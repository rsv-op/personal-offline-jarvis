package com.example.voiceassistant.generation

import android.content.Context
import com.example.voiceassistant.memory.DeviceMemory
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** LiteRT-LM CPU backend for short, stateless, on-device conversational replies. */
@Singleton
class LiteRtLocalTextGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelStore: LocalGenerationModelStore,
    private val memoryBudget: DeviceMemory
) : LocalTextGenerator {
    private val engineMutex = Mutex()
    private var engine: Engine? = null
    private var loadedModelPath: String? = null

    override suspend fun generateReply(userText: String): String = withContext(Dispatchers.IO) {
        require(userText.isNotBlank()) { "Cannot generate a reply for empty input" }
        engineMutex.withLock {
            val modelPath = modelStore.installedModelPath()
                ?: throw IllegalStateException("No local reply model is installed")
            val activeEngine = getOrCreateEngine(modelPath)
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.7),
                maxOutputToken = MAX_OUTPUT_TOKENS
            )

            activeEngine.createConversation(conversationConfig).use { conversation ->
                conversation.sendMessageAsync(userText.take(MAX_INPUT_CHARACTERS))
                    .toList()
                    .joinToString(separator = "") { message -> message.toString() }
                    .trim()
                    .take(MAX_REPLY_CHARACTERS)
                    .ifBlank { "I couldn't form a useful answer. Please try asking another way." }
            }
        }
    }

    override suspend fun onModelUpdated(): Unit = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            try {
                engine?.takeIf(Engine::isInitialized)?.close()
            } finally {
                engine = null
                loadedModelPath = null
            }
            Unit
        }
    }

    private fun getOrCreateEngine(modelPath: String): Engine {
        val currentEngine = engine
        if (currentEngine != null && loadedModelPath == modelPath && currentEngine.isInitialized()) {
            return currentEngine
        }

        if (currentEngine?.isInitialized() == true) currentEngine.close()
        engine = null
        loadedModelPath = null

        // Refuse to load a model the device cannot hold. The kernel will OOM-kill us if
        // we try to load a 2 GB LiteRT model on a phone with 4 GB free RAM, and on some
        // devices that takes down the whole OS. 2.5× headroom covers the model plus the
        // KV cache LiteRT allocates on top of it; 2× was tried first and was still too
        // tight on the user's device.
        val modelFile = File(modelPath)
        val modelBytes = if (modelFile.isFile) modelFile.length() else 0L
        memoryBudget.refusalReason(modelBytes, headroomMultiplier = 2.5)?.let { reason ->
            throw IllegalStateException(reason)
        }

        val newEngine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(threadCount = CPU_THREAD_COUNT),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = context.cacheDir.absolutePath
            )
        )
        try {
            newEngine.initialize()
            engine = newEngine
            loadedModelPath = modelPath
            return newEngine
        } catch (error: Exception) {
            if (newEngine.isInitialized()) newEngine.close()
            throw error
        }
    }

    private companion object {
        const val CPU_THREAD_COUNT = 4
        const val MAX_CONTEXT_TOKENS = 2_048
        const val MAX_OUTPUT_TOKENS = 192
        const val MAX_INPUT_CHARACTERS = 2_000
        const val MAX_REPLY_CHARACTERS = 900

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
