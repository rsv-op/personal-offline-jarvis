package com.example.voiceassistant.orchestrator

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voiceassistant.aimatrix.OnDeviceBrain
import com.example.voiceassistant.chat.ChatHistoryRepository
import com.example.voiceassistant.chat.ChatMessageEntity
import com.example.voiceassistant.execution.AlarmExecutor
import com.example.voiceassistant.execution.AssistantStatusProvider
import com.example.voiceassistant.execution.ContactsExecutor
import com.example.voiceassistant.execution.FlashlightExecutor
import com.example.voiceassistant.execution.StatusExecutor
import com.example.voiceassistant.execution.TimerExecutor
import com.example.voiceassistant.core.Capability
import com.example.voiceassistant.core.DecisionLog
import com.example.voiceassistant.execution.TtsController
import com.example.voiceassistant.execution.TtsEvent
import com.example.voiceassistant.generation.LocalTextGenerator
import com.example.voiceassistant.generation.RagPrompt
import com.example.voiceassistant.generation.RagPromptComposer
import com.example.voiceassistant.generation.llama.LlamaLocalTextGenerator
import com.example.voiceassistant.generation.llama.LlamaModelStore
import com.example.voiceassistant.generation.llama.ModelTierPicker
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
import com.example.voiceassistant.stt.SherpaOnnxModelStore
import com.example.voiceassistant.rag.RagChunker
import com.example.voiceassistant.rag.RagContext
import com.example.voiceassistant.rag.RagIngestor
import com.example.voiceassistant.rag.RagQuery
import com.example.voiceassistant.rag.RagSourceSummary
import com.example.voiceassistant.stt.SttEvent
import com.example.voiceassistant.wake.ContinuousWakeController
import com.example.voiceassistant.wake.WakeSessionGate
import com.example.voiceassistant.wake.WakeState
import com.example.voiceassistant.vault.JarvisMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
 *   tap orb -> load speech model -> listen -> transcribe (sherpa-onnx)
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
    private val contactsExecutor: ContactsExecutor,
    val assistantStatusProvider: AssistantStatusProvider,
    private val nlpProcessor: NlpProcessor,
    sttEngine: SttEngine,
    private val audioCaptureSource: AudioCaptureSource,
    private val modelStore: SherpaOnnxModelStore,
    private val localTextGenerator: LocalTextGenerator,
    private val llamaModelStore: LlamaModelStore,
    private val llamaGenerator: LlamaLocalTextGenerator,
    private val chatHistory: ChatHistoryRepository,
    private val llamaModelServiceStarter: LlamaModelServiceStarter,
    private val brain: OnDeviceBrain,
    private val memory: JarvisMemory,
    val memoryBudget: com.example.voiceassistant.memory.DeviceMemory,
    private val systemMetricsCollector: com.example.voiceassistant.telematics.SystemMetricsCollector,
    /** The SAF ingestion pipeline for the local knowledge base. */
    private val ragIngestor: RagIngestor,
    /** The hash-chained ADR log and the capability gate in front of every subsystem. */
    private val decisionLog: DecisionLog,
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

    // ------------------------------------------------------------ knowledge base

    private val _ragStatus = MutableStateFlow("No documents indexed")
    val ragStatus: StateFlow<String> = _ragStatus.asStateFlow()

    private val _ragSources = MutableStateFlow<List<RagSourceSummary>>(emptyList())
    val ragSources: StateFlow<List<RagSourceSummary>> = _ragSources.asStateFlow()

    private val _ragLastIngest = MutableStateFlow("")
    val ragLastIngest: StateFlow<String> = _ragLastIngest.asStateFlow()

    private val _ragBusy = MutableStateFlow(false)
    val ragBusy: StateFlow<Boolean> = _ragBusy.asStateFlow()

    /** When false the assistant answers from general knowledge only. */
    private val _ragEnabled = MutableStateFlow(true)
    val ragEnabled: StateFlow<Boolean> = _ragEnabled.asStateFlow()

    /** Optional exact-match target, e.g. `notes.md page 2`. */
    private val _ragTargetFilter = MutableStateFlow("")
    val ragTargetFilter: StateFlow<String> = _ragTargetFilter.asStateFlow()

    /** The sources quoted in the most recent answer, shown under it in the chat. */
    private val _ragLastSources = MutableStateFlow<List<String>>(emptyList())
    val ragLastSources: StateFlow<List<String>> = _ragLastSources.asStateFlow()

    // ------------------------------------------------------------ wake word

    /** Where the hands-free loop is: off, standby (waiting for "heretic"), armed, busy. */
    private val _wakeState = MutableStateFlow(WakeState.OFF)
    val wakeState: StateFlow<WakeState> = _wakeState.asStateFlow()

    /** True while the microphone is held open by the hands-free loop. */
    private val _handsFreeActive = MutableStateFlow(false)
    val handsFreeActive: StateFlow<Boolean> = _handsFreeActive.asStateFlow()

    /** When false, unprompted speech runs a command without the wake word. */
    private val _wakeWordRequired = MutableStateFlow(true)
    val wakeWordRequired: StateFlow<Boolean> = _wakeWordRequired.asStateFlow()

    /** How long the wake window stays open, for the live countdown in the UI. */
    private val _wakeWindowMs = MutableStateFlow(0L)
    val wakeWindowMs: StateFlow<Long> = _wakeWindowMs.asStateFlow()

    private val _confirmationPrompt = MutableStateFlow("")
    val confirmationPrompt: StateFlow<String> = _confirmationPrompt.asStateFlow()

    private val _intentSource = MutableStateFlow<NlpProcessor.Source?>(null)
    val intentSource: StateFlow<NlpProcessor.Source?> = _intentSource.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /** True while the user has the in-app diagnose panel open. UI-only. */
    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()

    /** The persisted chat transcript, oldest first. */
    val chatMessages: StateFlow<List<ChatMessageEntity>> =
        chatHistory.observeMessages().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    /**
     * The reply as it is being generated, so the chat can show it arriving
     * token by token rather than all at once at the end.
     *
     * This is deliberately a separate StateFlow rather than the generator's
     * replaying SharedFlow: the stream keeps the last value it replayed, so
     * the finished answer would otherwise sit on screen next to the
     * completed message.
     */
    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

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

    /** The current sherpa-onnx model path, or null if no model is installed. */
    val diagnosticModelPath: String? get() = modelStore.installedModelPath()

    /** The size of the installed sherpa-onnx model in MB, or null if no model is installed. */
    val diagnosticModelSizeMb: Double? get() = modelStore.installedModelSizeMb()

    /** The current llama.cpp reply model path, or null if no model is installed. */
    val diagnosticReplyModelPath: String? get() = llamaModelStore.installedModelPath()

    /** The size of the installed reply model in MB, or null if no model is installed. */
    val diagnosticReplyModelSizeMb: Double? get() = llamaModelStore.installedModelSizeMb()

    /** The llama.cpp version the native library was built from. */
    val diagnosticLlamaVersion: String get() = llamaGenerator.backendVersion

    /**
     * Set by the `"shaz"` interrupt and consumed by the next generation failure, so a
     * deliberately stopped answer is not reported to the user as an error.
     */
    @Volatile
    private var interruptedByUser = false

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

    /**
     * The hands-free wake loop.
     *
     * It is created eagerly but only starts a microphone session on demand, so a phone that
     * never enables hands-free never opens the microphone in the background. The gate is the
     * same object the controller consults, which is what keeps "is the window open?" a single
     * piece of truth instead of two flags that can disagree.
     */
    private val wakeGate = WakeSessionGate()

    private val wakeController = ContinuousWakeController(
        capture = audioCaptureSource,
        integrator = sttIntegrator,
        gate = wakeGate,
        scope = viewModelScope,
    )

    private val wakeCallbacks = object : ContinuousWakeController.Callbacks {
        override fun onWakeAcknowledged() {
            viewModelScope.launch {
                _statusMessage.value = "Wake word heard — the command window is open, go ahead."
                runCatching {
                    decisionLog.note(
                        Capability.MIC_CAPTURE,
                        "wake",
                        "heretic",
                        "A wake word opened the command window, which is the only way unprefixed speech is accepted.",
                    )
                }
                speakAndSettle("Yes?")
            }
        }

        override fun onCommand(command: String, wokeNow: Boolean) {
            viewModelScope.launch {
                _partialTranscript.value = command
                _lastUtterance.value = command
                if (wokeNow) {
                    _statusMessage.value = "Command from the wake word: \"$command\""
                }
                if (uiState.value !is AssistantUiState.Listening) {
                    machine.processEvent(AssistantEvent.OnWakeWordDetected)
                }
                machine.processEvent(AssistantEvent.OnSpeechEnded)
                handleUtterance(command)
            }
        }

        override fun onInterrupt() {
            interruptNow(fromWakeWord = true)
        }

        override fun onIgnoredAmbient(text: String) {
            _statusMessage.value = "Ignored room speech (say \"Heretic\" first): $text"
        }

        override fun onPartial(text: String) {
            _partialTranscript.value = text
        }

        override fun onProblem(message: String) {
            _statusMessage.value = message
            _handsFreeActive.value = false
        }
    }

    init {
        viewModelScope.launch { refreshStatuses() }
        viewModelScope.launch {
            // Put the user's documents back before anything can ask a question. The
            // snapshot is one small JSON file, so this is a few milliseconds; it is on
            // its own coroutine anyway so a slow disk cannot delay the first frame.
            restoreKnowledgeBase()
        }
        viewModelScope.launch {
            // A reply that is still pending can only have been cut short by the
            // process being killed. Mark it so the transcript does not keep a
            // blank bubble.
            val recovered = runCatching { chatHistory.recoverInterruptedReplies() }.getOrDefault(0)
            if (recovered > 0) {
                _statusMessage.value = "Recovered $recovered interrupted repl" +
                    if (recovered == 1) "y." else "ies."
            }
        }
        viewModelScope.launch {
            audioCaptureSource.errors.collect { error ->
                _statusMessage.value = error.message ?: "Microphone capture failed"
                fullReset()
            }
        }
        // Mirror the generator's token stream into the UI, clearing it once
        // the reply has been written to the transcript.
        viewModelScope.launch {
            llamaGenerator.partialReply.collect { text ->
                _streamingReply.value = text
            }
        }
    }

    // ------------------------------------------------------------------ chat

    /**
     * Sends a typed message: records it in the transcript, generates a reply
     * and speaks it.
     */
    fun sendChatMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                // Typed questions get the same retrieval treatment as spoken ones: the
                // knowledge base does not care how the question arrived.
                val context = retrieveContext(trimmed)
                val reply = chatHistory.runTurn(trimmed, context)
                speakAndSettle(reply)
            } catch (error: Exception) {
                _statusMessage.value = error.message ?: "The reply could not be generated"
            } finally {
                // The finished reply is now in the transcript, so the
                // streaming bubble is no longer needed.
                _streamingReply.value = ""
                machine.processEvent(AssistantEvent.OnCleanupComplete)
            }
        }
    }

    /** Clears the chat transcript. */
    fun clearChat() {
        viewModelScope.launch {
            runCatching { chatHistory.clear() }
                .onFailure { _statusMessage.value = "Could not clear the conversation" }
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
        _replyModelStatus.value = if (llamaModelStore.installedModelPath() == null) {
            "Local reply model not installed (open-ended answers use the built-in replies)"
        } else {
            "Local reply model ready (llama.cpp ${llamaGenerator.backendVersion})"
        }
        _vaultStatus.value = runCatching {
            val summary = memory.summary()
            "${summary.taughtExamples} taught phrases • vault on ${summary.backend}"
        }.getOrDefault("Encrypted vault unavailable")
        _taughtPhrases.value = runCatching { memory.examples() }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------- model import

    /** Imports an extracted sherpa-onnx model folder. */
    fun importOfflineModel(treeUri: Uri) = importSpeechModel { modelStore.importModelTree(treeUri) }

    /** Imports a sherpa-onnx model downloaded as a `.zip` archive. */
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
                // The model arrives as a GGUF file the user picked locally.
                // There is no downloader and no network access of any kind.
                llamaModelStore.importModel(uri)
                localTextGenerator.onModelUpdated()
                // Keep the model resident now that there is one worth keeping.
                runCatching { llamaModelServiceStarter.start() }
                _statusMessage.value = "Reply model installed for open-ended questions."
            } catch (error: Exception) {
                _statusMessage.value = error.message ?: "Could not import the selected reply model"
            } finally {
                _isImporting.value = false
                refreshStatuses()
            }
        }
    }

    /**
     * A recommendation for which model size this device can realistically run.
     */
    fun modelRecommendation(): String = ModelTierPicker.recommendation(
        availableBytes = memoryBudget.availableMemoryBytes,
        totalBytes = memoryBudget.totalMemoryBytes
    )

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
            _statusMessage.value = "Import an offline speech model (folder or .zip) before listening."
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
        val replyModelPath = llamaModelStore.installedModelPath()
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
        sb.appendLine("==== JARVIS BUILD REPORT ====")
        sb.appendLine("Build: $buildLabel")
        sb.appendLine("=============================")
        sb.appendLine("Device: total RAM ${totalMb} MB, available ${availableMb} MB")
        sb.appendLine("Speech model: ${modelPath ?: "(not installed)"}")
        if (modelSizeMb != null) sb.appendLine("Speech model size: %.1f MB".format(modelSizeMb))
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
            is SttEvent.OnPartialResult -> {
                if (_handsFreeActive.value) {
                    wakeController.onPartialHypothesis(event.text)
                } else {
                    viewModelScope.launch { _partialTranscript.value = event.text }
                }
            }

            is SttEvent.OnFinalResult -> {
                // The hands-free loop owns its own session: stopping the capture here would
                // silently end standby after the first command, which is the opposite of
                // "continuous". Its transcripts go through the wake gate instead.
                if (_handsFreeActive.value) {
                    viewModelScope.launch { wakeController.onFinalTranscript(event.text) }
                    return
                }
                viewModelScope.launch {
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

    /**
     * Routes one recognised utterance, whichever path produced it (orb, wake word, chat).
     *
     * The order is the safety model: a stop word is obeyed before anything else, the
     * deterministic intent router gets the first say, and only an unclaimed request is
     * answered from the knowledge base or the local model.
     */
    private fun handleUtterance(utterance: String) {
        viewModelScope.launch {
            try {
                val parsed = nlpProcessor.process(utterance)
                onIntentParsed(parsed, utterance)
            } catch (error: Exception) {
                _statusMessage.value = error.message ?: "Could not understand the request"
                fullReset(keepMessage = true)
            }
        }
    }

    // ------------------------------------------------------- hands-free wake

    /**
     * Turns the hands-free loop on or off.
     *
     * Deliberately refuses to start without an installed speech model: the loop's whole
     * value is that it recognises the wake word locally, and failing loudly now is better
     * than a microphone that is open with nothing listening.
     */
    fun toggleHandsFree() {
        if (_handsFreeActive.value) {
            stopHandsFree()
        } else {
            startHandsFree()
        }
    }

    fun startHandsFree() {
        val modelPath = modelStore.installedModelPath()
        if (modelPath == null) {
            _statusMessage.value = "Import an offline speech model before using hands-free listening."
            return
        }
        viewModelScope.launch {
            _statusMessage.value = "Loading the offline speech model for hands-free listening…"
            runCatching {
                decisionLog.authorize(
                    Capability.MIC_CAPTURE,
                    "handsfree.start",
                    "continuous",
                    "Hands-free standby opens the microphone to watch for the wake word and nothing else.",
                )
            }
            if (loadedModelPath != modelPath) {
                if (!sttIntegrator.initializeModel(modelPath)) {
                    _statusMessage.value = sttIntegrator.lastInitializationError
                        ?: "The offline speech model could not be loaded"
                    return@launch
                }
                loadedModelPath = modelPath
            }
            val started = wakeController.start(wakeCallbacks)
            _handsFreeActive.value = started
            if (started) {
                machine.reset()
                _statusMessage.value = if (_wakeWordRequired.value) {
                    "Hands-free standby — say \"Heretic\" to give a command, or \"shaz\" to stop playback."
                } else {
                    "Open-mic listening — speak normally, or say \"shaz\" to stop playback."
                }
            }
            refreshWakeState()
        }
    }

    fun stopHandsFree() {
        viewModelScope.launch {
            wakeController.stop()
            _handsFreeActive.value = false
            _wakeState.value = WakeState.OFF
            _statusMessage.value = "Hands-free listening stopped."
        }
    }

    /** Switches between "wake word required" and open-mic standby. */
    fun setWakeWordRequired(required: Boolean) {
        _wakeWordRequired.value = required
        wakeController.setWakeWordRequired(required)
        _statusMessage.value = if (required) {
            "Wake word on — say \"Heretic\" first."
        } else {
            "Wake word off — the assistant answers any speech it hears."
        }
        refreshWakeState()
    }

    fun toggleWakeWordRequired() = setWakeWordRequired(!_wakeWordRequired.value)

    /** Re-arms the hands-free window from a tap, as if the wake word had been said. */
    fun armWakeWindow() {
        wakeController.armNow()
        _statusMessage.value = "Command window open for " +
            "${com.example.voiceassistant.wake.WakeWordDetector.DEFAULT_AWAKE_WINDOW_MS / 1000} seconds — go ahead."
        refreshWakeState()
    }

    /**
     * The `"shaz"` interrupt: stop the audio immediately and halt the generation behind it.
     *
     * Order matters and is not negotiable — the audio buffer first (so the user hears
     * silence at once), then the token stream (so no new sentence is queued), then the state
     * machine and the status line. Everything else in the app is downstream of this.
     */
    fun interruptPlayback() = interruptNow(fromWakeWord = false)

    private fun interruptNow(fromWakeWord: Boolean) {
        interruptedByUser = true
        val interruptedUtterance = ttsController.interrupt()
        // Halt the native generation. This is the llama.cpp cancellation flag, so the token
        // callback stops firing and no further sentence reaches the speaker.
        runCatching { llamaGenerator.cancel() }
        wakeController.sleepNow()
        _streamingReply.value = ""
        viewModelScope.launch {
            runCatching {
                decisionLog.note(
                    Capability.TTS_SPEAK,
                    "interrupt",
                    interruptedUtterance ?: "idle",
                    "The stop word was heard, so playback was flushed and the generation behind it was halted.",
                )
            }
        }
        _statusMessage.value = if (fromWakeWord) {
            "\"shaz\" heard — playback flushed and generation halted."
        } else {
            "Playback stopped."
        }
        if (uiState.value is AssistantUiState.Speaking || interruptedUtterance != null) {
            machine.processEvent(AssistantEvent.OnSpeechSynthesisCompleted)
            machine.processEvent(AssistantEvent.OnCleanupComplete)
        }
        refreshWakeState()
    }

    private fun refreshWakeState() {
        _wakeState.value = wakeController.state.value
        _wakeWindowMs.value = wakeController.remainingWindowMs()
    }

    // -------------------------------------------------------- knowledge base

    /** Indexes one picked document (a `.zip` is expanded member by member). */
    fun importKnowledgeDocument(uri: Uri, displayName: String? = null) {
        if (_ragBusy.value) return
        _ragBusy.value = true
        viewModelScope.launch {
            _statusMessage.value = "Indexing the document locally…"
            try {
                val authorized = decisionLog.authorize(
                    capability = Capability.RAG_INDEX,
                    action = "import.document",
                    subject = displayName ?: uri.lastPathSegment ?: "document",
                    because = "The user picked this document in the system file picker, which is the consent to read it.",
                )
                if (!authorized) {
                    _statusMessage.value = "The knowledge base is blocked by the authorization gate."
                    return@launch
                }
                val report = ragIngestor.ingestDocument(uri, displayName) { progress ->
                    _statusMessage.value = progress
                }
                applyIngestReport(report)
            } catch (error: Exception) {
                _statusMessage.value = error.message ?: "The document could not be indexed"
            } finally {
                _ragBusy.value = false
            }
        }
    }

    /** Indexes every supported document under a picked folder, recursively. */
    fun importKnowledgeFolder(treeUri: Uri) {
        if (_ragBusy.value) return
        _ragBusy.value = true
        viewModelScope.launch {
            _statusMessage.value = "Indexing the folder locally…"
            try {
                val authorized = decisionLog.authorize(
                    capability = Capability.RAG_INDEX,
                    action = "import.tree",
                    subject = treeUri.lastPathSegment ?: "folder",
                    because = "The user granted a whole folder through the system picker, so every document in it may be read.",
                )
                if (!authorized) {
                    _statusMessage.value = "The knowledge base is blocked by the authorization gate."
                    return@launch
                }
                val report = ragIngestor.ingestTree(treeUri) { progress ->
                    _statusMessage.value = progress
                }
                applyIngestReport(report)
            } catch (error: Exception) {
                _statusMessage.value = error.message ?: "The folder could not be indexed"
            } finally {
                _ragBusy.value = false
            }
        }
    }

    /**
     * Reloads the saved knowledge snapshot into the index and publishes its summary.
     *
     * Every import already persists, so this is the other half of the durability story:
     * documents survive a cold start, and the "Knowledge:" line on the home screen is
     * accurate before the user opens the panel. A failure here leaves the index empty
     * rather than blocking startup — the assistant works without a knowledge base.
     */
    private suspend fun restoreKnowledgeBase() {
        val summary = runCatching { ragIngestor.restore() }.getOrElse { error ->
            _ragStatus.value = "No documents indexed"
            _statusMessage.value = error.message ?: "The saved knowledge base could not be read"
            return
        }
        _ragSources.value = summary.sources
        _ragStatus.value = summary.describe()
        if (!summary.isEmpty) {
            _statusMessage.value = "Restored ${summary.describe()} from this device."
            runCatching {
                decisionLog.note(
                    Capability.RAG_INDEX,
                    "restored",
                    "${summary.sources.size} file(s)",
                    "The saved on-device knowledge snapshot was reloaded, so no document had to be re-imported.",
                )
            }
        }
    }

    private fun applyIngestReport(report: com.example.voiceassistant.rag.RagIngestReport) {
        val summary = ragIngestor.indexSummary()
        _ragSources.value = summary.sources
        _ragStatus.value = summary.describe()
        _ragLastIngest.value = report.describe()
        _statusMessage.value = report.describe()
        viewModelScope.launch {
            runCatching {
                decisionLog.note(
                    Capability.RAG_INDEX,
                    "indexed",
                    "${report.filesIndexed} file(s)",
                    "Indexing ran entirely on device and produced ${report.chunksAdded} new excerpt(s) for later retrieval.",
                )
            }
        }
    }

    fun forgetKnowledgeSource(sourcePath: String) {
        viewModelScope.launch {
            val removed = ragIngestor.forget(sourcePath, viewModelScope)
            val summary = ragIngestor.indexSummary()
            _ragSources.value = summary.sources
            _ragStatus.value = summary.describe()
            _statusMessage.value = if (removed) "Removed $sourcePath from the index." else "That source was not indexed."
            runCatching {
                decisionLog.note(
                    Capability.RAG_INDEX,
                    "forget",
                    sourcePath,
                    "The user removed this source, so its excerpts no longer reach the prompt.",
                )
            }
            if (summary.isEmpty) _ragLastIngest.value = ""
        }
    }

    fun clearKnowledgeBase() {
        viewModelScope.launch {
            // clear() also deletes the saved snapshot, so "clear" really means the
            // documents are gone after a restart rather than only out of memory.
            ragIngestor.clear(viewModelScope)
            _ragSources.value = emptyList()
            _ragStatus.value = "No documents indexed"
            _ragLastIngest.value = ""
            _ragLastSources.value = emptyList()
            _statusMessage.value = "Cleared the local knowledge base."
            runCatching {
                decisionLog.note(
                    Capability.RAG_INDEX,
                    "cleared",
                    "knowledge base",
                    "The user emptied the knowledge base, so no excerpts remain in memory or on disk.",
                )
            }
        }
    }

    fun setKnowledgeBaseEnabled(enabled: Boolean) {
        _ragEnabled.value = enabled
        _statusMessage.value = if (enabled) {
            "Answers will be grounded in your indexed documents."
        } else {
            "Answers will come from the model alone."
        }
    }

    fun setRagTargetFilter(filter: String) {
        _ragTargetFilter.value = filter.trim()
    }

    /**
     * Retrieves the excerpts that best answer [question], recording the retrieval in the ADR
     * log. Returns `null` when there is nothing to say, so the caller falls back to an
     * ordinary answer instead of quoting an empty context.
     */
    private suspend fun retrieveContext(question: String): RagContext? {
        if (!_ragEnabled.value) return null
        if (!decisionLog.authorize(
                capability = Capability.RAG_QUERY,
                action = "retrieve",
                subject = question.take(80),
                because = "Answering from documents already on this device needs no network and no further consent.",
            )
        ) {
            return null
        }
        // Resolve the query against the paths that are actually indexed, so "the
        // deployment document" is only treated as a file filter when a file by that
        // name exists. Without this the filter could match nothing and silently fall
        // back to the whole index, which reads to the user as "it ignored my file".
        val query = RagQuery.parse(question, ragIngestor.indexSummary().sources.map { it.sourcePath })
        if (query.isEmpty || RagQuery.looksLikeDeviceCommand(query.question)) return null
        val configured = _ragTargetFilter.value
        val effective = if (configured.isNotBlank() && query.fileFilter == null) {
            query.copy(fileFilter = configured)
        } else {
            query
        }
        val context = ragIngestor.buildContext(effective)
        if (context.isEmpty) {
            _ragLastSources.value = emptyList()
            return null
        }
        _ragLastSources.value = context.sourceLabels
        runCatching {
            decisionLog.note(
                Capability.RAG_QUERY,
                "retrieved",
                context.chunks.joinToString(", ") { it.label }.take(180),
                "Ranking picked ${context.chunks.size} excerpt(s) by token overlap, file name and page number.",
            )
        }
        return context
    }

    /** The ADR log's capability table plus chain state, for the gates panel. */
    fun decisionSummary(): String = buildString {
        appendLine("GATES")
        Capability.entries.forEach { capability ->
            val state = if (decisionLog.isGranted(capability)) "ALLOWED" else "DENIED "
            appendLine("  $state  ${capability.id}")
        }
        appendLine()
        appendLine("ADR LOG")
        val records = decisionLog.recentRecords(8)
        if (records.isEmpty()) {
            appendLine("  (no decisions recorded yet)")
        } else {
            records.forEach { record ->
                appendLine("  #${record.sequence} ${record.outcome} ${record.capability} ${record.action}")
            }
        }
        appendLine()
        append("Chain: ")
        append("verified on demand — open the Gates tab to re-walk every hash link.")
    }

    /** Re-walks the whole `decisions.jsonl` chain and returns the verdict. */
    fun verifyDecisionChain(): String {
        var verdict = "checking…"
        val job = viewModelScope.launch {
            verdict = runCatching { decisionLog.verifyChain() }.getOrElse { error ->
                "the chain could not be verified: ${error.message}"
            } ?: "CHAIN INTACT — every record links to the previous one."
            _statusMessage.value = verdict
        }
        // The launch above is asynchronous; the UI refreshes from statusMessage.
        job.invokeOnCompletion { }
        return "Verifying the hash chain… the result appears in the status line."
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
                is AppIntent.LookUpContact -> contactsExecutor.execute(context, intent)
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

    /**
     * Answers an open-ended request, grounding it in the local knowledge base when the
     * question matches something the user has indexed.
     *
     * The prompt shape is fixed by [RagPromptComposer] — `Context Information: … Question: …`
     * — so the llama.cpp path, the LiteRT path and the deterministic fallback all receive
     * exactly the same block, and the bubble in the transcript quotes the same sources the
     * model was shown.
     */
    private fun handleOpenEndedRequest(userText: String) {
        viewModelScope.launch {
            if (userText.isBlank()) {
                speakAndSettle("I didn't catch that. Please try again.")
                machine.processEvent(AssistantEvent.OnCleanupComplete)
                return@launch
            }

            val context = runCatching { retrieveContext(userText) }.getOrNull()
            val prompt = RagPromptComposer.compose(context, userText)

            _statusMessage.value = when {
                prompt.usedContext && llamaModelStore.installedModelPath() != null ->
                    "Answering from ${prompt.contextChunks} local excerpt(s) with the on-device model…"
                prompt.usedContext ->
                    "Answering from ${prompt.contextChunks} local excerpt(s)…"
                llamaModelStore.installedModelPath() == null ->
                    "Replying with the built-in fallback (no reply model installed)…"
                else ->
                    // Loading a multi-GB model takes 5–15 seconds the first time.
                    "Thinking with the on-device model (first load can take 5–15 seconds)…"
            }

            val reply = try {
                localTextGenerator.generateReply(prompt)
            } catch (error: Exception) {
                if (interruptedByUser) {
                    // "shaz" stopped the generation on purpose; that is not a failure.
                    interruptedByUser = false
                    machine.processEvent(AssistantEvent.OnCleanupComplete)
                    return@launch
                }
                _replyModelStatus.value = "Local reply model could not be loaded"
                _statusMessage.value = error.message ?: "Local generation failed"
                "I couldn't load the local reply model. Please check the selected file."
            }
            // speakAndSettle always ends in ReturnToIdle, so the loop can never get stuck
            // in Speaking if the synthesis engine reports nothing.
            speakAndSettle(reply)
            _streamingReply.value = ""
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
                        (event is TtsEvent.OnInterrupted && event.utteranceId == utteranceId) ||
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
        is AppIntent.LookUpContact -> "Looking up ${intent.name}."
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
        // The hands-free loop holds the microphone; it must be released before the capture
        // source is closed, or AudioRecord is torn down while a read is in flight.
        wakeController.sleepNow()
        ttsController.interrupt()
        runCatching { llamaGenerator.cancel() }
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
