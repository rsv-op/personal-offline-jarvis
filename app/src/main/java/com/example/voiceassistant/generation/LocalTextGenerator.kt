package com.example.voiceassistant.generation

/** Generates conversational replies locally; it is never used to select or execute device actions. */
interface LocalTextGenerator {
    suspend fun generateReply(userText: String): String

    /** Called after replacing the model file so any native engine using the old file is released. */
    suspend fun onModelUpdated()
}
