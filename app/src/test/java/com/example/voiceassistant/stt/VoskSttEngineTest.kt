package com.example.voiceassistant.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The engine is the last gate before Vosk's native loader. It must reject a model whose
 * *contents* are broken — the shape an empty import produces — with a message that names the
 * cause, and it must do that before any native code runs.
 *
 * Every case here fails the precondition, so no native library is loaded and the whole class
 * runs on the JVM. That is deliberate: the bug this guards against was invisible in CI
 * because nothing ever asserted these messages.
 */
class VoskSttEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val engine = VoskSttEngine()

    private fun modelDirectory(name: String = "vosk-model"): File =
        temporaryFolder.newFolder(name)

    private fun initializeExpectingFailure(path: String): String {
        val failure = runCatching { engine.initialize(path) }.exceptionOrNull()
        assertNotNull("initialize should refuse this model", failure)
        assertTrue(
            "expected an IllegalArgumentException, got $failure",
            failure is IllegalArgumentException
        )
        return failure!!.message.orEmpty()
    }

    @Test
    fun `a missing directory is refused`() {
        val message = initializeExpectingFailure(
            File(temporaryFolder.root, "not-there").absolutePath
        )
        assertTrue(message, message.contains("model folder does not exist"))
    }

    @Test
    fun `a model without conf model conf is refused`() {
        val directory = modelDirectory()
        File(directory, "am").mkdirs()

        val message = initializeExpectingFailure(directory.absolutePath)

        assertTrue(message, message.contains("conf/model.conf is missing"))
    }

    @Test
    fun `an empty conf model conf is refused`() {
        // The exact artifact of the shipped import bug: the file exists, with zero bytes.
        val directory = modelDirectory()
        File(directory, "conf").mkdirs()
        File(directory, "conf/model.conf").createNewFile()
        assertEquals(0L, File(directory, "conf/model.conf").length())

        val message = initializeExpectingFailure(directory.absolutePath)

        assertTrue(message, message.contains("conf/model.conf is empty"))
        assertTrue(
            "the message must name the path so it is actionable from the phone",
            message.contains(directory.absolutePath)
        )
    }

    @Test
    fun `a model with no payload is refused before the native loader runs`() {
        val directory = modelDirectory()
        File(directory, "conf").mkdirs()
        File(directory, "conf/model.conf").writeText("[model]\nname=empty\n")

        val message = initializeExpectingFailure(directory.absolutePath)

        assertTrue(message, message.contains("0 bytes of data") || message.contains("only "))
    }

    @Test
    fun `a refused model leaves the engine uninitialized`() {
        val directory = modelDirectory()
        File(directory, "conf").mkdirs()
        File(directory, "conf/model.conf").createNewFile()

        initializeExpectingFailure(directory.absolutePath)

        val failure = runCatching { engine.getPartialText() }.exceptionOrNull()
        assertNotNull("no recognizer should exist after a refused model", failure)
        assertTrue(
            "transcribing with no model must fail loudly, was: $failure",
            failure!!.message.orEmpty().contains("has not been initialized")
        )
    }
}
