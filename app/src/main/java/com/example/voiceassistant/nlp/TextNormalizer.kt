package com.example.voiceassistant.nlp

/** Cleans raw speech-recognition output by normalizing case, punctuation, and fillers. */
class TextNormalizer {
    private val fillers = listOf(
        "uh", "um", "like", "please", "can you", "could you", "just", "actually", "i mean", "hey"
    )

    fun normalize(input: String): String {
        var cleanText = input.lowercase().trim()
            .replace(Regex("[^a-z0-9\\s]"), " ")
        fillers.forEach { filler ->
            cleanText = cleanText.replace(Regex("\\b${Regex.escape(filler)}\\b"), " ")
        }
        return cleanText.replace(Regex("\\s+"), " ").trim()
    }
}
