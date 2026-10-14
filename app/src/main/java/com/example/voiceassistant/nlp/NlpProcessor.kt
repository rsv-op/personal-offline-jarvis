package com.example.voiceassistant.nlp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NlpProcessor(
    private val normalizer: TextNormalizer = TextNormalizer(),
    private val router: IntentRouter = IntentRouter(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    data class OnIntentParsed(
        val intent: AppIntent,
        val needsConfirmation: Boolean,
        val confidence: Float
    )

    private val confidenceThreshold = 0.75f

    suspend fun process(rawText: String): OnIntentParsed = withContext(dispatcher) {
        val cleanText = normalizer.normalize(rawText)
        val evaluation = router.route(cleanText)
        val missingParams = when (val intent = evaluation.intent) {
            is AppIntent.SetAlarm -> intent.time == null
            else -> false
        }
        val isUnknown = evaluation.intent is AppIntent.UnknownIntent
        val needsConfirmation = !isUnknown && (
            evaluation.confidence < confidenceThreshold ||
                evaluation.sensitivity == IntentSensitivity.HIGH ||
                missingParams
            )
        OnIntentParsed(evaluation.intent, needsConfirmation, evaluation.confidence)
    }
}
