package com.example.voiceassistant.statemachine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface AssistantUiState {
    data object Idle : AssistantUiState
    data object WakeDetected : AssistantUiState
    data object Listening : AssistantUiState
    data object Processing : AssistantUiState
    data object NeedsConfirmation : AssistantUiState
    data object Executing : AssistantUiState
    data object Speaking : AssistantUiState
    data object ReturnToIdle : AssistantUiState
}

sealed interface AssistantEvent {
    data object OnWakeWordDetected : AssistantEvent
    data object OnSpeechStarted : AssistantEvent
    data object OnSpeechEnded : AssistantEvent
    data class OnIntentParsed(val needsConfirmation: Boolean) : AssistantEvent
    data object OnConfirmationReceived : AssistantEvent
    data object OnConfirmationDenied : AssistantEvent

    /** Emitted before any spoken prompt, including errors and brain replies. */
    data object OnStartSpeaking : AssistantEvent

    /** Same as [OnStartSpeaking] but also valid when the assistant is already winding down. */
    data object OnStartSpeakingFromRest : AssistantEvent

    data object OnExecutionStarted : AssistantEvent
    data object OnExecutionCompleted : AssistantEvent
    data object OnSpeechSynthesisCompleted : AssistantEvent
    data object OnError : AssistantEvent
    data object OnTimeout : AssistantEvent
    data object OnCleanupComplete : AssistantEvent
}

/**
 * Single source of truth for the assistant's screen state.
 *
 * Two deliberate properties:
 *
 *  * **Lenient.** Speech, synthesis and device callbacks race (a TTS engine can finish
 *    while the microphone is still shutting down), so an event that does not apply to
 *    the current state is reported through [onInvalidTransition] instead of throwing.
 *    A dropped event can never take the assistant down mid-conversation.
 *  * **Explicit.** Every path back to `Idle` goes through `ReturnToIdle` and
 *    `OnCleanupComplete`, so microphone/model cleanup always has a place to live.
 */
class AssistantUiStateMachine(
    private val onInvalidTransition: (AssistantUiState, AssistantEvent) -> Unit = { _, _ -> }
) {
    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    fun processEvent(event: AssistantEvent) {
        _uiState.update { current ->
            val next = reduce(current, event)
            if (next == null) {
                onInvalidTransition(current, event)
                current
            } else {
                next
            }
        }
    }

    /** Forces the machine back to [AssistantUiState.Idle] (used when listening is cancelled). */
    fun reset() {
        _uiState.value = AssistantUiState.Idle
    }

    private fun reduce(current: AssistantUiState, event: AssistantEvent): AssistantUiState? {
        if (event is AssistantEvent.OnError || event is AssistantEvent.OnTimeout) {
            return when (current) {
                AssistantUiState.Idle, AssistantUiState.ReturnToIdle -> current
                else -> AssistantUiState.ReturnToIdle
            }
        }

        return when (event) {
            AssistantEvent.OnWakeWordDetected -> when (current) {
                AssistantUiState.Idle,
                AssistantUiState.ReturnToIdle,
                // You can answer a pending confirmation out loud: tapping the orb while
                // the question is on screen starts a short listen for "yes" / "cancel".
                AssistantUiState.NeedsConfirmation -> AssistantUiState.WakeDetected
                else -> null
            }
            AssistantEvent.OnSpeechStarted -> when (current) {
                AssistantUiState.WakeDetected,
                AssistantUiState.Idle,
                AssistantUiState.ReturnToIdle,
                AssistantUiState.Listening -> AssistantUiState.Listening
                else -> null
            }
            AssistantEvent.OnSpeechEnded -> when (current) {
                AssistantUiState.Listening, AssistantUiState.WakeDetected -> AssistantUiState.Processing
                else -> null
            }
            is AssistantEvent.OnIntentParsed -> when (current) {
                // Idle/WakeDetected are accepted too: an intent can be produced without a
                // listen phase (a restored request, a test, a future wake word), and the
                // confirmation gate must still be raised rather than dropped.
                AssistantUiState.Processing,
                AssistantUiState.ReturnToIdle,
                AssistantUiState.Idle,
                AssistantUiState.WakeDetected ->
                    if (event.needsConfirmation) {
                        AssistantUiState.NeedsConfirmation
                    } else {
                        AssistantUiState.Executing
                    }
                else -> null
            }
            AssistantEvent.OnConfirmationReceived -> when (current) {
                // Processing is included for the spoken "yes": the transcript arrives
                // after listening has already moved on to Processing.
                AssistantUiState.NeedsConfirmation, AssistantUiState.Processing ->
                    AssistantUiState.Executing
                else -> null
            }
            AssistantEvent.OnConfirmationDenied -> when (current) {
                AssistantUiState.NeedsConfirmation, AssistantUiState.Processing ->
                    AssistantUiState.ReturnToIdle
                else -> null
            }
            AssistantEvent.OnStartSpeaking -> when (current) {
                AssistantUiState.Idle,
                AssistantUiState.Listening,
                AssistantUiState.WakeDetected,
                AssistantUiState.Processing,
                AssistantUiState.NeedsConfirmation,
                AssistantUiState.Executing -> AssistantUiState.Speaking
                else -> null
            }
            AssistantEvent.OnExecutionStarted -> when (current) {
                AssistantUiState.Executing -> AssistantUiState.Executing
                else -> null
            }
            AssistantEvent.OnExecutionCompleted -> when (current) {
                AssistantUiState.Executing -> AssistantUiState.Speaking
                else -> null
            }
            AssistantEvent.OnStartSpeakingFromRest -> when (current) {
                AssistantUiState.ReturnToIdle -> AssistantUiState.Speaking
                else -> null
            }
            AssistantEvent.OnSpeechSynthesisCompleted -> when (current) {
                AssistantUiState.Speaking -> AssistantUiState.ReturnToIdle
                else -> null
            }
            AssistantEvent.OnCleanupComplete -> when (current) {
                AssistantUiState.ReturnToIdle -> AssistantUiState.Idle
                else -> null
            }
            AssistantEvent.OnError, AssistantEvent.OnTimeout,
            AssistantEvent.OnStartSpeakingFromRest -> null
        }
    }
}
