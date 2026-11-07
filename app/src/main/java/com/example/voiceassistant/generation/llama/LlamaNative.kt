package com.example.voiceassistant.generation.llama

/**
 * Raw JNI surface of the llama.cpp bridge (`app/src/main/cpp/jarvis_llama.cpp`).
 *
 * Every method here is backed by a native llama.cpp call. The native library is
 * loaded lazily: on a device where the APK's ABI does not match (or on a JVM
 * unit test) [isAvailable] returns false and the caller falls back to the
 * rule-based generator instead of crashing.
 *
 * The library is named `jarvis_llama` and is built by the Gradle
 * `externalNativeBuild` configuration from `app/src/main/cpp/CMakeLists.txt`.
 */
object LlamaNative {

    /** Outcome codes returned by [generate]; mirrors the native bridge. */
    const val RESULT_OK = 0
    const val RESULT_CANCELLED = 1

    @Volatile
    private var loaded = false

    @Volatile
    private var loadFailure: String? = null

    /** True when the native library loaded and llama.cpp can be used. */
    val isAvailable: Boolean
        get() {
            if (loaded) return true
            if (loadFailure != null) return false
            synchronized(this) {
                if (loaded) return true
                if (loadFailure != null) return false
                return try {
                    System.loadLibrary("jarvis_llama")
                    nativeInit()
                    loaded = true
                    true
                } catch (error: Throwable) {
                    // A missing or mismatched .so must not take the app down:
                    // the assistant has a rule-based fallback for exactly this.
                    loadFailure = error.message ?: error.javaClass.simpleName
                    false
                }
            }
        }

    /** Why the native library is unavailable, or `null` when it is available. */
    val unavailableReason: String? get() = loadFailure

    /** Human-readable description of the CPU backend in use. */
    fun systemInfo(): String = if (isAvailable) nativeSystemInfo() else ""

    /** llama.cpp version string, e.g. `b12189`. */
    fun version(): String = if (isAvailable) nativeVersion() else ""

    /** Whether this build supports memory-mapping the model (always true on Android). */
    fun supportsMmap(): Boolean = isAvailable && nativeSupportsMmap()

    /**
     * Loads a GGUF model and creates an inference context for it.
     *
     * @param modelPath absolute path to the `.gguf` file in app-private storage.
     * @param contextSize context window in tokens.
     * @param threads number of CPU threads to use.
     * @return 0 on success, or a negative error code.
     */
    fun loadModel(modelPath: String, contextSize: Int, threads: Int): Int {
        require(isAvailable) { "llama.cpp native library is not available" }
        return nativeLoadModel(modelPath, contextSize, threads)
    }

    /**
     * Runs one generation, streaming raw bytes to [onToken] as they arrive.
     *
     * The bytes handed to [onToken] are NOT guaranteed to be valid UTF-8 on
     * their own — a BPE token can end in the middle of a multi-byte character.
     * Use [Utf8StreamDecoder] to reassemble them into displayable text.
     *
     * @return [RESULT_OK], [RESULT_CANCELLED], or a negative error code.
     */
    fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        onToken: (ByteArray) -> Unit
    ): Int {
        require(isAvailable) { "llama.cpp native library is not available" }
        return nativeGenerate(systemPrompt, userPrompt, maxTokens, temperature, TokenSink(onToken))
    }

    /** Requests that the in-flight generation stop as soon as possible. */
    fun cancel() {
        if (isAvailable) nativeCancel()
    }

    /** Frees the model and context. Safe to call when nothing is loaded. */
    fun unloadModel() {
        if (isAvailable) nativeUnloadModel()
    }

    /** Adapts a Kotlin lambda to the single-method callback JNI expects. */
    private class TokenSink(private val onToken: (ByteArray) -> Unit) {
        @Suppress("unused") // called reflectively from JNI
        fun onToken(bytes: ByteArray) {
            onToken(bytes)
        }
    }

    @JvmStatic private external fun nativeInit()

    @JvmStatic private external fun nativeSystemInfo(): String

    @JvmStatic private external fun nativeVersion(): String

    @JvmStatic private external fun nativeSupportsMmap(): Boolean

    @JvmStatic private external fun nativeLoadModel(
        modelPath: String,
        contextSize: Int,
        threads: Int
    ): Int

    @JvmStatic private external fun nativeGenerate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        sink: TokenSink
    ): Int

    @JvmStatic private external fun nativeCancel()

    @JvmStatic private external fun nativeUnloadModel()
}
