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
 * The engine's [initialize] path is the last gate before the native
 * loader. It must reject a model whose *contents* are broken — the
 * shape an empty import produces — with a message that names the cause,
 * and it must do that before any native code runs.
 *
 * Every case here fails the precondition, so no native library is
 * loaded and the whole class runs on the JVM. The native loading
 * itself is exercised on a real device, where ARM64 + onnxruntime are
 * available; running it on the JVM (x86_64 Linux without
 * libsherpa-onnx) would fail for the wrong reason.
 *
 * The Vosk-era equivalent of this class was
 * `VoskSttEngineTest`. The pre-native-content-check pattern is
 * identical; the validator that surfaces the reason is now
 * [SherpaOnnxModelValidator] instead of the Vosk conf/model.conf
 * check.
 */
class SherpaOnnxSttEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val engine = SherpaOnnxSttEngine(object : DeviceMemory {
        override val totalMemoryBytes: Long = 8L * 1024 * 1024 * 1024
        override val availableMemoryBytes: Long = 8L * 1024 * 1024 * 1024
    })

    private fun modelDirectory(name: String = "sherpa-model"): File =
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
    fun `a model without tokens dot txt is refused`() {
        val directory = modelDirectory()
        File(directory, "encoder-epoch-99-avg-1.int8.onnx")
            .writeBytes(ByteArray(1_200_000) { 0 })

        val message = initializeExpectingFailure(directory.absolutePath)

        assertTrue(message, message.contains("tokens.txt is missing"))
    }

    @Test
    fun `an empty tokens dot txt is refused`() {
        val directory = modelDirectory()
        File(directory, "tokens.txt").createNewFile()
        assertEquals(0L, File(directory, "tokens.txt").length())

        val message = initializeExpectingFailure(directory.absolutePath)

        assertTrue(message, message.contains("tokens.txt is empty"))
    }

    @Test
    fun `a refused model leaves the engine uninitialized`() {
        val directory = modelDirectory()
        File(directory, "tokens.txt").createNewFile()

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
        // 1 MB of free RAM is not enough to load any real model, so
        // the memory check fires before the native loader. The content
        // checks all pass; the engine must surface the memory budget's
        // reason.
        val tightEngine = SherpaOnnxSttEngine(object : DeviceMemory {
            override val totalMemoryBytes: Long = 8L * 1024 * 1024 * 1024
            override val availableMemoryBytes: Long = 1024L * 1024
        })
        val directory = modelDirectory()
        File(directory, "tokens.txt").writeText("<s> </s>\n")
        // 2 MiB payload — passes the 1 MiB MIN_MODEL_BYTES floor, so the
        // memory check is what fires here, not the content check.
        val payload = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        File(directory, "encoder-epoch-99-avg-1.int8.onnx").writeBytes(payload)
        File(directory, "decoder-epoch-99-avg-1.int8.onnx").writeBytes(payload)
        File(directory, "joiner-epoch-99-avg-1.int8.onnx").writeBytes(payload)

        val failure = runCatching { tightEngine.initialize(directory.absolutePath) }
            .exceptionOrNull()
        assertNotNull("a 6 MB model on 1 MB free should be refused", failure)
        assertTrue(
            "failure must name the memory check, was: ${failure?.message}",
            failure!!.message.orEmpty().contains("free RAM")
        )
    }
}
