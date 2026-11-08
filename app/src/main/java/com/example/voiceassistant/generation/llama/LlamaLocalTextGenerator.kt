package com.example.voiceassistant.generation.llama

import com.example.voiceassistant.generation.LocalTextGenerator
import com.example.voiceassistant.generation.RagPrompt
import com.example.voiceassistant.generation.RagPromptComposer
import com.example.voiceassistant.memory.DeviceMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * llama.cpp-backed local conversational replies.
 *
 * The model runs entirely on the device through the native bridge in
 * `app/src/main/cpp/jarvis_llama.cpp`. Nothing here touches the network: the
 * app holds no INTERNET permission.
 *
 * The model is loaded lazily on first use and then kept resident, so the
 * 5–15 second cold start is paid once rather than on every question. The
 * foreground service is what keeps it alive across backgrounding; when the
 * device is under memory pressure the model is unloaded and the next reply
 * pays the cold start again.
 */
@Singleton
class LlamaLocalTextGenerator @Inject constructor(
    private val modelStore: LlamaModelStore,
    private val memoryBudget: DeviceMemory
) : LocalTextGenerator {

    private val engineMutex = Mutex()

    /** The path of the model currently loaded into llama.cpp, if any. */
    @Volatile
    private var loadedModelPath: String? = null

    /**
     * Text of the reply as it is produced, emitted so the UI can show the
     * answer appearing token by token.
     */
    private val _partialReply = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 16)
    val partialReply: SharedFlow<String> = _partialReply

    /** True when a model is loaded and ready to answer. */
    val isModelLoaded: Boolean get() = loadedModelPath != null

    /** llama.cpp version string, or an empty string when the native library is unavailable. */
    val backendVersion: String get() = LlamaNative.version()

    override suspend fun generateReply(userText: String): String =
        generateReply(RagPromptComposer.compose(context = null, question = userText))

    /**
     * Generation with an explicit prompt.
     *
     * This is the seam the RAG path uses: the excerpts arrive already formatted by
     * [RagPromptComposer], so the model sees the same `Context Information: … Question: …`
     * block the UI reported quoting, and this class needs no knowledge of the index.
     *
     * A cancellation requested through [cancel] (the `shaz` interrupt) surfaces as an
     * [IllegalStateException] so the caller can tell an interrupted answer from a failure.
     */
    override suspend fun generateReply(prompt: RagPrompt): String {
        require(prompt.userPrompt.isNotBlank()) { "Cannot generate a reply for empty input" }

        if (!LlamaNative.isAvailable) {
            throw IllegalStateException(
                "The on-device language model is not available on this device: " +
                    (LlamaNative.unavailableReason ?: "native library could not be loaded")
            )
        }

        // Clear any replayed text from a previous reply so the UI does not
        // show the last answer as if it were still streaming.
        _partialReply.tryEmit("")

        return withContext(Dispatchers.IO) {
            engineMutex.withLock {
                val modelPath = modelStore.installedModelPath()
                    ?: throw IllegalStateException("No local reply model is installed")
                ensureModelLoaded(modelPath)

                val decoder = Utf8StreamDecoder()
                val builder = StringBuilder()

                val result = LlamaNative.generate(
                    systemPrompt = prompt.systemInstruction,
                    userPrompt = prompt.userPrompt.take(MAX_INPUT_CHARACTERS),
                    maxTokens = MAX_OUTPUT_TOKENS,
                    temperature = TEMPERATURE
                ) { bytes ->
                    // The native side hands over RAW bytes: a token can end in
                    // the middle of a multi-byte character, so the decoder
                    // holds back the tail until it is complete.
                    val text = decoder.decode(bytes)
                    if (text.isNotEmpty()) {
                        builder.append(text)
                        _partialReply.tryEmit(builder.toString())
                    }
                }

                val tail = decoder.flush()
                if (tail.isNotEmpty()) {
                    builder.append(tail)
                    _partialReply.tryEmit(builder.toString())
                }

                when (result) {
                    LlamaNative.RESULT_CANCELLED ->
                        throw IllegalStateException("The reply was cancelled")

                    LlamaNative.RESULT_OK -> Unit

                    else -> throw IllegalStateException(
                        "The on-device model stopped unexpectedly (code $result)"
                    )
                }

                builder.toString()
                    .trim()
                    .take(MAX_REPLY_CHARACTERS)
                    .ifBlank {
                        "I couldn't form a useful answer. Please try asking another way."
                    }
            }
        }
    }

    override suspend fun onModelUpdated() {
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                unloadLocked()
            }
        }
    }

    /** Stops an in-flight generation. Safe to call when nothing is running. */
    fun cancel() {
        LlamaNative.cancel()
    }

    /**
     * Frees the model. Called by the foreground service when the device is
     * under memory pressure.
     */
    suspend fun releaseModel() {
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                unloadLocked()
            }
        }
    }

    private suspend fun ensureModelLoaded(modelPath: String) {
        if (loadedModelPath == modelPath && LlamaNative.isAvailable) return

        // Refuse to load a model the device cannot hold. Trying anyway means
        // the kernel OOM-kills the app, and on some phones it takes the whole
        // OS with it.
        val modelBytes = modelStore.installedModelSizeBytes()
        ModelTierPicker.refusalReason(memoryBudget.availableMemoryBytes, modelBytes)?.let { reason ->
            throw IllegalStateException(reason)
        }

        val loaded = LlamaNative.loadModel(
            modelPath = modelPath,
            contextSize = CONTEXT_SIZE,
            threads = CPU_THREAD_COUNT
        )
        if (loaded != 0) {
            throw IllegalStateException(
                "The on-device model could not be loaded (code $loaded). " +
                    "The file may be corrupt or not a GGUF model."
            )
        }
        loadedModelPath = modelPath
    }

    private fun unloadLocked() {
        if (loadedModelPath == null && !LlamaNative.isAvailable) return
        LlamaNative.unloadModel()
        loadedModelPath = null
    }

    private companion object {
        const val CPU_THREAD_COUNT = 4
        const val CONTEXT_SIZE = 2_048
        const val MAX_OUTPUT_TOKENS = 256
        const val MAX_INPUT_CHARACTERS = 2_000
        const val MAX_REPLY_CHARACTERS = 900
        const val TEMPERATURE = 0.7f

        /**
         * The reply model only produces speech. It never selects or executes
         * device actions — that stays with the deterministic intent router and
         * the confirmation gate, so a hallucinated reply can never turn into
         * an unconfirmed action.
         */
        val SYSTEM_INSTRUCTION = """
            You are JARVIS, a concise conversational assistant running entirely on this device.
            Answer open-ended questions helpfully and honestly, preferably in one to three sentences
            and in the user's language. You have no internet access and may not know current facts.
            You cannot control the device or perform actions. Never claim to have changed settings,
            set an alarm, deleted a file, or performed any other action. Device actions are handled
            separately by deterministic app code and always require the user's explicit confirmation.
            If asked to perform an action, explain that you can only carry out supported actions through
            the assistant's confirmed controls.
        """.trimIndent()
    }
}
