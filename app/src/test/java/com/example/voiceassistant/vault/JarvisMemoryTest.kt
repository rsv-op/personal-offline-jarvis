package com.example.voiceassistant.vault

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JarvisMemoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var memory: JarvisMemory
    private lateinit var vaultDirectory: File

    @Before
    fun setUp() {
        vaultDirectory = File(temporaryFolder.root, "vault")
        val vault = SecureVault(vaultDirectory, SoftwareVaultCipher())
        memory = JarvisMemory(vault).apply { clock = { FIXED_TIME } }
    }

    @Test
    fun `taught phrases round trip through the encrypted vault`() = runTest {
        memory.teach("make it bright", "FLASHLIGHT_ON")

        val examples = memory.examples()
        assertEquals(1, examples.size)
        assertEquals("make it bright", examples.single().phrase)
        assertEquals("FLASHLIGHT_ON", examples.single().label)
        assertEquals(FIXED_TIME, examples.single().taughtAt)
    }

    @Test
    fun `teaching the same phrase again replaces it`() = runTest {
        memory.teach("make it bright", "FLASHLIGHT_ON")
        memory.teach("Make It Bright", "FLASHLIGHT_OFF")

        val examples = memory.examples()
        assertEquals(1, examples.size)
        assertEquals("FLASHLIGHT_OFF", examples.single().label)
    }

    @Test
    fun `labels are normalised to upper case`() = runTest {
        memory.teach("dim it", "flashlight_off")
        assertEquals("FLASHLIGHT_OFF", memory.examples().single().label)
    }

    @Test
    fun `empty phrases are rejected`() = runTest {
        val failure = runCatching { memory.teach("   ", "FLASHLIGHT_ON") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(memory.examples().isEmpty())
    }

    @Test
    fun `forgetting removes a single phrase`() = runTest {
        memory.teach("make it bright", "FLASHLIGHT_ON")
        memory.teach("kill the light", "FLASHLIGHT_OFF")

        assertTrue(memory.forget("make it bright"))
        assertFalse(memory.forget("make it bright"))
        assertEquals(listOf("kill the light"), memory.examples().map { it.phrase })
    }

    @Test
    fun `forgetting everything clears the taught phrases`() = runTest {
        memory.teach("make it bright", "FLASHLIGHT_ON")
        memory.forgetAll()
        assertTrue(memory.examples().isEmpty())
    }

    @Test
    fun `preferences are remembered`() = runTest {
        memory.remember("wakeWord", "jarvis")
        assertEquals("jarvis", memory.recall("wakeWord"))
        assertNull(memory.recall("missing"))
        assertEquals(mapOf("wakeWord" to "jarvis"), memory.preferences())
    }

    @Test
    fun `the action log is newest first and bounded`() = runTest {
        repeat(JarvisMemory.MAX_LOGGED_ACTIONS + 10) { index ->
            memory.logAction("ToggleFlashlight", "utterance $index", "done")
        }

        val actions = memory.recentActions(limit = JarvisMemory.MAX_LOGGED_ACTIONS)
        assertEquals(JarvisMemory.MAX_LOGGED_ACTIONS, actions.size)
        assertEquals("utterance ${JarvisMemory.MAX_LOGGED_ACTIONS + 9}", actions.first().utterance)
        assertEquals(JarvisMemory.MAX_LOGGED_ACTIONS, memory.summary().loggedActions)
    }

    @Test
    fun `everything is persisted on disk encrypted`() = runTest {
        memory.teach("make it bright", "FLASHLIGHT_ON")
        memory.remember("name", "Ravi")

        val stored = File(vaultDirectory, "${JarvisMemory.DOCUMENT}.enc").readBytes()
            .toString(Charsets.ISO_8859_1)
        assertFalse("Taught phrases must not be stored in plaintext", stored.contains("make it bright"))

        // A second instance over the same directory sees the same encrypted memory.
        val reopened = JarvisMemory(SecureVault(vaultDirectory, SoftwareVaultCipher()))
        assertEquals(1, reopened.examples().size)
        assertEquals("Ravi", reopened.recall("name"))
    }

    @Test
    fun `summary reports what the UI shows`() = runTest {
        memory.teach("make it bright", "FLASHLIGHT_ON")
        memory.remember("name", "Ravi")
        memory.logAction("StatusReport", "how is the battery", "Battery is 80 percent.")

        val summary = memory.summary()
        assertEquals(1, summary.taughtExamples)
        assertEquals(1, summary.preferences)
        assertEquals(1, summary.loggedActions)
        assertTrue(summary.backend.isNotEmpty())
    }

    private companion object {
        const val FIXED_TIME = 1_700_000_000_000L
    }
}
