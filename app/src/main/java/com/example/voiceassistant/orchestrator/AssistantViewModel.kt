package com.example.voiceassistant.orchestrator

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voiceassistant.execution.AlarmExecutor
import com.example.voiceassistant.execution.FlashlightExecutor
import com.example.voiceassistant.execution.TtsController
import com.example.voiceassistant.execution.TtsEvent
import com.example.voiceassistant.nlp.AppIntent
import com.example.voiceassistant.nlp.NlpProcessor
import com.example.voiceassistant.statemachine.AssistantUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AssistantViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsController: TtsController,
    private val flashlightExecutor: FlashlightExecutor,
    private val alarmExecutor: AlarmExecutor
) : ViewModel() {
    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private var pendingIntent: AppIntent? = null

    init {
        viewModelScope.launch {
            ttsController.events.collect { event ->
                if (event is TtsEvent.OnDone && _uiState.value is AssistantUiState.Speaking) {
                    _uiState.value = AssistantUiState.ReturnToIdle
                    _uiState.value = AssistantUiState.Idle
                }
            }
        }
    }

    fun onTranscriptChanged(text: String) {
        _partialTranscript.value = text
    }

    fun onIntentParsed(parsed: NlpProcessor.OnIntentParsed) {
        if (!parsed.needsConfirmation) {
            executeIntent(parsed.intent)
        } else {
            pendingIntent = parsed.intent
            _uiState.value = AssistantUiState.Speaking
            viewModelScope.launch {
                ttsController.speak("Do you want me to proceed with this action?")
                _uiState.value = AssistantUiState.NeedsConfirmation
            }
        }
    }

    fun confirmAction() {
        pendingIntent?.let(::executeIntent)
        pendingIntent = null
    }

    fun denyAction() {
        pendingIntent = null
        _uiState.value = AssistantUiState.ReturnToIdle
        _uiState.value = AssistantUiState.Idle
    }

    private fun executeIntent(intent: AppIntent) {
        _uiState.value = AssistantUiState.Executing
        viewModelScope.launch {
            try {
                when (intent) {
                    is AppIntent.ToggleFlashlight -> flashlightExecutor.execute(context, intent)
                    is AppIntent.SetAlarm -> alarmExecutor.execute(context, intent)
                    is AppIntent.DeleteFile -> {
                        // Intentionally not executed: destructive file actions require a scoped design.
                    }
                    AppIntent.UnknownIntent -> Unit
                }
                _uiState.value = AssistantUiState.ReturnToIdle
                _uiState.value = AssistantUiState.Idle
            } catch (_: Exception) {
                _uiState.value = AssistantUiState.ReturnToIdle
                _uiState.value = AssistantUiState.Idle
            }
        }
    }

    override fun onCleared() {
        ttsController.shutdown()
        super.onCleared()
    }
}
