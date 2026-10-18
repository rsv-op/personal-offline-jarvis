package com.example.voiceassistant.orchestrator

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voiceassistant.aimatrix.OnDeviceBrain
import com.example.voiceassistant.execution.AlarmExecutor
import com.example.voiceassistant.execution.AssistantStatusProvider
import com.example.voiceassistant.execution.FlashlightExecutor
import com.example.voiceassistant.execution.StatusExecutor
import com.example.voiceassistant.execution.TimerExecutor
import com.example.voiceassistant.execution.TtsController
import com.example.voiceassistant.execution.TtsEvent
import com.example.voiceassistant.generation.LocalGenerationModelStore
import com.example.voiceassistant.generation.LocalTextGenerator
import com.example.voiceassistant.nlp.AppIntent
import com.example.voiceassistant.nlp.NlpProcessor
import com.example.voiceassistant.nlp.TextNormalizer
import com.example.voiceassistant.statemachine.AssistantEvent
import com.example.voiceassistant.statemachine.AssistantUiState
import com.example.voiceassistant.statemachine.AssistantUiStateMachine
import com.example.voiceassistant.stt.AudioCaptureSource
import com.example.voiceassistant.stt.AudioDiagnostics
import com.example.voiceassistant.memory.DeviceMemory
import com.example.voiceassistant.stt.OfflineSttIntegrator
import com.example.voiceassistant.stt.SttEngine
import com.example.voiceassistant.stt.SttEvent
import com.example.voiceassistant.stt.VoskModelStore
import com.example.voiceassistant.vault.JarvisMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * The assistant's brain stem: owns the voice loop, the confirmation gate and every
 * piece of state the screen shows.
 *
 * Voice loop:
 * ```
 *   tap orb -> load speech model -> listen -> transcribe (Vosk)
 *     -> route intent (rules, then the ai-matrix brain)
 *     -> device action? ask "do you want me to ...?" and wait for yes/cancel
 *     -> execute -> speak the result -> log it to the encrypted vault -> idle
 * ```
 *
 * The confirmation gate is deliberately not optional: [AppIntent.changesDeviceState]
 * intents are never executed without either a tap on Confirm or a spoken "yes".
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsController: TtsController,
    private val flashlightExecutor: FlashlightExecutor,
    private val alarmExecutor: AlarmExecutor,
    private val timerExecutor: TimerExecutor,
    private val statusExecutor: StatusExecutor,
    val assistantStatusProvider: AssistantStatusProvider,
    private val nlpProcessor: NlpProcessor,
    sttEngine: SttEngine,
    private val audioCaptureSource: AudioCaptureSource,
    private val modelStore: VoskModelStore,
    private val localTextGenerator: LocalTextGenerator,
    private val localGenerationModelStore: LocalGenerationModelStore,
    private val brain: OnDeviceBrain,
    private val memory: JarvisMemory,
    val memoryBudget: com.example.voiceassistant.memory.DeviceMemory,
    private val systemMetricsCollector: com.example.voiceassistant.telematics.SystemMetricsCollector,
    /** Injected (never defaulted) so Dagger sees a single, fully-specified constructor. */
    @SpeechDispatcher private val speechDispatcher: kotlinx.coroutines.CoroutineDispatcher
) : ViewModel() {

    private val machine = AssistantUiStateMachine()

    /** Screen state, owned by [AssistantUiStateMachine]. */
    val uiState: StateFlow<AssistantUiState> = machine.uiState

    private val normalizer = TextNormalizer()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _lastUtterance = MutableStateFlow("")
    val lastUtterance: StateFlow<String> = _lastUtterance.asStateFlow()

    private val _modelStatus = MutableStateFlow("")
    val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()

    private val _modelSizeMb = MutableStateFlow<Double?>(null)
    val modelSizeMb: StateFlow<Double?> = _modelSizeMb.asStateFlow()

    private val _replyModelStatus = MutableStateFlow("")
    val replyModelStatus: StateFlow<String> = _replyModelStatus.asStateFlow()

    private val _brainStatus = MutableStateFlow("")
    val brainStatus: StateFlow<String> = _brainStatus.asStateFlow()

    private val _vaultStatus = MutableStateFlow("")
    val vaultStatus: StateFlow<String> = _vaultStatus.asStateFlow()

    private val _taughtPhrases = MutableStateFlow<List<JarvisMemory.TaughtExample>>(emptyList())
    val taughtPhrases: StateFlow<List<JarvisMemory.TaughtExample>> = _taughtPhrases.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _confirmationPrompt = MutableStateFlow("")
    val confirmationPrompt: StateFlow<String> = _confirmationPrompt.asStateFlow()

    private val _intentSource = MutableStateFlow<NlpProcessor.Source?>(null)
    val intentSource: StateFlow<NlpProcessor.Source?> = _intentSource.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /** True while the user has the in-app diagnose panel open. UI-only. */
    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()

    /**
     * Live telematics stream. The collector reads device memory, CPU, battery, thermal
     * status, and process info on every tick of the 1 Hz ticker; the screen consumes
     * the resulting flow. A small ring buffer of recent CPU samples is kept alongside
     * the live snapshot for the sparkline.
     *
     * The ticker is **lazy**: it only starts running while at least one consumer is
     * actively subscribed (see [com.example.voiceassistant.telematics.TelematicsTicker]).
     * When the diagnose screen is not open, no 1 Hz coroutine is alive, which keeps
     * the unit-test dispatcher from being held by an infinite loop and lets the
     * Robolectric Compose tests in the same JVM fork render without OOMing.
     */
    private val telematicsTicker: com.example.voiceassistant.telematics.TelematicsTicker =
        com.example.voiceassistant.telematics.TelematicsTicker(
            collector = systemMetricsCollector,
            scope = viewModelScope
        )

    /** Live snapshot of the device's memory, CPU, storage, battery, thermal, and process info. */
    val telematics: StateFlow<com.example.voiceassistant.telematics.TelematicsSnapshot> =
        telematicsTicker.flow

    /** Last 60 CPU samples (oldest first) for the sparkline. Refreshed by the ticker. */
    val cpuHistory: StateFlow<FloatArray> = telematicsTicker.cpuHistory

    /** Most recent partial transcript, kept around while diagnosing so the panel can show it. */
    val diagnosticPartial: StateFlow<String> = partialTranscript

    /** Live audio level from the capture source, in dBFS (-60..0). */
    val diagnosticLevelDb: StateFlow<Float> = audioCaptureSource.levelDb

    /** Live capture stats, surfaced to the diagnose screen as a snapshot. */
    val diagnosticStats: AudioDiagnostics.CaptureStats
        get() = audioCaptureSource.stats

    /** The current Vosk model path, or null if no model is installed. */
    val diagnosticModelPath: String? get() = modelStore.installedModelPath()

    /** The size of the installed Vosk model in MB, or null if no model is installed. */
    val diagnosticModelSizeMb: Double? get() = modelStore.installedModelSizeMb()

    /** The current LiteRT-LM reply model path, or null if no model is installed. */
    val diagnosticReplyModelPath: String? get() = localGenerationModelStore.installedModelPath()

    /** The size of the installed reply model in MB, or null if no model is installed. */
    val diagnosticReplyModelSizeMb: Double? get() = localGenerationModelStore.installedModelSizeMb()

    private var pendingIntent: AppIntent? = null
    private var awaitingAlarmTime = false
    private var awaitingTimerDuration = false
    private var awaitingConfirmationAnswer = false
    private var loadedModelPath: String? = null

    private val sttIntegrator = OfflineSttIntegrator(
        engine = sttEngine,
        audioStream = audioCaptureSource.frames,
        frameRecycler = { /* AudioCaptureSource emits an owned frame copy. */ },
        eventDispatcher = ::dispatchSttEvent,
        dispatcher = speechDispatcher
    )

    init {
        viewModelScope.launch { refreshStatuses() }
        viewModelScope.launch {
            audioCaptureSource.errors.collect { error ->
                _statusMessage.value = error.message ?: "Microphone capture failed"
                fullReset()
            }
        }
    }

    // --------------------------------------------------------------- status

    private suspend fun refreshStatuses() {
        _brainStatus.value = runCatching { assistantStatusProvider.shortStatus() }
            .getOrDefault("intent brain unavailable")
        _modelStatus.value = if (modelStore.installedModelPath() == null) {
            "Offline speech model not installed"
        } else {
            "Offline speech model ready"
        }
        _modelSizeMb.value = modelStore.installedModelSizeMb()
        _replyModelStatus.value = if (localGenerationModelStore.installedModelPath() == null) {
            "Local reply model not installed (open-ended answers use the built-in replies)"
        } else {
            "Local reply model ready"
        }
        _vaultStatus.value = runCatching {
            val summary = memory.summary()
            "${summary.taughtExamples} taught phrases • vault on ${summary.backend}"
        }.getOrDefault("Encrypted vault unavailable")
        _taughtPhrases.value = runCatching { memory.examples() }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------- model import

    /** Imports an extracted Vosk model folder. */
    fun importOfflineModel(treeUri: Uri) = importSpeechModel { modelStore.importModelTree(treeUri) }

    /** Imports a Vosk model downloaded as a `.zip` — the phone-friendly path. */
    fun importOfflineModelArchive(archiveUri: Uri) =
        importSpeechModel { modelStore.importModelArchive(archiveUri) }

    private fun importSpeechModel(importBlock: suspend () -> String) {
        if (_isImporting.value) return
        _isImporting.value = true
        viewModelScope.launch {
            _statusMessage.value = "Importing the speech model… this can take a minute"
            try {
                importBlock()
                loadedModelPath = null // force a reload of the newly installed model
                _statusMessage.value = "Speech model installed. Everything stays on this device."
            } catch (error: Exception) {
                _statusMessage.value = error.message ?: "Could not import the selected model"
            } finally {
                _isImporting.value = false
                refreshStatuses()
            }
        }
    }

    fun importReplyModel(uri: Uri) {
        if (_isImporting.value) return
        _isImporting.value = true
        viewModelScope.launch {
            _statusMessage.value = "Importing the reply model…"
            try {
                localGenerationModelStore.importModel(uri)
                localTextGenerator.onModelUpdated()
                _statusMessage.value = "Reply model installed for open-ended questions."
            } catch (error: Exception) {
                _statusMessage.value = error.message ?: "Could not import the selected reply model"
            } finally {
                _isImporting.value = false
                refreshStatuses()
            }
        }
    }

    // ------------------------------------------------------------ listening

    fun onTranscriptChanged(text: String) {
        _partialTranscript.value = text
    }

    fun toggleListening() {
        if (uiState.value is AssistantUiState.Listening) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun startListening() {
        val current = uiState.value
        if (current is AssistantUiState.Listening) {
            stopListening()
            return
        }
        if (current !is AssistantUiState.Idle &&
            current !is AssistantUiState.ReturnToIdle &&
            current !is AssistantUiState.NeedsConfirmation
        ) {
            return
        }

        val modelPath = modelStore.installedModelPath()
        if (modelPath == null) {
            _statusMessage.value = "Import an offline Vosk model (folder or .zip) before listening."
            _modelStatus.value = "Offline speech model not installed"
            return
        }

        val answeringConfirmation = current is AssistantUiState.NeedsConfirmation
        machine.processEvent(AssistantEvent.OnWakeWordDetected)

        viewModelScope.launch {
            _statusMessage.value = "Loading the offline speech model…"
            _partialTranscript.value = ""
            try {
                if (loadedModelPath != modelPath) {
                    if (!sttIntegrator.initializeModel(modelPath)) {
                        // Surface the engine's own reason (missing files, unreadable model,
                        // wrong format) rather than a generic failure.
                        throw IllegalStateException(
                            sttIntegrator.lastInitializationError
                                ?: "Could not initialize the offline speech model"
                        )
                    }
                    loadedModelPath = modelPath
                }
                awaitingConfirmationAnswer = answeringConfirmation
                sttIntegrator.startTranscription()
                audioCaptureSource.start()
                machine.processEvent(AssistantEvent.OnSpeechStarted)
                _statusMessage.value = if (answeringConfirmation) {
                    "Listening for your yes or cancel"
                } else {
                    "Listening offline — tap the orb to stop"
                }
            } catch (error: Exception) {
                awaitingConfirmationAnswer = false
                _statusMessage.value = error.message ?: "Could not start listening"
                fullReset(keepMessage = true)
            }
        }
    }

    fun stopListening() {
        if (uiState.value !is AssistantUiState.Listening) return
        machine.processEvent(AssistantEvent.OnSpeechEnded)
        _statusMessage.value = "Finishing transcription…"
        viewModelScope.launch {
            audioCaptureSource.stop()
            sttIntegrator.stopTranscription()
        }
    }

    // ------------------------------------------------------------ diagnostics

    /**
     * Long-pressing the orb from idle opens the in-app diagnose panel. The assistant
     * state machine is not engaged: the orb does not start listening, the partial
     * transcript is not reset, and the user can back out without side effects. The
     * point of this panel is to let the user answer "why is my STT bad" by reading
     * what the app actually heard, not by guessing from a transcript.
     */
    fun openDiagnostics() {
        if (_isDiagnosing.value) return
        _isDiagnosing.value = true
    }

    fun closeDiagnostics() {
        _isDiagnosing.value = false
    }

    /**
     * Writes the last 10 seconds of captured audio to `Download/jarvis_diagnose_<ts>.wav`.
     * Returns the file path on success, or null if there is nothing in the buffer or
     * the write failed. The file is a well-formed 16-bit mono PCM WAV at 16 kHz;
     * `MediaPlayer`, `ffplay`, and macOS QuickTime play it back without complaint.
     *
     * The diagnose screen uses this as the "Save what the mic actually heard" button:
     * if the transcript is wrong and the saved file is clear, the model is the
     * problem; if the saved file is muffled or clipped, the mic is.
     */
    fun saveLastTenSeconds(): String? {
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = java.io.File(downloads, "jarvis_diagnose_$timestamp.wav")
        return if (audioCaptureSource.saveRollingBuffer(file)) file.absolutePath else null
    }

    /**
     * Builds a plain-text diagnostic snapshot suitable for pasting into a chat. The
     * diagnose screen puts this on the clipboard; it is the user's "build report" so
     * I can read the actual numbers from the device instead of guessing.
     */
    fun diagnosticSnapshot(): String {
        val sb = StringBuilder()
        val totalMb = memoryBudget.totalMemoryBytes / (1024 * 1024)
        val availableMb = memoryBudget.availableMemoryBytes / (1024 * 1024)
        val modelPath = modelStore.installedModelPath()
        val modelSizeMb = modelStore.installedModelSizeMb()
        val replyModelPath = localGenerationModelStore.installedModelPath()
        val stats = audioCaptureSource.stats
        // Read the installed package's version so the pasted diagnosis
        // unambiguously identifies the build on the device. The
        // versionCode is set to the GitHub Actions run number, so a
        // value >= 121 means the AudioRecord buffer fix from PR #22
        // is present; < 121 means the v119-era buffer is still in use
        // and the user's "Average read latency" of ~99 ms/frame is
        // expected.
        val buildLabel = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "v${info.versionName} (code $code)"
        }.getOrDefault("v? (?)")
        sb.appendLine("JARVIS diagnosis — $buildLabel")
        sb.appendLine("-----------------")
        sb.appendLine("Device: total RAM ${totalMb} MB, available ${availableMb} MB")
        sb.appendLine("Vosk model: ${modelPath ?: "(not installed)"}")
        if (modelSizeMb != null) sb.appendLine("Vosk model size: %.1f MB".format(modelSizeMb))
        sb.appendLine("Reply model: ${replyModelPath ?: "(not installed)"}")
        replyModelPath?.let {
            val f = java.io.File(it)
            if (f.isFile) sb.appendLine("Reply model size: %.1f MB".format(f.length() / (1024.0 * 1024.0)))
        }
        sb.appendLine("Mic level (right now): ${audioCaptureSource.levelDb.value} dBFS")
        sb.appendLine("Capture: delivered=${stats.framesDelivered} zeroByteReads=${stats.framesReadZero} readErrors=${stats.readErrors}")
        sb.appendLine("Average read latency: ${stats.averageReadLatencyMicros} µs/frame")
        sb.appendLine("Sample rate: ${com.example.voiceassistant.stt.AudioCaptureSource.SAMPLE_RATE_HZ} Hz")
        val partial = partialTranscript.value
        if (partial.isNotBlank()) sb.appendLine("Last partial transcript: $partial")
        return sb.toString()
    }

    // ------------------------------------------------------------- the brain

    private fun dispatchSttEvent(event: SttEvent) {
        when (event) {
            is SttEvent.OnPartialResult -> viewModelScope.launch {
                _partialTranscript.value = event.text
            }

            is SttEvent.OnFinalResult -> viewModelScope.launch {
                audioCaptureSource.stop()
                sttIntegrator.stopTranscription(emitFinalResult = false)
                if (uiState.value is AssistantUiState.Listening) {
                    machine.processEvent(AssistantEvent.OnSpeechEnded)
                }
                _partialTranscript.value = event.text
                _lastUtterance.value = event.text

                if (awaitingConfirmationAnswer) {
                    awaitingConfirmationAnswer = false
                    handleConfirmationAnswer(event.text)
                    return@launch
                }

                val requestText = when {
                    awaitingAlarmTime -> {
                        awaitingAlarmTime = false
                        "set alarm for ${event.text}"
                    }
                    awaitingTimerDuration -> {
                        awaitingTimerDuration = false
                        "set a timer for ${event.text}"
                    }
                    else -> event.text
                }
                processUtterance(requestText, event.text)
            }

            SttEvent.OnEmptyAcoustic -> viewModelScope.launch {
                val current = uiState.value
                if (current is AssistantUiState.Listening || current is AssistantUiState.Processing) {
                    _statusMessage.value = "No speech detected"
                    fullReset(keepMessage = true)
                }
            }

            is SttEvent.OnError -> viewModelScope.launch {
                _statusMessage.value = event.exception.message ?: "Offline speech recognition failed"
                fullReset(keepMessage = true)
            }
        }
    }

    private suspend fun processUtterance(requestText: String, spokenText: String) {
        _statusMessage.value = "Understanding your request…"
        try {
            val parsed = nlpProcessor.process(requestText)
            onIntentParsed(parsed, spokenText)
        } catch (error: Exception) {
            _statusMessage.value = error.message ?: "Could not understand the request"
            fullReset(keepMessage = true)
        }
    }

    /** Spoken "yes"/"cancel" while a confirmation is pending. */
    private suspend fun handleConfirmationAnswer(spokenText: String) {
        val answer = normalizer.normalize(spokenText)
        val words = answer.split(' ').toSet()
        val affirmative = words.any { it in AFFIRMATIVE }
        val negative = words.any { it in NEGATIVE }

        when {
            affirmative && !negative -> confirmAction()
            negative && !affirmative -> denyAction()
            else -> {
                _statusMessage.value = "Please say yes to confirm or cancel to stop."
                speakAndSettle(_confirmationPrompt.value.ifBlank { "Should I go ahead?" })
                if (pendingIntent != null) {
                    machine.processEvent(AssistantEvent.OnIntentParsed(needsConfirmation = true))
                }
            }
        }
    }

    fun onIntentParsed(parsed: NlpProcessor.OnIntentParsed, originalUtterance: String? = null) {
        viewModelScope.launch {
            _intentSource.value = parsed.source
            val utterance = originalUtterance.orEmpty()

            when (val intent = parsed.intent) {
                is AppIntent.SetAlarm -> if (intent.time == null) {
                    askForMissingDetail(
                        question = "What time would you like the alarm for?",
                        flag = { awaitingAlarmTime = it }
                    )
                } else {
                    resolveAction(intent, parsed.needsConfirmation, utterance)
                }

                is AppIntent.SetTimer -> if (intent.durationSeconds == null) {
                    askForMissingDetail(
                        question = "How long should the timer run?",
                        flag = { awaitingTimerDuration = it }
                    )
                } else {
                    resolveAction(intent, parsed.needsConfirmation, utterance)
                }

                is AppIntent.UnknownIntent -> handleOpenEndedRequest(utterance)

                // Deletion is deliberately unimplemented, so there is nothing to confirm:
                // the assistant explains the limitation and keeps zero file permissions.
                is AppIntent.DeleteFile -> {
                    machine.processEvent(AssistantEvent.OnIntentParsed(needsConfirmation = false))
                    speakAndSettle(
                        "File deletion is not available in this build, so nothing was deleted."
                    )
                    machine.processEvent(AssistantEvent.OnCleanupComplete)
                }

                else -> resolveAction(intent, parsed.needsConfirmation, utterance)
            }
        }
    }

    /**
     * The confirmation gate. Everything that can change the device goes through here:
     * either it is confirmed out loud / by tap first, or it never runs.
     */
    private suspend fun resolveAction(
        intent: AppIntent,
        needsConfirmation: Boolean,
        utterance: String
    ) {
        if (!needsConfirmation) {
            machine.processEvent(AssistantEvent.OnIntentParsed(needsConfirmation = false))
            executeIntent(intent, utterance)
            return
        }

        pendingIntent = intent
        val prompt = confirmationPrompt(intent)
        _confirmationPrompt.value = prompt
        speakAndSettle(prompt)
        machine.processEvent(AssistantEvent.OnIntentParsed(needsConfirmation = true))
        _statusMessage.value = "Say yes, or tap Confirm."
    }

    private suspend fun askForMissingDetail(question: String, flag: (Boolean) -> Unit) {
        pendingIntent = null
        flag(true)
        speakAndSettle(question)
        machine.processEvent(AssistantEvent.OnCleanupComplete)
        startListening()
    }

    fun confirmAction() {
        val intent = pendingIntent ?: return
        pendingIntent = null
        _confirmationPrompt.value = ""
        machine.processEvent(AssistantEvent.OnConfirmationReceived)
        viewModelScope.launch { executeIntent(intent, _lastUtterance.value) }
    }

    fun denyAction() {
        val hadPendingIntent = pendingIntent != null
        pendingIntent = null
        awaitingAlarmTime = false
        awaitingTimerDuration = false
        _confirmationPrompt.value = ""
        if (hadPendingIntent) {
            machine.processEvent(AssistantEvent.OnConfirmationDenied)
        }
        viewModelScope.launch {
            speakAndSettle("Okay, cancelled.")
            machine.processEvent(AssistantEvent.OnCleanupComplete)
        }
    }

    private suspend fun executeIntent(intent: AppIntent, utterance: String) {
        if (uiState.value !is AssistantUiState.Executing) {
            // Direct (non-confirmed) path, e.g. a read-only status request.
            machine.processEvent(AssistantEvent.OnIntentParsed(needsConfirmation = false))
        }
        machine.processEvent(AssistantEvent.OnExecutionStarted)
        _statusMessage.value = "Executing the action…"

        val response = try {
            val result = when (intent) {
                is AppIntent.ToggleFlashlight -> flashlightExecutor.execute(context, intent)
                is AppIntent.SetAlarm -> alarmExecutor.execute(context, intent)
                is AppIntent.SetTimer -> timerExecutor.execute(context, intent)
                // Object literals do not smart-cast the subject, so name the singleton.
                AppIntent.StatusReport -> statusExecutor.execute(context, AppIntent.StatusReport)
                is AppIntent.DeleteFile ->
                    "File deletion is not available in this build, so nothing was deleted."
                AppIntent.UnknownIntent -> "I didn't catch that."
            }
            logAction(intent, utterance, result)
            result
        } catch (error: Exception) {
            val failure = error.message ?: "The action could not be completed"
            _statusMessage.value = failure
            logAction(intent, utterance, "failed: $failure")
            "I couldn't complete that action: $failure"
        }

        speakAndSettle(response)
        machine.processEvent(AssistantEvent.OnCleanupComplete)
        refreshStatuses()
    }

    private suspend fun logAction(intent: AppIntent, utterance: String, outcome: String) {
        runCatching {
            memory.logAction(intent.javaClass.simpleName, utterance, outcome)
        }
    }

    private fun handleOpenEndedRequest(userText: String) {
        viewModelScope.launch {
            if (userText.isBlank()) {
                speakAndSettle("I didn't catch that. Please try again.")
                machine.processEvent(AssistantEvent.OnCleanupComplete)
                return@launch
            }
            if (localGenerationModelStore.installedModelPath() == null) {
                _statusMessage.value = "Replying with the built-in fallback (no reply model installed)…"
            } else {
                _statusMessage.value = "Thinking with the on-device model…"
            }
            val reply = try {
                localTextGenerator.generateReply(userText)
            } catch (error: Exception) {
                _replyModelStatus.value = "Local reply model could not be loaded"
                _statusMessage.value = error.message ?: "Local generation failed"
                "I couldn't load the local reply model. Please check the selected file."
            }
            // speakAndSettle always ends in ReturnToIdle, so the loop can never get stuck
            // in Speaking if the synthesis engine reports nothing.
            speakAndSettle(reply)
            machine.processEvent(AssistantEvent.OnCleanupComplete)
        }
    }

    // --------------------------------------------------------------- teaching

    /** Pairs the last spoken phrase with an action so the brain learns it. */
    fun teachLastUtterance(actionLabel: String) {
        val phrase = _lastUtterance.value.ifBlank { return }
        teach(phrase, actionLabel)
    }

    fun teach(phrase: String, actionLabel: String) {
        viewModelScope.launch {
            try {
                brain.teach(phrase, actionLabel)
                _statusMessage.value = "Learned: \"$phrase\" → $actionLabel"
                refreshStatuses()
            } catch (error: Exception) {
                _statusMessage.value = error.message ?: "Could not store that phrase"
            }
        }
    }

    fun forgetPhrase(phrase: String) {
        viewModelScope.launch {
            val removed = runCatching { memory.forget(phrase) }.getOrDefault(false)
            _statusMessage.value = if (removed) "Forgot \"$phrase\"" else "That phrase was not stored"
            refreshStatuses()
        }
    }

    fun forgetAllPhrases() {
        viewModelScope.launch {
            runCatching { memory.forgetAll() }
            _statusMessage.value = "Cleared every taught phrase"
            refreshStatuses()
        }
    }

    fun refreshMemory() {
        viewModelScope.launch { refreshStatuses() }
    }

    // ----------------------------------------------------------------- voice

    private suspend fun speakAndSettle(text: String) {
        machine.processEvent(AssistantEvent.OnStartSpeaking)
        if (uiState.value !is AssistantUiState.Speaking) {
            // The machine already wound down to ReturnToIdle; still show the utterance.
            machine.processEvent(AssistantEvent.OnStartSpeakingFromRest)
        }
        _statusMessage.value = text
        speakText(text)
        machine.processEvent(AssistantEvent.OnSpeechSynthesisCompleted)
    }

    private suspend fun speakText(text: String) {
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
    }

    private fun confirmationPrompt(intent: AppIntent): String = when (intent) {
        is AppIntent.SetAlarm -> "Do you want me to set the alarm for ${intent.time}?"
        is AppIntent.SetTimer -> "Do you want me to start a timer for ${
            intent.durationSeconds?.let(com.example.voiceassistant.nlp.DurationParser::describe) ?: ""
        }?"
        is AppIntent.DeleteFile -> "File deletion is not available in this build."
        is AppIntent.ToggleFlashlight ->
            "Do you want to turn the flashlight ${if (intent.isOn) "on" else "off"}?"
        AppIntent.StatusReport -> "Do you want a device status report?"
        AppIntent.UnknownIntent -> "I didn't catch that."
    }

    private fun fullReset(keepMessage: Boolean = false) {
        awaitingConfirmationAnswer = false
        pendingIntent = null
        machine.processEvent(AssistantEvent.OnError)
        viewModelScope.launch {
            runCatching { audioCaptureSource.stop() }
            sttIntegrator.stopTranscription(emitFinalResult = false)
            machine.processEvent(AssistantEvent.OnCleanupComplete)
        }
        if (!keepMessage) _statusMessage.value = ""
    }

    fun clearStatusMessage() {
        _statusMessage.value = ""
    }

    override fun onCleared() {
        sttIntegrator.releaseModel()
        audioCaptureSource.close()
        super.onCleared()
    }

    private companion object {
        const val TTS_COMPLETION_TIMEOUT_MS = 8_000L

        val AFFIRMATIVE = setOf("yes", "yeah", "yep", "confirm", "confirmed", "sure", "do", "ok", "okay")
        val NEGATIVE = setOf("no", "nope", "cancel", "stop", "dont", "never", "abort")
    }
}
