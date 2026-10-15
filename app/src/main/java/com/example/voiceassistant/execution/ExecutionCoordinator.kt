package com.example.voiceassistant.execution

import com.example.voiceassistant.nlp.AppIntent
import com.example.voiceassistant.nlp.DurationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

sealed interface ExecutionEvent {
    data object OnExecutionStarted : ExecutionEvent
    data object OnExecutionCompleted : ExecutionEvent
    data object OnSpeechSynthesisCompleted : ExecutionEvent
    data class OnError(val exception: Exception) : ExecutionEvent
}

interface TtsEngine {
    fun speak(text: String)
    fun stop()
    fun shutdown()
}

interface DeviceActionExecutor {
    suspend fun execute(intent: AppIntent)
}

class ExecutionCoordinator(
    private val ttsEngine: TtsEngine,
    private val actionExecutor: DeviceActionExecutor,
    private val eventDispatcher: (ExecutionEvent) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pendingIntent: AppIntent? = null

    fun onIntentParsed(intent: AppIntent, needsConfirmation: Boolean) {
        if (needsConfirmation) {
            pendingIntent = intent
            ttsEngine.speak(generateConfirmationPrompt(intent))
        } else {
            executeIntent(intent)
        }
    }

    fun onConfirmationReceived() {
        val intentToExecute = pendingIntent
        pendingIntent = null
        if (intentToExecute != null) {
            executeIntent(intentToExecute)
        } else {
            eventDispatcher(ExecutionEvent.OnError(
                IllegalStateException("Confirmation received but no pending intent exists.")
            ))
        }
    }

    fun onConfirmationDenied() {
        pendingIntent = null
        ttsEngine.speak("Okay, cancelling.")
    }

    fun shutdown() {
        scope.cancel()
        ttsEngine.stop()
        ttsEngine.shutdown()
    }

    private fun executeIntent(intent: AppIntent) {
        scope.launch {
            eventDispatcher(ExecutionEvent.OnExecutionStarted)
            try {
                actionExecutor.execute(intent)
                val feedback = generateSuccessFeedback(intent)
                if (feedback.isNotEmpty()) ttsEngine.speak(feedback)
                eventDispatcher(ExecutionEvent.OnExecutionCompleted)
            } catch (e: Exception) {
                eventDispatcher(ExecutionEvent.OnError(e))
            }
        }
    }

    private fun generateConfirmationPrompt(intent: AppIntent): String = when (intent) {
        is AppIntent.SetAlarm -> if (intent.time != null) {
            "Do you want me to set the alarm for ${intent.time}?"
        } else {
            "What time would you like to set the alarm for?"
        }
        is AppIntent.DeleteFile ->
            "Are you sure you want to delete the file ${intent.fileName}? This cannot be undone."
        is AppIntent.ToggleFlashlight ->
            "Do you want to turn the flashlight ${if (intent.isOn) "on" else "off"}?"
        is AppIntent.SetTimer ->
            "Do you want me to start a timer for ${
                intent.durationSeconds?.let(DurationParser::describe) ?: "that duration"
            }?"
        AppIntent.StatusReport -> "Do you want a status report?"
        AppIntent.UnknownIntent -> "I didn't quite catch that. Could you repeat?"
    }

    private fun generateSuccessFeedback(intent: AppIntent): String = when (intent) {
        is AppIntent.SetAlarm -> "Alarm set."
        is AppIntent.DeleteFile -> "File deleted."
        is AppIntent.ToggleFlashlight -> "Done."
        is AppIntent.SetTimer -> "Timer started."
        AppIntent.StatusReport -> "Here is your status."
        AppIntent.UnknownIntent -> ""
    }
}
