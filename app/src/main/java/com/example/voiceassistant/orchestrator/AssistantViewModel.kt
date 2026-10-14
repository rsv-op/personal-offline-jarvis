package com.example.voiceassistant.orchestrator

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voiceassistant.execution.AlarmExecutor
import com.example.voiceassistant.execution.FlashlightExecutor
import com.example.voiceassistant.execution.TtsController
import com.example.voiceassistant.execution.TtsEvent
import com.example.voiceassistant.nlp.AppIntent
import com.example.voiceassistant.nlp.NlpProcessor
import com.example.voiceassistant.statemachine.AssistantUiState
import com.example.voiceassistant.stt.AudioCaptureSource
import com.example.voiceassistant.stt.OfflineSttIntegrator
import com.example.voiceassistant.stt.SttEngine
import com.example.voiceassistant.stt.SttEvent
import com.example.voiceassistant.stt.VoskModelStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AssistantViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsController: TtsController,
    private val flashlightExecutor: FlashlightExecutor,
    private val alarmExecutor: AlarmExecutor,
    private val nlpProcessor: NlpProcessor,
    sttEngine: SttEngine,
    private val audioCaptureSource: AudioCaptureSource,
    private val modelStore: VoskModelStore
) : ViewModel() {
    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _modelStatus = MutableStateFlow(
        if (modelStore.installedModelPath() == null) {
            "Offline speech model not installed"
        } else {
            "Offline speech model ready"
        }
    )
    val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var pendingIntent: AppIntent? = null
    private var awaitingAlarmTime = false
    private var loadedModelPath: String? = null

    private val sttIntegrator = OfflineSttIntegrator(
        engine = sttEngine,
        audioStream = audioCaptureSource.frames,
        frameRecycler = { /* AudioCaptureSource emits an owned frame copy. */ },
        eventDispatcher = ::dispatchSttEvent
    )

    init {
        viewModelScope.launch {
            ttsController.events.collect { event ->
                if (event is TtsEvent.OnDone && _uiState.value is AssistantUiState.Speaking) {
                    _uiState.value = AssistantUiState.Idle
                }
            }
        }
        viewModelScope.launch {
            audioCaptureSource.errors.collect { error ->
                _statusMessage.value = error.message ?: "Microphone capture failed"
                audioCaptureSource.stop()
                sttIntegrator.stopTranscription(emitFinalResult = false)
                _uiState.value = AssistantUiState.Idle
            }
        }
    }

    fun onTranscriptChanged(text: String) {
        _partialTranscript.value = text
    }

    fun importOfflineModel(treeUri: Uri) {
        viewModelScope.launch {
            _modelStatus.value = "Importing offline speech model…"
            _statusMessage.value = ""
            try {
                modelStore.importModelTree(treeUri)
                loadedModelPath = null
                _modelStatus.value = "Offline speech model ready"
                _statusMessage.value = "Model installed. You can now speak offline."
            } catch (error: Exception) {
                _modelStatus.value = "Offline speech model import failed"
                _statusMessage.value = error.message ?: "Could not import the selected model"
            }
        }
    }

    fun toggleListening() {
        if (_uiState.value is AssistantUiState.Listening) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun startListening() {
        if (_uiState.value !is AssistantUiState.Idle) return
        val modelPath = modelStore.installedModelPath()
        if (modelPath == null) {
            _statusMessage.value = "Import an offline Vosk model folder before listening."
            _modelStatus.value = "Offline speech model not installed"
            return
        }

        _uiState.value = AssistantUiState.Processing
        viewModelScope.launch {
            _statusMessage.value = "Loading offline speech model…"
            _partialTranscript.value = ""
            try {
                if (loadedModelPath != modelPath) {
                    if (!sttIntegrator.initializeModel(modelPath)) {
                        throw IllegalStateException("Could not initialize the offline speech model")
                    }
                    loadedModelPath = modelPath
                }
                sttIntegrator.startTranscription()
                audioCaptureSource.start()
                _statusMessage.value = "Listening offline"
                _uiState.value = AssistantUiState.Listening
            } catch (error: Exception) {
                audioCaptureSource.stop()
                sttIntegrator.stopTranscription(emitFinalResult = false)
                _uiState.value = AssistantUiState.Idle
                _statusMessage.value = error.message ?: "Could not start listening"
            }
        }
    }

    fun stopListening() {
        if (_uiState.value !is AssistantUiState.Listening) return
        _uiState.value = AssistantUiState.Processing
        _statusMessage.value = "Finishing transcription…"
        viewModelScope.launch {
            audioCaptureSource.stop()
            sttIntegrator.stopTranscription()
        }
    }

    fun onIntentParsed(parsed: NlpProcessor.OnIntentParsed) {
        if (parsed.intent is AppIntent.SetAlarm && parsed.intent.time == null) {
            awaitingAlarmTime = true
            pendingIntent = null
            viewModelScope.launch {
                speakAndReturnToIdle("What time would you like to set the alarm for?")
                if (awaitingAlarmTime) startListening()
            }
        } else if (parsed.intent is AppIntent.UnknownIntent) {
            viewModelScope.launch { speakAndReturnToIdle("I didn't catch that. Please try again.") }
        } else if (!parsed.needsConfirmation) {
            executeIntent(parsed.intent)
        } else {
            pendingIntent = parsed.intent
            viewModelScope.launch {
                _uiState.value = AssistantUiState.Speaking
                ttsController.speak(confirmationPrompt(parsed.intent))
                _uiState.value = AssistantUiState.NeedsConfirmation
                _statusMessage.value = "Confirm or cancel the requested action"
            }
        }
    }

    fun confirmAction() {
        awaitingAlarmTime = false
        pendingIntent?.let(::executeIntent)
        pendingIntent = null
    }

    fun denyAction() {
        awaitingAlarmTime = false
        pendingIntent = null
        viewModelScope.launch { speakAndReturnToIdle("Okay, cancelled.") }
    }

    private fun dispatchSttEvent(event: SttEvent) {
        when (event) {
            is SttEvent.OnPartialResult -> viewModelScope.launch {
                _partialTranscript.value = event.text
                _uiState.value = AssistantUiState.Listening
            }
            is SttEvent.OnFinalResult -> viewModelScope.launch {
                audioCaptureSource.stop()
                sttIntegrator.stopTranscription(emitFinalResult = false)
                _partialTranscript.value = event.text
                _uiState.value = AssistantUiState.Processing
                _statusMessage.value = "Understanding your request…"
                try {
                    val requestText = if (awaitingAlarmTime) {
                        awaitingAlarmTime = false
                        "set alarm for ${event.text}"
                    } else {
                        event.text
                    }
                    onIntentParsed(nlpProcessor.process(requestText))
                } catch (error: Exception) {
                    _statusMessage.value = error.message ?: "Could not understand the request"
                    _uiState.value = AssistantUiState.Idle
                }
            }
            SttEvent.OnEmptyAcoustic -> viewModelScope.launch {
                if (_uiState.value is AssistantUiState.Listening ||
                    _uiState.value is AssistantUiState.Processing
                ) {
                    _uiState.value = AssistantUiState.Idle
                    _statusMessage.value = "No speech detected"
                }
            }
            is SttEvent.OnError -> viewModelScope.launch {
                audioCaptureSource.stop()
                sttIntegrator.stopTranscription(emitFinalResult = false)
                _uiState.value = AssistantUiState.Idle
                _statusMessage.value = event.exception.message ?: "Offline speech recognition failed"
            }
        }
    }

    private fun executeIntent(intent: AppIntent) {
        _uiState.value = AssistantUiState.Executing
        _statusMessage.value = "Executing action…"
        viewModelScope.launch {
            val response = try {
                when (intent) {
                    is AppIntent.ToggleFlashlight -> {
                        flashlightExecutor.execute(context, intent)
                        "Done."
                    }
                    is AppIntent.SetAlarm -> {
                        alarmExecutor.execute(context, intent)
                        "Alarm set."
                    }
                    is AppIntent.DeleteFile ->
                        "File deletion is not available in this build."
                    AppIntent.UnknownIntent -> "I didn't catch that."
                }
            } catch (error: Exception) {
                _statusMessage.value = error.message ?: "The action could not be completed"
                "I couldn't complete that action."
            }
            speakAndReturnToIdle(response)
        }
    }

    private suspend fun speakAndReturnToIdle(text: String) {
        _uiState.value = AssistantUiState.Speaking
        _statusMessage.value = text
        val utteranceId = UUID.randomUUID().toString()
        val completion = viewModelScope.async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(TTS_COMPLETION_TIMEOUT_MS) {
                ttsController.events.first { event ->
                    (event is TtsEvent.OnDone && event.utteranceId == utteranceId) ||
                        event is TtsEvent.OnError
                }
            }
        }
        ttsController.speak(text, utteranceId)
        completion.await()
        _uiState.value = AssistantUiState.Idle
    }

    private fun confirmationPrompt(intent: AppIntent): String = when (intent) {
        is AppIntent.SetAlarm -> if (intent.time == null) {
            "What time would you like to set the alarm for?"
        } else {
            "Do you want me to set the alarm for ${intent.time}?"
        }
        is AppIntent.DeleteFile -> "Are you sure you want to delete ${intent.fileName}?"
        is AppIntent.ToggleFlashlight ->
            "Do you want to turn the flashlight ${if (intent.isOn) "on" else "off"}?"
        AppIntent.UnknownIntent -> "I didn't catch that."
    }

    private companion object {
        const val TTS_COMPLETION_TIMEOUT_MS = 8_000L
    }

    override fun onCleared() {
        sttIntegrator.releaseModel()
        audioCaptureSource.close()
        super.onCleared()
    }
}
