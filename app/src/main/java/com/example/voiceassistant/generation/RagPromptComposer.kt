package com.example.voiceassistant.generation

import com.example.voiceassistant.rag.RagContext

/**
 * The exact text handed to the on-device language model for a retrieval-augmented turn.
 *
 * Keeping this in one pure object means the prompt shape is testable (`RagPromptComposerTest`)
 * and can never drift between the llama.cpp path, the fallback path and the chat transcript:
 * whatever the user sees quoted in the bubble is what the model was shown.
 */
data class RagPrompt(
    val systemInstruction: String,
    val userPrompt: String,
    val usedContext: Boolean,
    val contextChunks: Int,
) {
    /** True when the question was answered without any local document excerpts. */
    val isPlain: Boolean get() = !usedContext
}

/** One excerpt quoted out of a composed prompt. */
data class QuotedExcerpt(val label: String, val text: String)

object RagPromptComposer {

    /** The marker the model is told to look for. Matches the text this composer emits. */
    const val CONTEXT_HEADER = "Context Information:"
    const val QUESTION_HEADER = "Question:"

    /**
     * Base instruction. Deliberately identical in spirit to the pre-RAG prompt: the model
     * may only produce speech, never actions. The RAG variant adds one paragraph about
     * quoting the user's own excerpts, and nothing else — a bigger prompt would eat the
     * 2 048-token context the excerpts need.
     */
    private val BASE_SYSTEM = """
        You are JARVIS, a concise conversational assistant running entirely on this device.
        Answer open-ended questions helpfully and honestly, preferably in one to three sentences
        and in the user's language. You have no internet access and may not know current facts.
        You cannot control the device or perform actions. Never claim to have changed settings,
        set an alarm, deleted a file, or performed any other action. Device actions are handled
        separately by deterministic app code and always require the user's explicit confirmation.
        If asked to perform an action, explain that you can only carry out supported actions through
        the assistant's confirmed controls.
    """.trimIndent()

    private val RAG_ADDENDUM = """
        The user's question comes with excerpts from documents stored on this device under
        "Context Information". Each excerpt is labelled with its source file and page.
        Answer from those excerpts first and cite the label you used, for example
        "(notes.md p.2)". If the excerpts do not contain the answer, say so plainly and answer
        from general knowledge only if you are confident — never invent a quotation, a page
        number, or a file name.
    """.trimIndent()

    fun systemInstruction(withContext: Boolean): String =
        if (withContext) "$BASE_SYSTEM\n\n$RAG_ADDENDUM" else BASE_SYSTEM

    /**
     * Composes the prompt for [question], injecting [context] when there is any.
     *
     * The shape is fixed:
     *
     * ```
     * Context Information:
     * [notes.md p.2]:
     * <excerpt>
     *
     * [plan.csv p.1]:
     * <excerpt>
     *
     * Question: <question>
     * ```
     */
    /**
     * Reads the excerpts back out of a composed prompt.
     *
     * The deterministic fallback uses this to quote the user's own text verbatim; the chat
     * transcript uses it to show which sources answered the question. The labels come out
     * exactly as [compose] wrote them, so the two can never disagree.
     */
    fun extractExcerpts(composedPrompt: String): List<QuotedExcerpt> {
        val contextStart = composedPrompt.indexOf(CONTEXT_HEADER)
        if (contextStart < 0) return emptyList()
        val questionAt = composedPrompt.indexOf("\n$QUESTION_HEADER", contextStart)
        val block = composedPrompt.substring(
            contextStart + CONTEXT_HEADER.length,
            if (questionAt < 0) composedPrompt.length else questionAt,
        )
        val labelRegex = Regex("""\[([^\]]{1,300})\]:\n""")
        val matches = labelRegex.findAll(block).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { index, match ->
            val bodyStart = match.range.last + 1
            val bodyEnd = matches.getOrNull(index + 1)?.range?.first ?: block.length
            QuotedExcerpt(
                label = match.groupValues[1].trim(),
                text = block.substring(bodyStart, bodyEnd).trim(),
            )
        }
    }

    fun compose(context: RagContext?, question: String): RagPrompt {
        val cleanQuestion = question.trim()
        if (context == null || context.isEmpty || context.text.isBlank()) {
            return RagPrompt(
                systemInstruction = systemInstruction(withContext = false),
                userPrompt = cleanQuestion,
                usedContext = false,
                contextChunks = 0,
            )
        }
        val userPrompt = buildString(context.text.length + cleanQuestion.length + 64) {
            append(CONTEXT_HEADER).append('\n')
            append(context.text.trim()).append('\n').append('\n')
            append(QUESTION_HEADER).append(' ').append(cleanQuestion)
        }
        return RagPrompt(
            systemInstruction = systemInstruction(withContext = true),
            userPrompt = userPrompt,
            usedContext = true,
            contextChunks = context.chunks.size,
        )
    }
}
