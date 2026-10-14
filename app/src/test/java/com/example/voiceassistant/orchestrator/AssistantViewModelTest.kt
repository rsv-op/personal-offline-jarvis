package com.example.voiceassistant.orchestrator

import android.content.Context
import com.example.voiceassistant.execution.AlarmExecutor
import com.example.voiceassistant.execution.FlashlightExecutor
import com.example.voiceassistant.execution.TtsController
import com.example.voiceassistant.execution.TtsEvent
import com.example.voiceassistant.nlp.AppIntent
import com.example.voiceassistant.nlp.NlpProcessor
import com.example.voiceassistant.statemachine.AssistantUiState
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
        doReturn(MutableSharedFlow<TtsEvent>()).whenever(mockTtsController).events

        viewModel = AssistantViewModel(
            context = mockContext,
            ttsController = mockTtsController,
            flashlightExecutor = mockFlashlightExecutor,
            alarmExecutor = mockAlarmExecutor
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
    fun `confirmation request stores intent and speaks prompt`() = runTest(dispatcher) {
        val intent = AppIntent.SetAlarm(null)
        viewModel.onIntentParsed(NlpProcessor.OnIntentParsed(intent, true, 0.4f))
        advanceUntilIdle()

        verifyBlocking(mockTtsController) {
            speak("Do you want me to proceed with this action?", any())
        }
        verifyBlocking(mockAlarmExecutor, never()) { execute(mockContext, intent) }
        assertEquals(AssistantUiState.NeedsConfirmation, viewModel.uiState.value)
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
