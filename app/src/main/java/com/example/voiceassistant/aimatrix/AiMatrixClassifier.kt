package com.example.voiceassistant.aimatrix

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * On-device forward pass of the trained ai-matrix network.
 *
 * Architecture (mirrors `ai-matrix/ai_matrix/model.py`):
 *   normalized term frequencies -> dense(hiddenSize) + ReLU -> dense(labels) -> softmax
 *
 * Weights are stored row-major: `hiddenWeights[bucket * hiddenSize + unit]`, which is
 * exactly how the Python exporter writes `hiddenWeights[bucket][unit]`. Inference walks
 * only the non-zero features, so a typical utterance costs a handful of small dot
 * products — microseconds on a phone, no allocation per token.
 */
class AiMatrixClassifier(
    val labels: List<String>,
    val buckets: Int,
    val hiddenSize: Int,
    private val hiddenWeights: FloatArray,
    private val hiddenBias: FloatArray,
    private val outputWeights: FloatArray,
    private val outputBias: FloatArray,
    val metadata: JSONObject? = null
) {
    data class Prediction(val label: String, val confidence: Float, val scores: Map<String, Float>)

    init {
        require(labels.isNotEmpty()) { "The model must contain at least one label" }
        require(buckets > 0 && hiddenSize > 0) { "Model dimensions must be positive" }
        require(hiddenWeights.size == buckets * hiddenSize) {
            "hiddenWeights must hold $buckets x $hiddenSize values, found ${hiddenWeights.size}"
        }
        require(hiddenBias.size == hiddenSize) { "hiddenBias must hold $hiddenSize values" }
        require(outputWeights.size == hiddenSize * labels.size) {
            "outputWeights must hold $hiddenSize x ${labels.size} values"
        }
        require(outputBias.size == labels.size) { "outputBias must hold ${labels.size} values" }
    }

    /** Probability for every label, in `labels` order. */
    fun probabilities(text: String): FloatArray = probabilities(AiMatrixFeaturizer.termFrequencies(text, buckets), buckets)

    /** Probability for every label from a pre-computed `{bucket: count}` vector. */
    fun probabilities(counts: Map<Int, Int>, bucketCount: Int = buckets): FloatArray {
        require(bucketCount == buckets) {
            "Feature vector was built for $bucketCount buckets but the model expects $buckets"
        }
        val hidden = FloatArray(hiddenSize)
        System.arraycopy(hiddenBias, 0, hidden, 0, hiddenSize)

        var totalCount = 0.0
        for (count in counts.values) {
            totalCount += count.toDouble() * count
        }
        val norm = sqrt(totalCount).toFloat()
        if (norm > 0f) {
            for ((bucket, count) in counts) {
                if (bucket !in 0 until buckets) continue
                val scaled = count.toFloat() / norm
                val offset = bucket * hiddenSize
                for (unit in 0 until hiddenSize) {
                    hidden[unit] += scaled * hiddenWeights[offset + unit]
                }
            }
        }

        // ReLU
        for (unit in hidden.indices) {
            if (hidden[unit] < 0f) hidden[unit] = 0f
        }

        val logits = FloatArray(labels.size)
        for (label in labels.indices) {
            var sum = outputBias[label]
            for (unit in 0 until hiddenSize) {
                sum += hidden[unit] * outputWeights[unit * labels.size + label]
            }
            logits[label] = sum
        }
        return softmax(logits)
    }

    /** Highest-probability label for `text`. */
    fun predict(text: String): Prediction {
        val probabilities = probabilities(text)
        var best = 0
        for (index in probabilities.indices) {
            if (probabilities[index] > probabilities[best]) best = index
        }
        val scores = LinkedHashMap<String, Float>(labels.size)
        for (index in labels.indices) scores[labels[index]] = probabilities[index]
        return Prediction(labels[best], probabilities[best], scores)
    }

    /** Sorted `label -> probability` pairs, most likely first. */
    fun ranked(text: String): List<Pair<String, Float>> =
        predict(text).scores.entries.sortedByDescending { it.value }.map { it.key to it.value }

    private fun softmax(logits: FloatArray): FloatArray {
        var max = logits[0]
        for (value in logits) if (value > max) max = value
        var sum = 0f
        val exponentiated = FloatArray(logits.size)
        for (index in logits.indices) {
            val value = exp((logits[index] - max).toDouble()).toFloat()
            exponentiated[index] = value
            sum += value
        }
        if (sum <= 0f) {
            val uniform = 1f / logits.size
            for (index in exponentiated.indices) exponentiated[index] = uniform
            return exponentiated
        }
        for (index in exponentiated.indices) {
            exponentiated[index] = exponentiated[index] / sum
        }
        return exponentiated
    }

    companion object {
        const val SUPPORTED_FORMAT_VERSION = 2

        /** Parses the JSON artifact written by `ai_matrix.export.export_model`. */
        fun fromJson(json: String): AiMatrixClassifier = fromJson(JSONObject(json))

        fun fromJson(payload: JSONObject): AiMatrixClassifier {
            require(payload.optString("format") == "ai-matrix") {
                "This file is not an ai-matrix model"
            }
            val formatVersion = payload.optInt("formatVersion", 0)
            require(formatVersion == SUPPORTED_FORMAT_VERSION) {
                "Model format v$formatVersion is not supported (expected v$SUPPORTED_FORMAT_VERSION)"
            }
            val featureVersion = payload.optInt("featureVersion", 0)
            require(featureVersion == AiMatrixFeaturizer.FEATURE_VERSION) {
                "Model was trained with feature version $featureVersion, but this build uses " +
                    "${AiMatrixFeaturizer.FEATURE_VERSION}. Re-run `python -m ai_matrix.cli export`."
            }

            val buckets = payload.getInt("buckets")
            val hiddenSize = payload.getInt("hiddenSize")
            val labelsJson = payload.getJSONArray("labels")
            val labels = List(labelsJson.length()) { index -> labelsJson.getString(index) }

            return AiMatrixClassifier(
                labels = labels,
                buckets = buckets,
                hiddenSize = hiddenSize,
                hiddenWeights = readMatrix(payload.getJSONArray("hiddenWeights")).also {
                    require(it.size == buckets * hiddenSize) { "hiddenWeights has the wrong size" }
                },
                hiddenBias = readVector(payload.getJSONArray("hiddenBias")),
                outputWeights = readMatrix(payload.getJSONArray("outputWeights")),
                outputBias = readVector(payload.getJSONArray("outputBias")),
                metadata = payload.optJSONObject("metadata")
            )
        }

        private fun readMatrix(array: org.json.JSONArray): FloatArray {
            val perRow = if (array.length() == 0) 0 else array.getJSONArray(0).length()
            val values = FloatArray(array.length() * perRow)
            var offset = 0
            for (row in 0 until array.length()) {
                val inner = array.getJSONArray(row)
                require(inner.length() == perRow) { "Ragged weight matrix at row $row" }
                for (column in 0 until perRow) {
                    values[offset++] = inner.getDouble(column).toFloat()
                }
            }
            return values
        }

        private fun readVector(array: org.json.JSONArray): FloatArray =
            FloatArray(array.length()) { index -> array.getDouble(index).toFloat() }
    }
}
