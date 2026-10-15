package com.example.voiceassistant.nlp

import com.example.voiceassistant.aimatrix.OnDeviceBrain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two-stage intent pipeline, entirely on device:
 *
 *  1. [IntentRouter] — deterministic rules for phrases that must never be misread
 *     (flashlight on/off, alarms, timers, device status).
 *  2. [OnDeviceBrain] — taught phrases and the bundled ai-matrix network for anything
 *     the rules cannot resolve safely.
 *
 * Safety: anything the brain suggests is treated as low-confidence, so it always goes
 * through the confirmation flow, and parameter extraction (the alarm time, the timer
 * duration) still comes from the deterministic parsers rather than from the classifier.
 */
class NlpProcessor(
    private val normalizer: TextNormalizer = TextNormalizer(),
    private val router: IntentRouter = IntentRouter(),
    /** Exposed so callers (the UI's teach panel and tests) can teach it new phrases. */
    val brain: OnDeviceBrain? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    data class OnIntentParsed(
        val intent: AppIntent,
        val needsConfirmation: Boolean,
        val confidence: Float,
        /** Which stage produced the intent; useful for the UI and for logging. */
        val source: Source = Source.RULES
    )

    enum class Source { RULES, BRAIN, TAUGHT }

    private val confidenceThreshold = 0.75f

    suspend fun process(rawText: String): OnIntentParsed = withContext(dispatcher) {
        val cleanText = normalizer.normalize(rawText)
        val evaluation = router.route(cleanText)

        if (evaluation.intent !is AppIntent.UnknownIntent) {
            return@withContext finish(evaluation.intent, evaluation.confidence, Source.RULES)
        }

        val suggestion = brain?.suggest(rawText)
        if (suggestion != null) {
            val intent = toIntent(suggestion.label, rawText)
            if (intent != null) {
                return@withContext OnIntentParsed(
                    intent = intent,
                    // The brain is never trusted to act on its own: always confirm.
                    needsConfirmation = true,
                    confidence = suggestion.confidence,
                    source = if (suggestion.source == OnDeviceBrain.Source.TAUGHT) {
                        Source.TAUGHT
                    } else {
                        Source.BRAIN
                    }
                )
            }
        }

        finish(AppIntent.UnknownIntent, 0.0f, Source.RULES)
    }

    /** Turns a brain label into an intent, extracting parameters deterministically. */
    fun toIntent(label: String, rawText: String): AppIntent? = when (label.uppercase()) {
        "FLASHLIGHT_ON" -> AppIntent.ToggleFlashlight(isOn = true)
        "FLASHLIGHT_OFF" -> AppIntent.ToggleFlashlight(isOn = false)
        "ALARM_SET" -> AppIntent.SetAlarm(router.extractAlarmTime(normalizer.normalize(rawText)))
        "TIMER_SET" -> AppIntent.SetTimer(DurationParser.parseSeconds(rawText))
        "DELETE_FILE" -> AppIntent.DeleteFile(router.guessFileName(normalizer.normalize(rawText)))
        "DEVICE_STATUS" -> AppIntent.StatusReport
        else -> null
    }

    private fun finish(intent: AppIntent, confidence: Float, source: Source): OnIntentParsed {
        val missingParams = when (intent) {
            is AppIntent.SetAlarm -> intent.time == null
            is AppIntent.SetTimer -> intent.durationSeconds == null
            else -> false
        }
        val isUnknown = intent is AppIntent.UnknownIntent
        val needsConfirmation = !isUnknown && (
            intent.changesDeviceState ||
                confidence < confidenceThreshold ||
                missingParams
            )
        return OnIntentParsed(intent, needsConfirmation, confidence, source)
    }
}
