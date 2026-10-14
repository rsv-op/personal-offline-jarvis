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
    data object OnExecutionStarted : AssistantEvent
    data object OnExecutionCompleted : AssistantEvent
    data object OnSpeechSynthesisCompleted : AssistantEvent
    data object OnError : AssistantEvent
    data object OnTimeout : AssistantEvent
    data object OnCleanupComplete : AssistantEvent
}

class AssistantUiStateMachine {
    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    fun processEvent(event: AssistantEvent) {
        _uiState.update { current -> reduce(current, event) }
    }

    private fun reduce(current: AssistantUiState, event: AssistantEvent): AssistantUiState {
        if (event is AssistantEvent.OnTimeout || event is AssistantEvent.OnError) {
            return if (current == AssistantUiState.Idle || current == AssistantUiState.ReturnToIdle) {
                current
            } else {
                AssistantUiState.ReturnToIdle
            }
        }

        return when (current) {
            AssistantUiState.Idle -> when (event) {
                AssistantEvent.OnWakeWordDetected -> AssistantUiState.WakeDetected
                else -> invalid(current, event)
            }
            AssistantUiState.WakeDetected -> when (event) {
                AssistantEvent.OnSpeechStarted -> AssistantUiState.Listening
                else -> invalid(current, event)
            }
            AssistantUiState.Listening -> when (event) {
                AssistantEvent.OnSpeechEnded -> AssistantUiState.Processing
                else -> invalid(current, event)
            }
            AssistantUiState.Processing -> when (event) {
                is AssistantEvent.OnIntentParsed -> if (event.needsConfirmation) {
                    AssistantUiState.NeedsConfirmation
                } else {
                    AssistantUiState.Executing
                }
                else -> invalid(current, event)
            }
            AssistantUiState.NeedsConfirmation -> when (event) {
                AssistantEvent.OnConfirmationReceived -> AssistantUiState.Executing
                AssistantEvent.OnConfirmationDenied -> AssistantUiState.ReturnToIdle
                else -> invalid(current, event)
            }
            AssistantUiState.Executing -> when (event) {
                AssistantEvent.OnExecutionStarted -> AssistantUiState.Executing
                AssistantEvent.OnExecutionCompleted -> AssistantUiState.Speaking
                else -> invalid(current, event)
            }
            AssistantUiState.Speaking -> when (event) {
                AssistantEvent.OnSpeechSynthesisCompleted -> AssistantUiState.ReturnToIdle
                else -> invalid(current, event)
            }
            AssistantUiState.ReturnToIdle -> when (event) {
                AssistantEvent.OnCleanupComplete -> AssistantUiState.Idle
                else -> invalid(current, event)
            }
        }
    }

    private fun invalid(current: AssistantUiState, event: AssistantEvent): Nothing =
        throw IllegalStateException("Invalid transition from $current via $event")
}
