package com.example.voiceassistant.generation

/** Generates conversational replies locally; it is never used to select or execute device actions. */
interface LocalTextGenerator {
    suspend fun generateReply(userText: String): String

    /**
     * Generation from an already-composed prompt.
     *
     * The RAG path uses this overload so the excerpts and the question reach the model in
     * one fixed shape ([RagPromptComposer]) instead of being concatenated on the way past.
     * Implementations that have no prompt-level control (the deterministic fallback) can
     * ignore the split; the default keeps them compiling unchanged.
     */
    suspend fun generateReply(prompt: RagPrompt): String = generateReply(prompt.userPrompt)

    /** Called after replacing the model file so any native engine using the old file is released. */
    suspend fun onModelUpdated()
}
