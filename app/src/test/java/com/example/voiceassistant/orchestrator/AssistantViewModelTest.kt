package com.example.voiceassistant.orchestrator

import android.content.Context
import com.example.voiceassistant.execution.AlarmExecutor
import com.example.voiceassistant.execution.FlashlightExecutor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {
    private lateinit var mockContext: Context
    private lateinit var mockTtsController: TtsController
    private lateinit var mockFlashlightExecutor: FlashlightExecutor
    private lateinit var mockAlarmExecutor: AlarmExecutor
    private lateinit var mockAudioCaptureSource: AudioCaptureSource
    private lateinit var mockSttEngine: SttEngine
    private lateinit var mockModelStore: VoskModelStore
    private lateinit var mockLocalTextGenerator: LocalTextGenerator
    private lateinit var mockLocalGenerationModelStore: LocalGenerationModelStore
    private lateinit var viewModel: AssistantViewModel
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        mockContext = mock()
        mockTtsController = mock()
        mockFlashlightExecutor = mock()
        mockAlarmExecutor = mock()
        mockAudioCaptureSource = mock()
        mockSttEngine = mock()
        mockModelStore = mock()
        mockLocalTextGenerator = mock()
        mockLocalGenerationModelStore = mock()

        doReturn(MutableSharedFlow<TtsEvent>()).whenever(mockTtsController).events
        doReturn(MutableSharedFlow<ShortArray>()).whenever(mockAudioCaptureSource).frames
        doReturn(MutableSharedFlow<Throwable>()).whenever(mockAudioCaptureSource).errors

        viewModel = AssistantViewModel(
            context = mockContext,
            ttsController = mockTtsController,
            flashlightExecutor = mockFlashlightExecutor,
            alarmExecutor = mockAlarmExecutor,
            nlpProcessor = NlpProcessor(),
            sttEngine = mockSttEngine,
            audioCaptureSource = mockAudioCaptureSource,
            modelStore = mockModelStore,
            localTextGenerator = mockLocalTextGenerator,
            localGenerationModelStore = mockLocalGenerationModelStore
        )
    }

    @After
    fun tearDown() {
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
    fun `unknown request stays offline and asks for a local reply model`() = runTest(dispatcher) {
        val parsed = NlpProcessor().process("what is the capital of France")
        viewModel.onIntentParsed(parsed, "what is the capital of France")
        advanceUntilIdle()

        verifyBlocking(mockLocalTextGenerator, never()) { generateReply(any()) }
        verifyBlocking(mockTtsController) {
            speak(eq("I can answer open-ended questions after you import a local reply model."), any())
        }
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
    }

    @Test
    fun `alarm without a time asks a follow-up instead of executing`() = runTest(dispatcher) {
        val intent = AppIntent.SetAlarm(null)
        viewModel.onIntentParsed(NlpProcessor.OnIntentParsed(intent, true, 0.4f))
        advanceUntilIdle()

        verifyBlocking(mockTtsController) {
            speak(eq("What time would you like to set the alarm for?"), any())
        }
        verifyBlocking(mockAlarmExecutor, never()) { execute(mockContext, intent) }
        assertEquals(AssistantUiState.Idle, viewModel.uiState.value)
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
}
