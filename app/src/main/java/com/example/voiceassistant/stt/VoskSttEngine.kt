package com.example.voiceassistant.stt

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Offline Vosk adapter. The model directory is provisioned locally and never fetched by the app. */
class VoskSttEngine : SttEngine {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var endpointResult: String? = null
    private val released = AtomicBoolean(false)

    @Synchronized
    override fun initialize(modelPath: String) {
        release()
        val modelDirectory = File(modelPath)
        // Validate the *contents*, not just the file names: an import that wrote empty files
        // used to pass a "does conf/model.conf exist" check and only blew up inside the
        // native loader, which surfaced as a generic "could not initialise" message.
        val problem = ModelArchiveExtractor.describeModelProblem(modelDirectory)
        require(problem == null) { "Not a usable Vosk model at $modelPath: $problem" }

        val loadedModel = Model(modelDirectory.absolutePath)
        try {
            recognizer = Recognizer(loadedModel, SAMPLE_RATE_HZ.toFloat())
            model = loadedModel
            released.set(false)
        } catch (error: Exception) {
            loadedModel.close()
            throw error
        }
    }

    @Synchronized
    override fun processAudio(audioData: ShortArray): Boolean {
        val currentRecognizer = requireRecognizer()
        val endpoint = currentRecognizer.acceptWaveForm(audioData, audioData.size)
        endpointResult = if (endpoint) readText(currentRecognizer.result, "text") else null
        return endpoint
    }

    @Synchronized
    override fun getPartialText(): String = readText(requireRecognizer().partialResult, "partial")

    @Synchronized
    override fun getFinalText(): String = endpointResult ?: readText(requireRecognizer().finalResult, "text")

    @Synchronized
    override fun reset() {
        requireRecognizer().reset()
        endpointResult = null
    }

    @Synchronized
    override fun release() {
        if (released.getAndSet(true)) return
        try {
            recognizer?.close()
        } finally {
            recognizer = null
            try {
                model?.close()
            } finally {
                model = null
                endpointResult = null
            }
        }
    }

    private fun requireRecognizer(): Recognizer =
        recognizer ?: error("Vosk STT engine has not been initialized")

    private fun readText(json: String, field: String): String = try {
        JSONObject(json).optString(field, "").trim()
    } catch (_: Exception) {
        ""
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
    }
}
