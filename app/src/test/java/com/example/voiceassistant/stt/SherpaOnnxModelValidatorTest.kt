package com.example.voiceassistant.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Content-validation rules for a sherpa-onnx Streaming Zipformer model.
 *
 * A valid model directory has:
 *   * `tokens.txt` — non-empty
 *   * `encoder-*.onnx` — present
 *   * `decoder-*.onnx` — present
 *   * `joiner-*.onnx`  — present
 *   * total on-disk size >= 1 MB
 *
 * These rules are the second line of defence after the user picks an
 * archive. The first line is the [ModelArchiveExtractor] extraction
 * itself (no empty files, no zip-slip); the second is this class, run
 * before the native loader is touched. The native loader is the last
 * line of defence, and a crash there is the worst possible failure
 * mode — it would tell the user "could not initialise the offline
 * speech model" with no path forward.
 */
class SherpaOnnxModelValidatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun modelDirectory(): File = temporaryFolder.newFolder("sherpa-model")

    private fun tokens(directory: File, contents: String = "<s> </s>\n"): File {
        File(directory, "tokens.txt").writeText(contents)
        return File(directory, "tokens.txt")
    }

    private fun onnx(directory: File, prefix: String, size: Int = 1_200_000): File {
        val file = File(directory, "$prefix-epoch-99-avg-1.int8.onnx")
        file.writeBytes(ByteArray(size) { (it % 251).toByte() })
        return file
    }

    private fun describe(path: String): String? {
        val failure = runCatching { SherpaOnnxModelValidator.describeModelProblem(File(path)) }
            .exceptionOrNull()
        return failure?.message
    }

    @Test
    fun `a missing directory is refused`() {
        val message = SherpaOnnxModelValidator.describeModelProblem(
            File(temporaryFolder.root, "not-there")
        )
        assertNotNull(message)
        assertTrue(message!!.contains("model folder does not exist"))
    }

    @Test
    fun `a model without tokens dot txt is refused`() {
        val directory = modelDirectory()
        File(directory, "encoder-epoch-99-avg-1.int8.onnx")
            .writeBytes(ByteArray(1_200_000) { 0 })

        val message = SherpaOnnxModelValidator.describeModelProblem(directory)

        assertNotNull(message)
        assertTrue(message!!.contains("tokens.txt is missing"))
    }

    @Test
    fun `an empty tokens dot txt is refused`() {
        val directory = modelDirectory()
        tokens(directory, contents = "")

        val message = SherpaOnnxModelValidator.describeModelProblem(directory)

        assertNotNull(message)
        assertTrue(message!!.contains("tokens.txt is empty"))
    }

    @Test
    fun `a model without the encoder graph is refused`() {
        val directory = modelDirectory()
        tokens(directory)
        onnx(directory, "decoder", 1_200_000)
        onnx(directory, "joiner", 1_200_000)

        val message = SherpaOnnxModelValidator.describeModelProblem(directory)

        assertNotNull(message)
        assertTrue(message!!.contains("encoder-*.onnx is missing"))
    }

    @Test
    fun `a model without the decoder graph is refused`() {
        val directory = modelDirectory()
        tokens(directory)
        onnx(directory, "encoder", 1_200_000)
        onnx(directory, "joiner", 1_200_000)

        val message = SherpaOnnxModelValidator.describeModelProblem(directory)

        assertNotNull(message)
        assertTrue(message!!.contains("decoder-*.onnx is missing"))
    }

    @Test
    fun `a model without the joiner graph is refused`() {
        val directory = modelDirectory()
        tokens(directory)
        onnx(directory, "encoder", 1_200_000)
        onnx(directory, "decoder", 1_200_000)

        val message = SherpaOnnxModelValidator.describeModelProblem(directory)

        assertNotNull(message)
        assertTrue(message!!.contains("joiner-*.onnx is missing"))
    }

    @Test
    fun `a model with empty payload is refused before the native loader runs`() {
        val directory = modelDirectory()
        tokens(directory)
        onnx(directory, "encoder", 0)
        onnx(directory, "decoder", 0)
        onnx(directory, "joiner", 0)

        val message = SherpaOnnxModelValidator.describeModelProblem(directory)

        assertNotNull(message)
        assertTrue(message!!.contains("0 bytes of data") || message.contains("only "))
    }

    @Test
    fun `a usable model is accepted`() {
        val directory = modelDirectory()
        tokens(directory)
        onnx(directory, "encoder", 1_200_000)
        onnx(directory, "decoder", 1_200_000)
        onnx(directory, "joiner", 1_200_000)

        assertNull(SherpaOnnxModelValidator.describeModelProblem(directory))
        assertTrue(SherpaOnnxModelValidator.isModelDirectory(directory))
    }

    @Test
    fun `findOnnx matches both int8 and fp32 encoder names`() {
        val directory = modelDirectory()
        // fp32-style filename
        val fp32 = File(directory, "encoder-epoch-99-avg-1.onnx")
        fp32.writeBytes(ByteArray(100) { 0 })

        assertEquals(fp32, SherpaOnnxModelValidator.findOnnx(directory, "encoder"))
    }

    @Test
    fun `model size sums every file recursively`() {
        val directory = modelDirectory()
        tokens(directory)
        onnx(directory, "encoder", 1_000_000)
        onnx(directory, "decoder", 1_000_000)
        onnx(directory, "joiner", 1_000_000)
        // tokens.txt is ~10 bytes; total ~3 MB.

        val bytes = SherpaOnnxModelValidator.modelSizeBytes(directory)
        assertTrue(
            "model size should include the .onnx files and tokens.txt, was $bytes",
            bytes >= 3_000_000
        )
    }

    @Test
    fun `rejection message names the missing shape when no onnx files exist`() {
        val directory = temporaryFolder.newFolder("empty")
        val message = SherpaOnnxModelValidator.describeRejection(directory)
        // Empty tree: the validator should fall through to the "Nothing was extracted" case
        // (the store treats an empty staging directory the same way regardless of
        // recogniser — it means the archive had nothing inside).
        assertTrue(message, message.contains("Nothing was extracted"))
    }
}
