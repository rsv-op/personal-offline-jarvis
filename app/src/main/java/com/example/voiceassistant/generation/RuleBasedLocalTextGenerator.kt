package com.example.voiceassistant.generation

import com.example.voiceassistant.generation.llama.LlamaLocalTextGenerator
import com.example.voiceassistant.generation.llama.LlamaModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Chooses between the on-device language model and the built-in replies.
 *
 * When a GGUF model is installed the reply comes from llama.cpp running
 * locally. When there is no model the assistant still answers with the
 * deterministic replies in [ReplyComposer], so it is never mute.
 *
 * Device actions are *not* decided here. The intent router owns that, and the
 * confirmation gate still applies. This generator only produces speech for
 * things that are already safe to say out loud.
 */
@Singleton
class RuleBasedLocalTextGenerator @Inject constructor(
    private val llamaModelStore: LlamaModelStore,
    private val llama: Provider<LlamaLocalTextGenerator>
) : LocalTextGenerator {

    override suspend fun generateReply(userText: String): String =
        generateReply(RagPromptComposer.compose(context = null, question = userText))

    /**
     * Same routing, but the prompt keeps the `Context Information: … Question: …` shape the
     * RAG engine built. The deterministic fallback below quotes the excerpts itself, because
     * without a language model that is the only way the user sees what was retrieved.
     */
    override suspend fun generateReply(prompt: RagPrompt): String = withContext(Dispatchers.Default) {
        val modelPath = llamaModelStore.installedModelPath()
        if (modelPath != null) {
            // A real model exists — the user has paid the download cost, so use it.
            return@withContext llama.get().generateReply(prompt)
        }

        if (prompt.usedContext) {
            ReplyComposer.composeFromContext(prompt.userPrompt)
        } else {
            ReplyComposer.compose(prompt.userPrompt)
        }
    }

    override suspend fun onModelUpdated(): Unit = llama.get().onModelUpdated()
}

/**
 * Pure, testable reply composition. The only inputs are the user's text and the optional
 * model path. Anything that needs runtime state (vault, brain status) is looked up here so
 * the tests stay deterministic by injecting fakes.
 */
internal object ReplyComposer {

    /** Longest verbatim quote the fallback will read back; keeps a reply listenable. */
    private const val MAX_QUOTED_CHARACTERS = 600

    private val greetingTriggers = setOf(
        "hi", "hello", "hey", "hi there", "hello there", "good morning", "good evening", "good afternoon"
    )

    private val thanksTriggers = setOf("thanks", "thank you", "thx", "ty", "cheers")

    private val identityTriggers = setOf("who are you", "what are you", "your name", "are you jarvis")

    private val helpTriggers = setOf("help", "what can you do", "what do you do", "commands")

    private val abilityTriggers = setOf(
        "can you", "could you", "will you", "would you", "are you able"
    )

    /**
     * The honest answer when there is no language model but there *is* retrieved context.
     *
     * Rather than pretending to summarise, it reads back the best-matched excerpt and names
     * the file and page it came from. That is genuinely useful ("what does page 2 say?") and
     * it can never hallucinate, because every word of it is the user's own text.
     */
    fun composeFromContext(ragUserPrompt: String): String {
        val question = ragUserPrompt
            .substringAfter(RagPromptComposer.QUESTION_HEADER, missingDelimiterValue = "")
            .trim()
            .ifBlank { "your documents" }
        val excerpts = RagPromptComposer.extractExcerpts(ragUserPrompt)
        if (excerpts.isEmpty()) return compose(question)

        val best = excerpts.first()
        val more = excerpts.size - 1
        val builder = StringBuilder()
        builder.append("From ").append(best.label).append(":\n")
        builder.append(best.text.trim().take(MAX_QUOTED_CHARACTERS))
        if (more > 0) {
            builder.append("\n\n(")
            builder.append(more).append(" more excerpt(s) matched in your indexed documents.)")
        }
        return builder.toString()
    }

    fun compose(userText: String): String {
        val text = userText.trim()
        if (text.isEmpty()) return "I didn't catch that. Please try again."

        val lower = text.lowercase()
        val trimmed = lower.removeTrailingPunctuation()

        return when {
            trimmed in greetingTriggers ->
                "Hello. I am ready when you are."
            trimmed in thanksTriggers ->
                "You're welcome."
            identityTriggers.any { trimmed.contains(it) } ->
                "I am JARVIS, your offline voice assistant. I run entirely on this device."
            helpTriggers.any { trimmed == it } ->
                "I can toggle the flashlight, set alarms and timers, report the device status, " +
                    "look up a contact, and remember phrases you teach me. " +
                    "Open-ended questions work once you import a local reply model " +
                    "in .gguf format."
            trimmed.endsWith("?") && abilityTriggers.any { trimmed.startsWith(it) } ->
                "Device actions are handled by the assistant's confirmed controls, not by me. I can only answer with words."
            trimmed.endsWith("?") ->
                "I don't have a local reply model installed, so I can't answer that in detail. " +
                    "The model can be downloaded as a .gguf file and imported from the screen."
            looksLikeActionRequest(trimmed) ->
                "I can't take actions through this reply path. Tap the orb and speak the command, or use the controls on the screen."
            trimmed.length <= 4 ->
                "I heard '$text', but I need a longer sentence to answer."
            else ->
                "I don't have a local reply model installed, so I can only acknowledge: you said \"$text\". " +
                    "A .gguf reply model will let me give a real answer."
        }
    }

    private fun looksLikeActionRequest(lower: String): Boolean {
        // Heuristic: matches the intent router's action vocabulary closely enough to refuse safely.
        val actionKeywords = listOf(
            "flashlight", "torch", "alarm", "timer", "reminder",
            "set", "turn on", "turn off", "switch on", "switch off",
            "delete", "remove", "open", "launch", "start", "stop", "cancel"
        )
        return actionKeywords.any { lower.contains(it) }
    }

    private fun String.removeTrailingPunctuation(): String =
        trimEnd(' ', '.', '!', '?', ',', ';', ':')
}
