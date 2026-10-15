package com.example.voiceassistant.aimatrix

import com.example.voiceassistant.TestFiles
import com.example.voiceassistant.vault.JarvisMemory
import com.example.voiceassistant.vault.SecureVault
import com.example.voiceassistant.vault.SoftwareVaultCipher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The brain blends taught phrases with the bundled network. Both stages are exercised
 * here against the real trained asset, so this is close to what runs on the phone.
 */
class OnDeviceBrainTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var memory: JarvisMemory
    private lateinit var brain: OnDeviceBrain

    /** Supplies the real bundled model, exactly like the asset-backed store does. */
    private class FakeModelSource(private val classifier: AiMatrixClassifier?) : IntentModelSource {
        override var lastError: String? = null
        override suspend fun classifier(): AiMatrixClassifier? = classifier
    }

    private fun classifierFromAsset(): AiMatrixClassifier =
        AiMatrixClassifier.fromJson(TestFiles.aiMatrixAsset().readText())

    @Before
    fun setUp() {
        val vault = SecureVault(File(temporaryFolder.root, "vault"), SoftwareVaultCipher())
        memory = JarvisMemory(vault)
        brain = OnDeviceBrain(FakeModelSource(classifierFromAsset()), memory)
    }

    @Test
    fun `device phrases are recognised by the bundled model`() = runTest {
        val suggestion = brain.suggest("switch the torch off")
        assertNotNull(suggestion)
        assertEquals("FLASHLIGHT_OFF", suggestion!!.label)
        assertEquals(OnDeviceBrain.Source.MODEL, suggestion.source)
        assertTrue(suggestion.confidence > OnDeviceBrain.MODEL_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `unknown phrases produce no suggestion`() = runTest {
        assertNull(brain.suggest("explain how a combustion engine works"))
    }

    @Test
    fun `blank input produces no suggestion`() = runTest {
        assertNull(brain.suggest("    "))
    }

    @Test
    fun `a taught phrase wins over the model`() = runTest {
        // "make it bright" would not be routed by the network alone.
        assertNull((FakeModelSource(null)).let { OnDeviceBrain(it, memory) }.suggest("make it bright"))

        brain.teach("make it bright", "FLASHLIGHT_ON")

        val suggestion = brain.suggest("make it bright")
        assertNotNull(suggestion)
        assertEquals("FLASHLIGHT_ON", suggestion!!.label)
        assertEquals(OnDeviceBrain.Source.TAUGHT, suggestion.source)
    }

    @Test
    fun `a taught phrase also matches close rephrasings`() = runTest {
        brain.teach("make it bright", "FLASHLIGHT_ON")
        val suggestion = brain.suggest("make it bright")
        assertNotNull(suggestion)
        assertTrue(suggestion!!.confidence >= OnDeviceBrain.TAUGHT_MATCH_THRESHOLD)
    }

    @Test
    fun `taught phrases are normalised to known labels and reject nonsense`() = runTest {
        assertEquals("FLASHLIGHT_OFF", brain.teach("kill the light", "flashlight_off"))
        val failure = runCatching { brain.teach("launch the rockets", "NUKE_THE_MOON") }.exceptionOrNull()
        assertTrue("Unknown actions must be rejected", failure is IllegalArgumentException)
    }

    @Test
    fun `the brain survives a broken vault`() = runTest {
        val brokenVault = SecureVault(
            File(temporaryFolder.root, "broken"),
            SoftwareVaultCipher(),
            onError = { }
        ).apply { write("jarvis-memory", org.json.JSONObject().put("taughtExamples", "not-an-array")) }

        val resilientBrain = OnDeviceBrain(FakeModelSource(classifierFromAsset()), JarvisMemory(brokenVault))
        // A corrupt memory document must not stop the model stage from answering.
        val suggestion = resilientBrain.suggest("turn on the flashlight")
        assertNotNull(suggestion)
        assertEquals("FLASHLIGHT_ON", suggestion!!.label)
    }

    @Test
    fun `a missing model leaves the brain usable for taught phrases only`() = runTest {
        val offlineBrain = OnDeviceBrain(FakeModelSource(null), memory)
        assertNull(offlineBrain.suggest("turn on the flashlight"))
        offlineBrain.teach("make it bright", "FLASHLIGHT_ON")
        val suggestion = offlineBrain.suggest("make it bright")
        assertNotNull(suggestion)
        assertEquals("FLASHLIGHT_ON", suggestion!!.label)
    }
}
