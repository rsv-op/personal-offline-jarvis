package com.example.voiceassistant.orchestrator

import android.content.Context
import com.example.voiceassistant.TestFiles
import com.example.voiceassistant.aimatrix.AiMatrixClassifier
import com.example.voiceassistant.aimatrix.IntentModelSource
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
import com.example.voiceassistant.statemachine.AssistantUiState
import com.example.voiceassistant.stt.AudioCaptureSource
import com.example.voiceassistant.stt.SttEngine
import com.example.voiceassistant.stt.VoskModelStore
import com.example.voiceassistant.vault.JarvisMemory
import com.example.voiceassistant.vault.SecureVault
import com.example.voiceassistant.vault.SoftwareVaultCipher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.io.File

/**
 * View-model level behaviour: the confirmation gate, the follow-up questions and the
 * spoken yes/no loop — all without a microphone or a real Vosk model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var mockTtsController: TtsController
    private lateinit var mockFlashlightExecutor: FlashlightExecutor
    private lateinit var mockAlarmExecutor: AlarmExecutor
    private lateinit var mockTimerExecutor: TimerExecutor
    private lateinit var mockStatusExecutor: StatusExecutor
    private lateinit var mockAudioCaptureSource: AudioCaptureSource
    private lateinit var mockSttEngine: SttEngine
    private lateinit var mockModelStore: VoskModelStore
    private lateinit var mockLocalTextGenerator: LocalTextGenerator
    private lateinit var mockLocalGenerationModelStore: LocalGenerationModelStore
    private lateinit var mockMemoryBudget: com.example.voiceassistant.memory.DeviceMemory
    private lateinit var classifier: AiMatrixClassifier
    private lateinit var memory: JarvisMemory
    private lateinit var brain: OnDeviceBrain
    private lateinit var viewModel: AssistantViewModel
    private lateinit var dispatcher: TestDispatcher
    private lateinit var frames: MutableSharedFlow<ShortArray>
    private val viewModelStore = ViewModelStore()

    private class FakeModelSource(private val classifier: AiMatrixClassifier?) : IntentModelSource {
        override var lastError: String? = null
        override suspend fun classifier(): AiMatrixClassifier? = classifier
    }

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)

        mockContext = mock()
        mockTtsController = mock()
        mockFlashlightExecutor = mock()
        mockAlarmExecutor = mock()
        mockTimerExecutor = mock()
        mockStatusExecutor = mock()
        mockAudioCaptureSource = mock()
        mockSttEngine = mock()
        mockModelStore = mock()
        mockLocalTextGenerator = mock()
        mockLocalGenerationModelStore = mock()
        // The view model only reads totalMemoryBytes/availableMemoryBytes from this, and
        // the existing test surface never inspects them, so a plain Mockito mock is fine.
        mockMemoryBudget = mock()

        frames = MutableSharedFlow(extraBufferCapacity = 4)
        doReturn(frames).whenever(mockAudioCaptureSource).frames
        doReturn(MutableSharedFlow<Throwable>()).whenever(mockAudioCaptureSource).errors
        doReturn(MutableSharedFlow<TtsEvent>()).whenever(mockTtsController).events
        doReturn("/model").whenever(mockModelStore).installedModelPath()
        doReturn(40.0).whenever(mockModelStore).installedModelSizeMb()
        doReturn(null).whenever(mockLocalGenerationModelStore).installedModelPath()
        runBlocking {
            whenever(mockSttEngine.getPartialText()).thenReturn("")
            whenever(mockSttEngine.getFinalText()).thenReturn("")
        }

        val vault = SecureVault(File(temporaryFolder.root, "vault"), SoftwareVaultCipher())
        memory = JarvisMemory(vault).apply { ioDispatcher = dispatcher }
        classifier = AiMatrixClassifier.fromJson(TestFiles.aiMatrixAsset().readText())
        brain = OnDeviceBrain(FakeModelSource(classifier), memory)

        runBlocking {
            whenever(mockFlashlightExecutor.execute(any(), any())).thenReturn("Flashlight on.")
            whenever(mockAlarmExecutor.execute(any(), any())).thenReturn("Alarm set for 07:00.")
            whenever(mockTimerExecutor.execute(any(), any())).thenReturn("Timer started for 10 minutes.")
            whenever(mockStatusExecutor.execute(any(), any())).thenReturn("Battery is 80 percent.")
        }

        viewModel = createViewModel()
    }

    /** Builds the view model through a store so it can be cleared in tearDown. */
    private fun createViewModel(): AssistantViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AssistantViewModel(
            context = mockContext,
            ttsController = mockTtsController,
            flashlightExecutor = mockFlashlightExecutor,
            alarmExecutor = mockAlarmExecutor,
            timerExecutor = mockTimerExecutor,
            statusExecutor = mockStatusExecutor,
            assistantStatusProvider = AssistantStatusProvider(
                FakeModelSource(classifier),
                mockModelStore,
                memory
            ),
            nlpProcessor = NlpProcessor(brain = brain),
            sttEngine = mockSttEngine,
            audioCaptureSource = mockAudioCaptureSource,
            modelStore = mockModelStore,
            localTextGenerator = mockLocalTextGenerator,
            localGenerationModelStore = mockLocalGenerationModelStore,
            brain = brain,
            memory = memory,
            memoryBudget = mockMemoryBudget,
            speechDispatcher = dispatcher
        ) as T
        }
        return ViewModelProvider(viewModelStore, factory)[AssistantViewModel::class.java]
    }

    @After
    fun tearDown() {
        // Clearing the store cancels viewModelScope, so no coroutine from this test can
        // leak into the next one (which kotlinx-coroutines-test reports as a failure).
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `direct execution invokes matching executor and returns to idle`() = runTest(dispatcher) {
        val intent = AppIntent.ToggleFlashlight(true)
        viewModel.onIntentParsed(NlpProcessor.OnIntentParsed(intent, false, 1.0f))
        advanceUntilIdle()

        verifyBlocking(mockFlashlightExecutor) { execute(mockContext, intent) }
        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `unknown request still calls the reply generator even without a model`() = runTest(dispatcher) {
        // No model installed: the generator itself returns a rule-based acknowledgement, so
        // the user hears a real reply instead of a pre-empt.
        whenever(mockLocalGenerationModelStore.installedModelPath()).thenReturn(null)
        whenever(mockLocalTextGenerator.generateReply(any())).thenReturn("rule-based reply")

        val parsed = NlpProcessor().process("what is the capital of France")
        viewModel.onIntentParsed(parsed, "what is the capital of France")
        advanceUntilIdle()

        verifyBlocking(mockLocalTextGenerator) { generateReply(eq("what is the capital of France")) }
        verifyBlocking(mockTtsController) { speak(eq("rule-based reply"), any()) }
        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `recognized device action is confirmation gated`() = runTest(dispatcher) {
        val parsed = NlpProcessor().process("turn on flashlight")
        viewModel.onIntentParsed(parsed)
        advanceUntilIdle()

        verifyBlocking(mockFlashlightExecutor, never()) {
            execute(mockContext, AppIntent.ToggleFlashlight(true))
        }
        assertEquals(AssistantUiState.NeedsConfirmation, viewModel.uiState.value)
        assertTrue(viewModel.confirmationPrompt.value.contains("flashlight on"))
    }

    @Test
    fun `alarm without a time asks a follow-up instead of executing`() = runTest(dispatcher) {
        val intent = AppIntent.SetAlarm(null)
        viewModel.onIntentParsed(NlpProcessor.OnIntentParsed(intent, true, 0.4f))
        advanceUntilIdle()

        verifyBlocking(mockTtsController) {
            speak(eq("What time would you like the alarm for?"), any())
        }
        verifyBlocking(mockAlarmExecutor, never()) { execute(mockContext, intent) }
        // The follow-up is a voice loop: the assistant asks, then listens for the answer.
        assertEquals(AssistantUiState.Listening, viewModel.uiState.value)
    }

    @Test
    fun `confirm executes the pending alarm`() = runTest(dispatcher) {
        val intent = AppIntent.SetAlarm("07:00")
        viewModel.onIntentParsed(NlpProcessor.OnIntentParsed(intent, true, 1.0f))
        advanceUntilIdle()

        viewModel.confirmAction()
        advanceUntilIdle()

        verifyBlocking(mockAlarmExecutor) { execute(mockContext, intent) }
        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `deny clears pending intent without executing it`() = runTest(dispatcher) {
        val intent = AppIntent.SetAlarm("07:00")
        viewModel.onIntentParsed(NlpProcessor.OnIntentParsed(intent, true, 1.0f))
        advanceUntilIdle()

        viewModel.denyAction()
        advanceUntilIdle()

        verifyBlocking(mockAlarmExecutor, never()) { execute(mockContext, intent) }
        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `timer intent is executed through the timer executor`() = runTest(dispatcher) {
        val intent = AppIntent.SetTimer(600)
        viewModel.onIntentParsed(NlpProcessor.OnIntentParsed(intent, false, 1.0f))
        advanceUntilIdle()

        verifyBlocking(mockTimerExecutor) { execute(mockContext, intent) }
        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `a read-only status request is answered without confirmation`() = runTest(dispatcher) {
        val parsed = NlpProcessor().process("how much battery is left")
        viewModel.onIntentParsed(parsed)
        advanceUntilIdle()

        verifyBlocking(mockStatusExecutor) { execute(mockContext, AppIntent.StatusReport) }
        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `deleting files is refused instead of executed`() = runTest(dispatcher) {
        viewModel.onIntentParsed(
            NlpProcessor.OnIntentParsed(AppIntent.DeleteFile("notes"), true, 0.8f)
        )
        advanceUntilIdle()

        verifyBlocking(mockTtsController) {
            speak(eq("File deletion is not available in this build, so nothing was deleted."), any())
        }
        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `spoken yes confirms the pending action`() = runTest(dispatcher) {
        viewModel.onIntentParsed(NlpProcessor().process("turn on flashlight"))
        advanceUntilIdle()
        assertEquals(AssistantUiState.NeedsConfirmation, viewModel.uiState.value)

        runBlocking { whenever(mockSttEngine.getFinalText()).thenReturn("yes") }
        viewModel.startListening()
        advanceUntilIdle()
        assertEquals(AssistantUiState.Listening, viewModel.uiState.value)

        viewModel.stopListening()
        advanceUntilIdle()

        verifyBlocking(mockFlashlightExecutor) {
            execute(mockContext, AppIntent.ToggleFlashlight(true))
        }
        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `spoken cancel denies the pending action`() = runTest(dispatcher) {
        viewModel.onIntentParsed(NlpProcessor().process("turn on flashlight"))
        advanceUntilIdle()

        runBlocking { whenever(mockSttEngine.getFinalText()).thenReturn("cancel") }
        viewModel.startListening()
        advanceUntilIdle()
        viewModel.stopListening()
        advanceUntilIdle()

        verifyBlocking(mockFlashlightExecutor, never()) {
            execute(mockContext, AppIntent.ToggleFlashlight(true))
        }
        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `listening without a speech model explains what to import`() = runTest(dispatcher) {
        runBlocking { whenever(mockModelStore.installedModelPath()).thenReturn(null) }
        viewModel.startListening()
        advanceUntilIdle()

        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
        assertTrue(viewModel.statusMessage.value.contains("Import an offline Vosk model"))
    }

    @Test
    fun `taught phrases are remembered and reported`() = runTest(dispatcher) {
        viewModel.teach("make it bright", "FLASHLIGHT_ON")
        advanceUntilIdle()

        assertEquals(1, memory.examples().size)
        assertEquals(1, viewModel.taughtPhrases.value.size)
        assertTrue(viewModel.statusMessage.value.contains("make it bright"))

        viewModel.forgetPhrase("make it bright")
        advanceUntilIdle()
        assertTrue(memory.examples().isEmpty())
    }

    @Test
    fun `teaching an unknown action is rejected`() = runTest(dispatcher) {
        viewModel.teach("launch the rockets", "NOPE")
        advanceUntilIdle()
        assertTrue(memory.examples().isEmpty())
    }

    @Test
    fun `every executed action is logged to the encrypted vault`() = runTest(dispatcher) {
        viewModel.onIntentParsed(NlpProcessor().process("turn on flashlight"))
        advanceUntilIdle()
        viewModel.confirmAction()
        advanceUntilIdle()

        val actions = memory.recentActions()
        assertEquals(1, actions.size)
        assertEquals("ToggleFlashlight", actions.single().label)
        assertTrue(actions.single().outcome.contains("Flashlight on"))
    }
}
