package com.example.voiceassistant.aimatrix

import com.example.voiceassistant.vault.JarvisMemory
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The intent brain as the assistant uses it: taught phrases first, then the neural net.
 *
 * Two stages, both offline:
 *
 *  1. **Taught examples** (stored encrypted in the vault). If you said "make it bright"
 *     and taught JARVIS that means flashlight-on, a cosine similarity over the same
 *     hashed features matches immediate rephrasings of that phrase. This is how the
 *     brain "learns from your voice" without any retraining.
 *  2. **The neural network** (`assets/ai_matrix_model.json`, trained by the NumPy
 *     pipeline in `ai-matrix/`). Handles everything the corpus covered.
 *
 * Nothing here can execute anything: a suggestion is only ever a *label*. Turning a
 * label into a device action happens in the intent router, and every device action is
 * still confirmation-gated by the ViewModel.
 */
@Singleton
class OnDeviceBrain @Inject constructor(
    private val modelStore: IntentModelSource,
    private val memory: JarvisMemory
) {
    enum class Source { TAUGHT, MODEL }

    data class Suggestion(
        val label: String,
        val confidence: Float,
        val source: Source,
        val alternatives: List<Pair<String, Float>> = emptyList()
    )

    /** Labels the neural network can emit; anything else is ignored. */
    val knownLabels: Set<String> = LABELS

    /** True when the bundled model asset loaded successfully. */
    suspend fun isModelReady(): Boolean = modelStore.classifier() != null

    /** Human-readable reason the model is unavailable, if it is. */
    fun modelError(): String? = modelStore.lastError

    /**
     * Best guess for `text`, or `null` when nothing is confident enough.
     * Never throws: a broken vault or asset simply means "no suggestion".
     */
    suspend fun suggest(text: String): Suggestion? {
        if (text.isBlank()) return null
        taughtSuggestion(text)?.let { return it }
        return modelSuggestion(text)
    }

    /** Teaches a phrase→label pair; the label is normalised to upper case. */
    suspend fun teach(text: String, label: String): String {
        val normalised = label.uppercase()
        require(normalised in knownLabels) { "'$label' is not a known action" }
        memory.teach(text, normalised)
        return normalised
    }

    private suspend fun taughtSuggestion(text: String): Suggestion? {
        val examples = runCatching { memory.examples() }.getOrNull().orEmpty()
        if (examples.isEmpty()) return null

        val query = AiMatrixFeaturizer.termFrequencies(text)
        val queryNorm = norm(query)
        if (queryNorm == 0.0) return null

        var best: JarvisMemory.TaughtExample? = null
        var bestScore = 0.0
        for (example in examples) {
            val candidate = AiMatrixFeaturizer.termFrequencies(example.phrase)
            val candidateNorm = norm(candidate)
            if (candidateNorm == 0.0) continue
            val score = cosine(query, queryNorm, candidate, candidateNorm)
            if (score > bestScore) {
                bestScore = score
                best = example
            }
        }

        val match = best ?: return null
        if (bestScore < TAUGHT_MATCH_THRESHOLD) return null
        return Suggestion(match.label, bestScore.toFloat(), Source.TAUGHT)
    }

    private suspend fun modelSuggestion(text: String): Suggestion? {
        val classifier = modelStore.classifier() ?: return null
        val prediction = classifier.predict(text)
        if (prediction.label == UNKNOWN_LABEL) return null
        if (prediction.confidence < MODEL_CONFIDENCE_THRESHOLD) return null
        return Suggestion(
            label = prediction.label,
            confidence = prediction.confidence,
            source = Source.MODEL,
            alternatives = classifier.ranked(text).take(3)
        )
    }

    private fun norm(counts: Map<Int, Int>): Double {
        var sum = 0.0
        for (count in counts.values) sum += count.toDouble() * count
        return sqrt(sum)
    }

    private fun cosine(
        first: Map<Int, Int>,
        firstNorm: Double,
        second: Map<Int, Int>,
        secondNorm: Double
    ): Double {
        val smaller = if (first.size <= second.size) first else second
        val larger = if (smaller === first) second else first
        var dot = 0.0
        for ((bucket, count) in smaller) {
            val other = larger[bucket] ?: continue
            dot += count.toDouble() * other
        }
        return dot / (firstNorm * secondNorm)
    }

    companion object {
        const val UNKNOWN_LABEL = "UNKNOWN"

        /**
         * Minimum neural-network probability before its label is trusted. Kept low
         * because a false positive only costs a confirmation prompt, while a false
         * negative sends you to the (slower) generative path. Every device action is
         * confirmed by the user regardless of confidence.
         */
        const val MODEL_CONFIDENCE_THRESHOLD = 0.40f

        /** Cosine similarity required for a taught phrase to win. */
        const val TAUGHT_MATCH_THRESHOLD = 0.60

        val LABELS: Set<String> = setOf(
            "FLASHLIGHT_ON",
            "FLASHLIGHT_OFF",
            "ALARM_SET",
            "TIMER_SET",
            "DELETE_FILE",
            "DEVICE_STATUS",
            UNKNOWN_LABEL
        )
    }
}
