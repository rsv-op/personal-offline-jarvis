package com.example.voiceassistant.nlp

/**
 * Cleans raw speech-recognition output by normalizing case, punctuation, and fillers.
 *
 * The filler list is shared with the ai-matrix featurizer
 * (`com.example.voiceassistant.aimatrix.AiMatrixFeaturizer.FILLERS`, generated from
 * `ai-matrix/ai_matrix/features.py`), so the rule stage and the neural stage always see
 * the same text.
 */
class TextNormalizer {
    private val fillers = FILLERS

    fun normalize(input: String): String {
        var cleanText = input.lowercase().trim()
            .replace(Regex("[^a-z0-9\\s]"), " ")
        fillers.forEach { filler ->
            cleanText = cleanText.replace(Regex("\\b${Regex.escape(filler)}\\b"), " ")
        }
        return cleanText.replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        /** Kept in sync with `ai-matrix/ai_matrix/features.py`. */
        val FILLERS: List<String> = listOf(
            "uh", "um", "err", "like", "please", "can you", "could you", "would you",
            "just", "actually", "i mean", "hey", "ok", "okay"
        )
    }
}
