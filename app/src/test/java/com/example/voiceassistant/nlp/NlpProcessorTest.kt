package com.example.voiceassistant.nlp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NlpProcessorTest {
    private val processor = NlpProcessor()

    @Test
    fun `please turn on flashlight routes to ToggleFlashlight`() = runTest {
        val result = processor.process("uh please turn on the flashlight")
        assertTrue(result.intent is AppIntent.ToggleFlashlight)
        assertTrue((result.intent as AppIntent.ToggleFlashlight).isOn)
        assertFalse(result.needsConfirmation)
    }

    @Test
    fun `alarm without time requires confirmation`() = runTest {
        val result = processor.process("set alarm")
        assertEquals(AppIntent.SetAlarm(null), result.intent)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `spoken time is normalized into alarm time`() = runTest {
        val result = processor.process("wake me up at six thirty am")
        assertEquals(AppIntent.SetAlarm("06:30 am"), result.intent)
        assertFalse(result.needsConfirmation)
    }

    @Test
    fun `out of domain request becomes UnknownIntent without confirmation`() = runTest {
        val result = processor.process("what is the capital of France")
        assertEquals(AppIntent.UnknownIntent, result.intent)
        assertFalse(result.needsConfirmation)
    }
}
