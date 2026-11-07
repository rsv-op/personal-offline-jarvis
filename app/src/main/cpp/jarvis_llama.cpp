// JARVIS local LLM — llama.cpp JNI bridge.
//
// Design notes (why it looks like this):
//
//  * llama.cpp is built as a separate static/shared library by the Gradle
//    `externalNativeBuild` configuration (see app/src/main/cpp/CMakeLists.txt).
//    This file is only the thin JNI layer that exposes that library to Kotlin.
//
//  * The chat template is taken FROM THE MODEL (`llama_model_get_chat_template`),
//    never hardcoded here. Models ship their own Jinja template in their GGUF
//    metadata; using the model's own template is what makes one binary work
//    with many different model families.
//
//  * Token streaming hands back RAW BYTES, not Java Strings. A single BPE token
//    can be a partial UTF-8 sequence (e.g. the first 2 bytes of a 3-byte
//    character). Returning raw bytes and reassembling UTF-8 on the Kotlin side
//    is the only correct way to stream; decoding each token independently
//    produces mojibake for any non-ASCII text.
//
//  * Everything is guarded by one mutex. llama.cpp contexts are not thread-safe,
//    and the foreground service can call `cancel` from a different thread while
//    a generation is in flight.

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "JarvisLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// The single, process-wide llama.cpp runtime. llama.cpp is initialised once
// and torn down once; models and contexts are created/destroyed around it.
std::once_flag g_backend_once;

struct EngineState {
    llama_model  * model  = nullptr;
    llama_context* context = nullptr;

    // Cached chat template owned by the model. Empty string means the model has
    // no template of its own, in which case we fall back to a plain
    // "role: content" formatting that every instruct-ish model understands.
    std::string chat_template;

    // Set by `cancel`; checked between decode steps so a long generation can be
    // stopped promptly from another thread.
    std::atomic<bool> cancelled{false};

    std::mutex mutex;
};

EngineState g_state;

// ---------------------------------------------------------------------------
// UTF-8 helpers
// ---------------------------------------------------------------------------

// Returns the number of bytes of the UTF-8 sequence that starts with `lead`.
// Returns 1 for invalid lead bytes so we always make forward progress.
int utf8_sequence_length(unsigned char lead) {
    if (lead < 0x80) return 1;
    if ((lead & 0xE0) == 0xC0) return 2;
    if ((lead & 0xF0) == 0xE0) return 3;
    if ((lead & 0xF8) == 0xF0) return 4;
    return 1;
}

// True when the buffer ends in the middle of a multi-byte sequence.
bool ends_with_partial_utf8(const std::string & data) {
    if (data.empty()) return false;
    // Walk back at most 3 bytes looking for a lead byte.
    for (int back = 1; back <= 4 && back <= static_cast<int>(data.size()); ++back) {
        unsigned char c = static_cast<unsigned char>(data[data.size() - back]);
        if ((c & 0xC0) == 0x80) {
            // Continuation byte — keep walking.
            continue;
        }
        int expected = utf8_sequence_length(c);
        // If the lead byte claims N bytes but we only have `back` of them, the
        // sequence is incomplete.
        return expected > back;
    }
    return false;
}

// ---------------------------------------------------------------------------
// Template handling
// ---------------------------------------------------------------------------

// Formats the conversation using the model's own chat template when it has
// one. Returns the raw prompt to tokenize.
std::string build_prompt(const std::string & system_prompt,
                          const std::string & user_prompt,
                          const std::string & chat_template) {
    if (chat_template.empty()) {
        // Fallback: a plain, widely-understood format.
        std::string prompt;
        if (!system_prompt.empty()) {
            prompt += "System: " + system_prompt + "\n";
        }
        prompt += "User: " + user_prompt + "\nAssistant:";
        return prompt;
    }

    std::vector<llama_chat_message> messages;
    std::string system = system_prompt;
    std::string user = user_prompt;
    if (!system.empty()) {
        messages.push_back({"system", system.c_str()});
    }
    messages.push_back({"user", user.c_str()});

    // First call with a zero-length buffer tells us how much space we need.
    int32_t needed = llama_chat_apply_template(
        chat_template.c_str(),
        messages.data(),
        messages.size(),
        /*add_ass=*/true,
        nullptr,
        0);
    if (needed <= 0) {
        LOGW("llama_chat_apply_template returned %d; falling back to plain format", needed);
        std::string prompt;
        if (!system.empty()) prompt += "System: " + system + "\n";
        prompt += "User: " + user + "\nAssistant:";
        return prompt;
    }

    std::vector<char> buffer(static_cast<size_t>(needed) + 1, '\0');
    int32_t written = llama_chat_apply_template(
        chat_template.c_str(),
        messages.data(),
        messages.size(),
        /*add_ass=*/true,
        buffer.data(),
        needed);
    if (written < 0) {
        LOGW("llama_chat_apply_template failed with %d; falling back to plain format", written);
        std::string prompt;
        if (!system.empty()) prompt += "System: " + system + "\n";
        prompt += "User: " + user + "\nAssistant:";
        return prompt;
    }
    return std::string(buffer.data(), static_cast<size_t>(written));
}

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_voiceassistant_generation_llama_LlamaNative_nativeInit(JNIEnv *, jclass) {
    std::call_once(g_backend_once, []() {
        llama_backend_init();
        LOGI("llama.cpp backend initialised, version %s", llama_version());
    });
}

JNIEXPORT jstring JNICALL
Java_com_example_voiceassistant_generation_llama_LlamaNative_nativeSystemInfo(JNIEnv * env, jclass) {
    const char * info = llama_print_system_info();
    return env->NewStringUTF(info ? info : "");
}

JNIEXPORT jboolean JNICALL
Java_com_example_voiceassistant_generation_llama_LlamaNative_nativeSupportsMmap(JNIEnv *, jclass) {
    return llama_supports_mmap() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Loads a GGUF model and creates a context for it.
 *
 * Returns 0 on success. On failure the reason is logged and a negative code is
 * returned; the Kotlin layer turns that into a readable message.
 */
JNIEXPORT jint JNICALL
Java_com_example_voiceassistant_generation_llama_LlamaNative_nativeLoadModel(
        JNIEnv * env,
        jclass,
        jstring jmodel_path,
        jint jcontext_size,
        jint jthreads) {
    const char * model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    if (model_path == nullptr) {
        return -1;
    }

    std::lock_guard<std::mutex> lock(g_state.mutex);

    // Free any previous model first so a re-import never leaks.
    if (g_state.context != nullptr) {
        llama_free(g_state.context);
        g_state.context = nullptr;
    }
    if (g_state.model != nullptr) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
    }
    g_state.chat_template.clear();
    g_state.cancelled.store(false);

    llama_model_params model_params = llama_model_default_params();
    // mmap is the default in this llama.cpp version (the old `use_mmap` /
    // `use_mlock` flags were removed from `llama_model_params`). Keeping the
    // default matters on a phone: mapped pages can be evicted by the OS under
    // pressure and paged back in on demand, so a multi-GB model does not pin
    // the whole resident set and get the app OOM-killed.
    model_params.n_gpu_layers = 0; // CPU only: no GPU offload in this build

    LOGI("loading model from %s", model_path);
    llama_model * model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);

    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed");
        return -2;
    }
    g_state.model = model;

    // The chat template lives in the model's own GGUF metadata. Passing
    // `nullptr` asks for the default template the model shipped with.
    const char * tmpl = llama_model_chat_template(model, nullptr);
    g_state.chat_template = tmpl ? std::string(tmpl) : std::string();
    LOGI("model loaded, chat template is %s",
         g_state.chat_template.empty() ? "<none, using plain format>" : "present");

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(jcontext_size > 0 ? jcontext_size : 2048);
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    ctx_params.n_threads = jthreads > 0 ? jthreads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;
    // Keep the KV cache on the CPU: this build is CPU-only by design. Vulkan is
    // off because on many Adreno GPUs the Vulkan backend is slower than the
    // well-optimised NEON/dotprod CPU kernels.
    ctx_params.offload_kqv = false;

    llama_context * context = llama_init_from_model(model, ctx_params);
    if (context == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        g_state.model = nullptr;
        return -3;
    }
    g_state.context = context;

    LOGI("context ready: n_ctx=%u n_batch=%u n_threads=%d",
         llama_n_ctx(context), llama_n_batch(context), ctx_params.n_threads);
    return 0;
}

/**
 * Runs one generation.
 *
 * The reply is streamed back to Kotlin through a callback so the UI can show
 * tokens as they arrive. `onToken` receives a byte[] which may be a PARTIAL
 * UTF-8 sequence; the Kotlin side reassembles it.
 *
 * Returns 0 on success, 1 if cancelled, negative on error.
 */
JNIEXPORT jint JNICALL
Java_com_example_voiceassistant_generation_llama_LlamaNative_nativeGenerate(
        JNIEnv * env,
        jclass,
        jstring jsystem_prompt,
        jstring juser_prompt,
        jint jmax_tokens,
        jfloat jtemperature,
        jobject jon_token) {
    const char * system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    const char * user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    if (system_prompt == nullptr || user_prompt == nullptr) {
        if (system_prompt) env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
        if (user_prompt) env->ReleaseStringUTFChars(juser_prompt, user_prompt);
        return -1;
    }

    jclass on_token_class = env->GetObjectClass(jon_token);
    jmethodID on_token_method = env->GetMethodID(on_token_class, "onToken", "([B)V");
    if (on_token_method == nullptr) {
        env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
        env->ReleaseStringUTFChars(juser_prompt, user_prompt);
        LOGE("could not find onToken([B)V on the callback object");
        return -2;
    }

    std::string prompt;
    std::string system(system_prompt);
    std::string user(user_prompt);
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    int result = 0;

    {
        std::lock_guard<std::mutex> lock(g_state.mutex);

        if (g_state.context == nullptr || g_state.model == nullptr) {
            LOGE("generate called without a loaded model");
            return -3;
        }

        prompt = build_prompt(system, user, g_state.chat_template);

        const llama_vocab * vocab = llama_model_get_vocab(g_state.model);

        // --- tokenize the prompt -------------------------------------------
        std::vector<llama_token> tokens(prompt.size() + 16);
        int32_t token_count = llama_tokenize(
            vocab,
            prompt.data(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            /*add_special=*/true,
            /*parse_special=*/false);
        if (token_count < 0) {
            // The buffer was too small: llama.cpp tells us how much it needs.
            tokens.resize(static_cast<size_t>(-token_count));
            token_count = llama_tokenize(
                vocab,
                prompt.data(),
                static_cast<int32_t>(prompt.size()),
                tokens.data(),
                static_cast<int32_t>(tokens.size()),
                true,
                false);
        }
        if (token_count <= 0) {
            LOGE("failed to tokenize the prompt (%d)", token_count);
            return -4;
        }
        tokens.resize(static_cast<size_t>(token_count));
        LOGI("prompt tokenized into %d tokens", token_count);

        // --- reset the KV cache so each reply is independent ----------------
        llama_memory_clear(llama_get_memory(g_state.context), false);

        // --- sampler --------------------------------------------------------
        llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
        chain_params.no_perf = true;
        llama_sampler * chain = llama_sampler_chain_init(chain_params);
        if (chain == nullptr) {
            LOGE("could not create the sampler chain");
            return -5;
        }
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(jtemperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t) LLAMA_DEFAULT_SEED));

        // --- prompt processing ---------------------------------------------
        llama_batch batch = llama_batch_get_one(tokens.data(), token_count);
        int32_t decode_result = llama_decode(g_state.context, batch);
        if (decode_result != 0) {
            LOGE("llama_decode failed while processing the prompt: %d", decode_result);
            llama_sampler_free(chain);
            return -6;
        }

        // --- generation ------------------------------------------------------
        const int max_tokens = jmax_tokens > 0 ? jmax_tokens : 256;
        std::string piece_buffer(256, '\0');
        int produced = 0;

        for (int i = 0; i < max_tokens; ++i) {
            if (g_state.cancelled.load()) {
                LOGI("generation cancelled after %d tokens", produced);
                result = 1;
                break;
            }

            const llama_token token = llama_sampler_sample(chain, g_state.context, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                break;
            }

            llama_sampler_accept(chain, token);

            // Detokenise into a raw byte buffer. `lstrip` = -1 keeps the
            // leading space that some tokenizers attach to the first word.
            const int32_t piece_length = llama_token_to_piece(
                vocab, token, piece_buffer.data(), (int32_t) piece_buffer.size(), -1, false);
            if (piece_length > 0) {
                // Hand the RAW bytes to Kotlin. We deliberately do not decode
                // them here: a token may end mid-character.
                jbyteArray raw = env->NewByteArray(piece_length);
                if (raw != nullptr) {
                    env->SetByteArrayRegion(raw, 0, piece_length,
                                            (const jbyte *) piece_buffer.data());
                    env->CallVoidMethod(jon_token, on_token_method, raw);
                    env->DeleteLocalRef(raw);
                }
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    LOGE("the token callback threw; aborting generation");
                    result = -7;
                    break;
                }
            }

            ++produced;

            // Decode the sampled token to advance the context.
            llama_token next_token = token;
            llama_batch next_batch = llama_batch_get_one(&next_token, 1);
            decode_result = llama_decode(g_state.context, next_batch);
            if (decode_result != 0) {
                LOGE("llama_decode failed while generating: %d", decode_result);
                result = -8;
                break;
            }
        }

        llama_sampler_free(chain);
        LOGI("generation finished, %d tokens produced", produced);
    }

    return result;
}

JNIEXPORT void JNICALL
Java_com_example_voiceassistant_generation_llama_LlamaNative_nativeCancel(JNIEnv *, jclass) {
    g_state.cancelled.store(true);
}

JNIEXPORT void JNICALL
Java_com_example_voiceassistant_generation_llama_LlamaNative_nativeUnloadModel(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.context != nullptr) {
        llama_free(g_state.context);
        g_state.context = nullptr;
    }
    if (g_state.model != nullptr) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
    }
    g_state.chat_template.clear();
    g_state.cancelled.store(false);
    LOGI("model unloaded");
}

JNIEXPORT jstring JNICALL
Java_com_example_voiceassistant_generation_llama_LlamaNative_nativeVersion(JNIEnv * env, jclass) {
    const char * version = llama_version();
    return env->NewStringUTF(version ? version : "");
}

} // extern "C"
