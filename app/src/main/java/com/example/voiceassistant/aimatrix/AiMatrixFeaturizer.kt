package com.example.voiceassistant.aimatrix

/**
 * Kotlin port of `ai-matrix/ai_matrix/features.py`.
 *
 * The two implementations must produce identical buckets: the Python trainer
 * decides the weights, this code applies them on the phone. `AiMatrixFeaturizerTest`
 * pins the contract against `ai_matrix/tests/golden_features.json`, and the
 * `featureVersion` field in the exported model is compared at load time so a stale
 * asset is rejected instead of silently mis-routing commands.
 *
 * Featurization:
 *  1. lowercase, replace every non `[a-z0-9\s]` with a space, delete fillers, collapse whitespace
 *  2. tokens = unigrams + adjacent bigrams + word affixes (`p:` prefix, `s:` suffix, words >= 4 chars)
 *  3. bucket = floorMod(token.hashCode(), BUCKETS) — the Java hash, so both languages agree
 *  4. features are term frequencies; the consumer L2-normalizes them
 */
object AiMatrixFeaturizer {

    /** Number of hashing buckets. Must match `features.BUCKETS`. */
    const val BUCKETS = 4096

    /** Must match `features.FEATURE_VERSION`; the asset declares the version it was trained with. */
    const val FEATURE_VERSION = 2

    /** Words shorter than this contribute no affix features. */
    const val AFFIX_MIN_LENGTH = 4

    /**
     * Filler phrases removed before featurization (see `features.FILLERS`).
     * Ordered longest-first so multi-word fillers are removed before their parts.
     */
    val FILLERS: List<String> = listOf(
        "uh", "um", "err", "like", "please", "can you", "could you", "would you",
        "just", "actually", "i mean", "hey", "ok", "okay"
    )

    private val nonAlphanumeric = Regex("[^a-z0-9\\s]")
    private val whitespace = Regex("\\s+")
    private val fillerPatterns: List<Regex> =
        FILLERS.map { filler -> Regex("\\b" + Regex.escape(filler) + "\\b") }

    /** Step 1: lowercase, strip punctuation, drop fillers, collapse whitespace. */
    fun normalize(text: String): String {
        var clean = nonAlphanumeric.replace(text.lowercase(), " ")
        for (pattern in fillerPatterns) {
            clean = pattern.replace(clean, " ")
        }
        return whitespace.replace(clean, " ").trim()
    }

    /** Step 2: unigrams, bigrams, then affix features. */
    fun tokens(text: String): List<String> {
        val words = normalize(text).split(' ').filter(String::isNotEmpty)
        val tokens = ArrayList<String>(words.size * 3)
        tokens.addAll(words)
        for (index in 0 until words.size - 1) {
            tokens.add(words[index] + "_" + words[index + 1])
        }
        for (word in words) {
            if (word.length >= AFFIX_MIN_LENGTH) {
                tokens.add("p:" + word.substring(0, AFFIX_MIN_LENGTH))
                tokens.add("s:" + word.substring(word.length - AFFIX_MIN_LENGTH))
            }
        }
        return tokens
    }

    /** Step 3: `java.lang.String.hashCode()` folded into `[0, BUCKETS)`. */
    fun bucketFor(token: String, buckets: Int = BUCKETS): Int = Math.floorMod(token.hashCode(), buckets)

    /** Step 4a: `{bucket: count}` term frequencies. */
    fun termFrequencies(text: String, buckets: Int = BUCKETS): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        for (token in tokens(text)) {
            val bucket = bucketFor(token, buckets)
            counts[bucket] = (counts[bucket] ?: 0) + 1
        }
        return counts
    }

    /**
     * Step 4b: L2-normalized dense vector, matching `features.featurize`.
     * Kept for parity tests; inference uses [termFrequencies] directly and never
     * materializes the dense vector.
     */
    fun featurize(text: String, buckets: Int = BUCKETS): FloatArray {
        val vector = FloatArray(buckets)
        var sumOfSquares = 0.0
        for ((bucket, count) in termFrequencies(text, buckets)) {
            vector[bucket] = count.toFloat()
            sumOfSquares += count.toDouble() * count
        }
        val norm = kotlin.math.sqrt(sumOfSquares).toFloat()
        if (norm > 0f) {
            for (index in vector.indices) {
                vector[index] = vector[index] / norm
            }
        }
        return vector
    }
}
