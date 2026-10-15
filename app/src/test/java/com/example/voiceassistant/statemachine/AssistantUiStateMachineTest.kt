package com.example.voiceassistant.statemachine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantUiStateMachineTest {

    private lateinit var machine: AssistantUiStateMachine
    private val invalidTransitions = mutableListOf<Pair<AssistantUiState, AssistantEvent>>()

    private fun newMachine(): AssistantUiStateMachine {
        invalidTransitions.clear()
        machine = AssistantUiStateMachine { state, event -> invalidTransitions += state to event }
        return machine
    }

    @Test
    fun `a full voice loop walks through every state`() {
        newMachine()
        assertEquals(AssistantUiState.Idle, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnWakeWordDetected)
        assertEquals(AssistantUiState.WakeDetected, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnSpeechStarted)
        assertEquals(AssistantUiState.Listening, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnSpeechEnded)
        assertEquals(AssistantUiState.Processing, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnIntentParsed(needsConfirmation = true))
        assertEquals(AssistantUiState.NeedsConfirmation, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnConfirmationReceived)
        assertEquals(AssistantUiState.Executing, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnExecutionCompleted)
        assertEquals(AssistantUiState.Speaking, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnSpeechSynthesisCompleted)
        assertEquals(AssistantUiState.ReturnToIdle, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnCleanupComplete)
        assertEquals(AssistantUiState.Idle, machine.uiState.value)
    }

    @Test
    fun `denying a confirmation never reaches execution`() {
        newMachine()
        machine.processEvent(AssistantEvent.OnWakeWordDetected)
        machine.processEvent(AssistantEvent.OnSpeechStarted)
        machine.processEvent(AssistantEvent.OnSpeechEnded)
        machine.processEvent(AssistantEvent.OnIntentParsed(needsConfirmation = true))

        machine.processEvent(AssistantEvent.OnConfirmationDenied)
        assertEquals(AssistantUiState.ReturnToIdle, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnCleanupComplete)
        assertEquals(AssistantUiState.Idle, machine.uiState.value)
    }

    @Test
    fun `a read-only intent skips confirmation`() {
        newMachine()
        machine.processEvent(AssistantEvent.OnWakeWordDetected)
        machine.processEvent(AssistantEvent.OnSpeechStarted)
        machine.processEvent(AssistantEvent.OnSpeechEnded)
        machine.processEvent(AssistantEvent.OnIntentParsed(needsConfirmation = false))
        assertEquals(AssistantUiState.Executing, machine.uiState.value)
    }

    @Test
    fun `errors always unwind to a safe state`() {
        newMachine()
        machine.processEvent(AssistantEvent.OnWakeWordDetected)
        machine.processEvent(AssistantEvent.OnSpeechStarted)

        machine.processEvent(AssistantEvent.OnError)
        assertEquals(AssistantUiState.ReturnToIdle, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnCleanupComplete)
        assertEquals(AssistantUiState.Idle, machine.uiState.value)
    }

    @Test
    fun `an error while idle changes nothing`() {
        newMachine()
        machine.processEvent(AssistantEvent.OnError)
        assertEquals(AssistantUiState.Idle, machine.uiState.value)
        machine.processEvent(AssistantEvent.OnTimeout)
        assertEquals(AssistantUiState.Idle, machine.uiState.value)
    }

    @Test
    fun `out of order events are reported instead of thrown`() {
        newMachine()
        // Synthesis finished but nothing was spoken: a race, not a crash.
        machine.processEvent(AssistantEvent.OnSpeechSynthesisCompleted)
        assertEquals(AssistantUiState.Idle, machine.uiState.value)
        assertEquals(1, invalidTransitions.size)

        // Execution cannot start without a parsed intent.
        machine.processEvent(AssistantEvent.OnExecutionCompleted)
        assertEquals(AssistantUiState.Idle, machine.uiState.value)
        assertEquals(2, invalidTransitions.size)

        // The assistant still works afterwards.
        machine.processEvent(AssistantEvent.OnWakeWordDetected)
        assertEquals(AssistantUiState.WakeDetected, machine.uiState.value)
    }

    @Test
    fun `a confirmation can be answered out loud`() {
        newMachine()
        machine.processEvent(AssistantEvent.OnWakeWordDetected)
        machine.processEvent(AssistantEvent.OnSpeechStarted)
        machine.processEvent(AssistantEvent.OnSpeechEnded)
        machine.processEvent(AssistantEvent.OnIntentParsed(needsConfirmation = true))

        // Tapping the orb while the question is on screen starts another listen.
        machine.processEvent(AssistantEvent.OnWakeWordDetected)
        assertEquals(AssistantUiState.WakeDetected, machine.uiState.value)

        machine.processEvent(AssistantEvent.OnSpeechStarted)
        machine.processEvent(AssistantEvent.OnSpeechEnded)
        machine.processEvent(AssistantEvent.OnConfirmationReceived)
        assertEquals(AssistantUiState.Executing, machine.uiState.value)
    }

    @Test
    fun `reset returns to idle from anywhere`() {
        newMachine()
        machine.processEvent(AssistantEvent.OnWakeWordDetected)
        machine.processEvent(AssistantEvent.OnSpeechStarted)
        machine.reset()
        assertEquals(AssistantUiState.Idle, machine.uiState.value)
        assertTrue(invalidTransitions.isEmpty())
    }
}
