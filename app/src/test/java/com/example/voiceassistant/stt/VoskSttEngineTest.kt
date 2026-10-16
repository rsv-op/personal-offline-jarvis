package com.example.voiceassistant.stt

import com.example.voiceassistant.memory.DeviceMemory
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

    // The memory check is a separate layer and these tests don't exercise it; give the
    // engine enough headroom to focus on the content-validation paths.
    private val engine = VoskSttEngine(object : DeviceMemory {
        override val totalMemoryBytes: Long = 8L * 1024 * 1024 * 1024
        override val availableMemoryBytes: Long = 8L * 1024 * 1024 * 1024
    })

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

    @Test
    fun `a usable model on a device with no free RAM is refused with the memory reason`() {
        // 1 MB of free RAM is not enough to load any real model, so the memory check fires
        // before the native loader. The content checks all pass; the engine must surface
        // the memory budget's reason.
        val tightEngine = VoskSttEngine(object : DeviceMemory {
            override val totalMemoryBytes: Long = 8L * 1024 * 1024 * 1024
            override val availableMemoryBytes: Long = 1024L * 1024
        })
        val directory = modelDirectory()
        File(directory, "conf").mkdirs()
        File(directory, "conf/model.conf").writeText("[model]\nname=tiny\n")
        // A 2 MB payload (well below the 1 MB floor? actually the MIN_MODEL_BYTES is 1 MiB,
        // so 2 MiB passes; the memory check is what should fire).
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        File(directory, "am/final.mdl").writeBytes(payload)

        val failure = runCatching { tightEngine.initialize(directory.absolutePath) }.exceptionOrNull()
        assertNotNull("a 2 MB model on 1 MB free should be refused", failure)
        assertTrue(
            "failure must name the memory check, was: ${failure?.message}",
            failure!!.message.orEmpty().contains("free RAM")
        )
    }
}
