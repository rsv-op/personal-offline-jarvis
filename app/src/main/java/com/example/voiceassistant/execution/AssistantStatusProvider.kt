package com.example.voiceassistant.execution

import com.example.voiceassistant.aimatrix.IntentModelSource
import com.example.voiceassistant.stt.VoskModelStore
import com.example.voiceassistant.vault.JarvisMemory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Describes what the assistant itself is made of — which models are installed, how many
 * phrases you have taught, where the vault key lives. Used by [StatusExecutor] so
 * "what can you do" is answered from real state instead of a canned line.
 */
@Singleton
class AssistantStatusProvider @Inject constructor(
    private val modelStore: IntentModelSource,
    private val voskModelStore: VoskModelStore,
    private val memory: JarvisMemory
) {
    suspend fun describe(): String {
        val parts = ArrayList<String>(4)

        val speech = voskModelStore.installedModelPath()
        parts += if (speech == null) {
            "No offline speech model is installed, so I cannot hear you yet."
        } else {
            val size = voskModelStore.installedModelSizeMb()
            val sizeText = if (size == null) "" else String.format(java.util.Locale.US, " (%.0f megabytes)", size)
            "Offline speech recognition is running on the imported Vosk model$sizeText."
        }

        val classifier = modelStore.classifier()
        parts += when {
            classifier == null ->
                "The intent brain is unavailable: ${modelStore.lastError ?: "the bundled model is missing"}."
            else -> {
                val metadata = classifier.metadata
                val examples = metadata?.optInt("trainingExamples") ?: 0
                val accuracy = metadata?.optDouble("holdoutAccuracy", 0.0) ?: 0.0
                if (examples > 0) {
                    "The intent brain is ready: $examples training phrases, " +
                        "${(accuracy * 100).toInt()} percent accuracy on phrases it had not heard before."
                } else {
                    "The intent brain is ready with ${classifier.labels.size} actions."
                }
            }
        }

        val summary = memory.summary()
        parts += "I have ${summary.taughtExamples} taught phrases in the encrypted vault " +
            "(${summary.backend}) and ${summary.loggedActions} logged actions."
        parts += "Everything runs on this device with no internet permission."

        return parts.joinToString(" ")
    }

    /** Short one-line status for the UI instead of the spoken version. */
    suspend fun shortStatus(): String {
        val classifier = modelStore.classifier()
        val brainText = if (classifier == null) "intent brain unavailable" else "intent brain ready"
        val speechText = if (voskModelStore.installedModelPath() == null) {
            "speech model missing"
        } else {
            "speech model ready"
        }
        val taught = runCatching { memory.summary().taughtExamples }.getOrDefault(0)
        return "$brainText • $speechText • $taught taught"
    }

    suspend fun isBrainReady(): Boolean = modelStore.classifier() != null
}
