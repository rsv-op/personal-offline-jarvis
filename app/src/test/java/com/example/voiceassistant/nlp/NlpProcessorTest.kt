package com.example.voiceassistant.nlp

import com.example.voiceassistant.aimatrix.AiMatrixClassifier
import com.example.voiceassistant.aimatrix.IntentModelSource
import com.example.voiceassistant.aimatrix.OnDeviceBrain
import com.example.voiceassistant.TestFiles
import com.example.voiceassistant.vault.JarvisMemory
import com.example.voiceassistant.vault.SecureVault
import com.example.voiceassistant.vault.SoftwareVaultCipher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NlpProcessorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val processor = NlpProcessor()

    private fun brain(): OnDeviceBrain {
        val vault = SecureVault(File(temporaryFolder.root, "vault"), SoftwareVaultCipher())
        val classifier = AiMatrixClassifier.fromJson(TestFiles.aiMatrixAsset().readText())
        val source = object : IntentModelSource {
            override val lastError: String? = null
            override suspend fun classifier(): AiMatrixClassifier = classifier
        }
        return OnDeviceBrain(source, JarvisMemory(vault))
    }

    // ------------------------------------------------------------- rule stage

    @Test
    fun `please turn on flashlight routes to ToggleFlashlight`() = runTest {
        val result = processor.process("uh please turn on the flashlight")
        assertTrue(result.intent is AppIntent.ToggleFlashlight)
        assertTrue((result.intent as AppIntent.ToggleFlashlight).isOn)
        assertTrue(result.needsConfirmation)
        assertEquals(NlpProcessor.Source.RULES, result.source)
    }

    @Test
    fun `turning the flashlight off is detected`() = runTest {
        val result = processor.process("turn the flashlight off")
        assertEquals(AppIntent.ToggleFlashlight(false), result.intent)
    }

    @Test
    fun `an ambiguous flashlight phrase falls through to the brain`() = runTest {
        // No on/off word: the rules refuse to guess.
        assertNull(IntentRouter().polarity(TextNormalizer().normalize("flashlight is not needed anymore")))

        val withBrain = NlpProcessor(brain = brain())
        val result = withBrain.process("flashlight is not needed anymore")
        assertEquals(AppIntent.ToggleFlashlight(false), result.intent)
        assertTrue(result.needsConfirmation)
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
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `timer requests are parsed into seconds`() = runTest {
        val result = processor.process("set a timer for ten minutes")
        assertEquals(AppIntent.SetTimer(600), result.intent)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `a timer without a duration asks for one`() = runTest {
        val result = processor.process("start a timer")
        assertEquals(AppIntent.SetTimer(null), result.intent)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `status requests are read only and skip confirmation`() = runTest {
        val result = processor.process("how much battery is left")
        assertEquals(AppIntent.StatusReport, result.intent)
        assertFalse(result.needsConfirmation)
    }

    @Test
    fun `delete requests are high sensitivity`() = runTest {
        val result = processor.process("delete my notes file")
        assertTrue(result.intent is AppIntent.DeleteFile)
        assertTrue("Deletion must never run unconfirmed", result.needsConfirmation)
    }

    @Test
    fun `out of domain request becomes UnknownIntent without confirmation`() = runTest {
        val result = processor.process("what is the capital of France")
        assertEquals(AppIntent.UnknownIntent, result.intent)
        assertFalse(result.needsConfirmation)
    }

    // ------------------------------------------------------------ brain stage

    @Test
    fun `the brain answers phrasing the rules do not cover`() = runTest {
        val withBrain = NlpProcessor(brain = brain())
        val result = withBrain.process("i need some light")

        assertTrue(result.intent is AppIntent.ToggleFlashlight)
        assertEquals(NlpProcessor.Source.BRAIN, result.source)
        assertTrue("Brain suggestions are always confirmed", result.needsConfirmation)
    }

    @Test
    fun `the brain extracts parameters deterministically`() = runTest {
        val withBrain = NlpProcessor(brain = brain())
        withBrain.brain!!.teach("wake me at quarter past", "ALARM_SET")

        val result = withBrain.process("wake me at quarter past")
        assertTrue(result.intent is AppIntent.SetAlarm)
        // "quarter past" carries no usable hour, so the assistant still has to ask.
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `taught phrases beat the network`() = runTest {
        val withBrain = NlpProcessor(brain = brain())
        withBrain.brain!!.teach("brew some coffee", "TIMER_SET")

        val result = withBrain.process("brew some coffee")
        assertEquals(AppIntent.SetTimer(null), result.intent)
        assertEquals(NlpProcessor.Source.TAUGHT, result.source)
    }

    @Test
    fun `label to intent mapping is exhaustive for the trained labels`() = runTest {
        assertNull(processor.toIntent("NOT_A_LABEL", "whatever"))
        assertEquals(AppIntent.StatusReport, processor.toIntent("DEVICE_STATUS", "device status"))
        assertEquals(
            AppIntent.ToggleFlashlight(true),
            processor.toIntent("FLASHLIGHT_ON", "light please")
        )
        val alarm = processor.toIntent("ALARM_SET", "set an alarm for seven am")
        assertEquals(AppIntent.SetAlarm("07:00 am"), alarm)
    }
}
