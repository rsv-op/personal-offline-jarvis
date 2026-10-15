package com.example.voiceassistant.stt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Guards the import path that broke on a real phone: entries were closed before their
 * bytes were read, so every file landed empty and the import still "succeeded".
 */
class ModelArchiveExtractorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val modelFolderName = "vosk-model-small-en-us-0.15"

    /** Writes a zip shaped like a real Vosk download (optional folder, real payload). */
    private fun writeArchive(
        target: File,
        payloadBytes: Int = 1_200_000,
        withDirectoryEntries: Boolean = true,
        configContents: String = "[model]\nname=small-en-us\n",
        extraEntries: List<Pair<String, String>> = emptyList()
    ): File {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            if (withDirectoryEntries) {
                zip.putNextEntry(ZipEntry("$modelFolderName/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("$modelFolderName/conf/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("$modelFolderName/am/"))
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("$modelFolderName/conf/model.conf"))
            zip.write(configContents.toByteArray())
            zip.closeEntry()

            // The acoustic model carries the bulk of a real download; filler stands in for it.
            zip.putNextEntry(ZipEntry("$modelFolderName/am/final.mdl"))
            zip.write(ByteArray(payloadBytes) { (it % 251).toByte() })
            zip.closeEntry()

            for ((name, body) in extraEntries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return target
    }

    private fun extract(archive: File, destination: File, budget: ModelArchiveExtractor.Budget? = null) {
        destination.mkdirs()
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            if (budget == null) {
                ModelArchiveExtractor.extract(zip, destination)
            } else {
                ModelArchiveExtractor.extract(zip, destination, budget)
            }
        }
    }

    @Test
    fun `extraction writes the real bytes of every entry`() {
        val archive = writeArchive(File(temporaryFolder.root, "model.zip"))
        val destination = File(temporaryFolder.root, "staging")

        extract(archive, destination)

        val config = File(destination, "$modelFolderName/conf/model.conf")
        assertTrue("conf/model.conf must exist", config.isFile)
        assertTrue("conf/model.conf must not be empty", config.length() > 0L)
        assertEquals("[model]\nname=small-en-us\n", config.readText())

        val payload = File(destination, "$modelFolderName/am/final.mdl")
        assertEquals(1_200_000L, payload.length())
        assertTrue(
            "payload bytes must survive the round trip",
            ByteArray(1_200_000) { (it % 251).toByte() }.contentEquals(payload.readBytes())
        )
    }

    @Test
    fun `archives without directory entries still extract`() {
        val archive = writeArchive(
            File(temporaryFolder.root, "flat.zip"),
            withDirectoryEntries = false
        )
        val destination = File(temporaryFolder.root, "staging-flat")

        extract(archive, destination)

        assertEquals(
            1_200_000L,
            File(destination, "$modelFolderName/am/final.mdl").length()
        )
    }

    @Test
    fun `a model root is found below the archive's wrapper folder`() {
        val archive = writeArchive(File(temporaryFolder.root, "model.zip"))
        val destination = File(temporaryFolder.root, "staging-root")

        extract(archive, destination)

        val root = ModelArchiveExtractor.findModelRoot(destination)
        assertNotNull("the wrapped model folder must be discovered", root)
        assertEquals(modelFolderName, root!!.name)
        assertTrue(ModelArchiveExtractor.isModelDirectory(root))
    }

    @Test
    fun `an empty config file is not a usable model`() {
        val destination = File(temporaryFolder.root, "staging-empty-config")
        val archive = writeArchive(
            File(temporaryFolder.root, "empty-config.zip"),
            configContents = ""
        )

        extract(archive, destination)

        val root = File(destination, modelFolderName)
        assertFalse(
            "a zero-byte conf/model.conf must not validate (this is the bug that shipped)",
            ModelArchiveExtractor.isModelDirectory(root)
        )
        assertNull(ModelArchiveExtractor.findModelRoot(destination))
        assertTrue(
            ModelArchiveExtractor.describeRejection(destination).contains("empty")
        )
    }

    @Test
    fun `a truncated model is not a usable model`() {
        val destination = File(temporaryFolder.root, "staging-tiny")
        val archive = writeArchive(
            File(temporaryFolder.root, "tiny.zip"),
            payloadBytes = 2_048
        )

        extract(archive, destination)

        assertFalse(
            "a model far below the minimum size cannot be loaded by Vosk",
            ModelArchiveExtractor.isModelDirectory(File(destination, modelFolderName))
        )
        assertTrue(
            ModelArchiveExtractor.describeRejection(destination).contains("too small")
        )
    }

    @Test
    fun `entries that escape the destination are rejected`() {
        val destination = File(temporaryFolder.root, "staging-escape")
        val archive = writeArchive(
            File(temporaryFolder.root, "escape.zip"),
            extraEntries = listOf("../escaped.txt" to "nope")
        )

        val failure = runCatching { extract(archive, destination) }.exceptionOrNull()

        assertNotNull("zip-slip must be refused", failure)
        assertTrue(
            "the message should name the offending entry",
            failure!!.message.orEmpty().contains("escapes the model folder")
        )
        assertFalse(File(temporaryFolder.root, "escaped.txt").exists())
    }

    /**
     * Writes a zip with one large STORED entry (no compression) whose *uncompressed* size
     * is [modelBytes], fed from a 64 KB streaming source. This lets the test exercise the
     * extraction budget against multi-GB payloads without ever holding the full payload in
     * memory. The source is deterministic: byte at offset [i] = (i % 251) cast to Byte,
     * so the head/tail spot-checks can verify the bytes really came through the zip.
     */
    private fun writeLargeUncompressedArchive(target: File, modelBytes: Long) {
        val source = ByteArray(64 * 1024) { (it % 251).toByte() }
        // STORED requires both size and CRC32 in the local file header. CRC32 is computed
        // by streaming the source over the byte sequence the writer will produce — the
        // zip layer doesn't add or change bytes, it just frames them.
        val crc = java.util.zip.CRC32()
        var remainingForCrc = modelBytes
        while (remainingForCrc > 0) {
            val chunkSize = if (remainingForCrc >= source.size) source.size else remainingForCrc.toInt()
            crc.update(source, 0, chunkSize)
            remainingForCrc -= chunkSize
        }
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(
                ZipEntry("am/final.mdl").apply {
                    size = modelBytes
                    crc = crc.value
                    method = ZipEntry.STORED
                }
            )
            var remaining = modelBytes
            while (remaining > 0) {
                val chunk = if (remaining >= source.size) source else source.copyOf(remaining.toInt())
                zip.write(chunk)
                remaining -= chunk.size
            }
            zip.closeEntry()
        }
    }

    @Test
    fun `a model of the largest official Vosk size passes the default budget`() {
        // vosk-model-en-us-0.22-lgraph extracts to ~2.3 GB. We synthesise a 2.4 GB
        // uncompressed entry by streaming from a 64 KB source so the test does not
        // materialise 2.4 GB of bytes in heap. The default budget (3 GiB after the
        // raise in MAX_MODEL_BYTES) must let this through.
        val archive = File(temporaryFolder.root, "lgraph-2_4gb.zip")
        writeLargeUncompressedArchive(archive, modelBytes = 2_400_000_000L)
        val destination = File(temporaryFolder.root, "staging-lgraph")

        extract(archive, destination) // default budget

        val payload = File(destination, "am/final.mdl")
        assertTrue("payload should be present", payload.isFile)
        assertEquals(2_400_000_000L, payload.length())
        // Spot-check the streaming source. STORED entries are read through unchanged, so the
        // first 64 KB of the extracted file must equal the deterministic source the writer
        // fed to ZipOutputStream. Reading the full 2.4 GB to compare would defeat the point
        // of the test, so a 64 KB head is enough to prove the budget path actually wrote
        // bytes, not just created a sparse file.
        val head = payload.inputStream().use { it.readNBytes(64) }
        val expected = ByteArray(64) { it.toByte() }
        assertArrayEquals("first 64 KB must match the streaming source", expected, head)
    }

    @Test
    fun `a multi-GB model is refused when the budget is below its size`() {
        // Same writer, but a budget of 1 GB; the 2.4 GB payload must be rejected.
        val archive = File(temporaryFolder.root, "lgraph-too-big.zip")
        writeLargeUncompressedArchive(archive, modelBytes = 2_400_000_000L)
        val destination = File(temporaryFolder.root, "staging-too-big")

        val failure = runCatching {
            extract(archive, destination, ModelArchiveExtractor.Budget(limitBytes = 1L * 1024L * 1024L * 1024L))
        }.exceptionOrNull()

        assertNotNull("a 1 GB budget must refuse a 2.4 GB archive", failure)
        assertTrue(failure!!.message.orEmpty().contains("import limit"))
    }

    @Test
    fun `a model larger than the import budget is refused`() {
        val archive = writeArchive(File(temporaryFolder.root, "big.zip"), payloadBytes = 4_096)
        val destination = File(temporaryFolder.root, "staging-budget")

        val failure = runCatching {
            extract(archive, destination, ModelArchiveExtractor.Budget(limitBytes = 1_024))
        }.exceptionOrNull()

        assertNotNull("the size budget must stop oversized imports", failure)
        assertTrue(failure!!.message.orEmpty().contains("import limit"))
    }

    @Test
    fun `an archive with too many entries is refused`() {
        val many = (0 until ModelArchiveExtractor.MAX_ARCHIVE_ENTRIES + 1)
            .map { "junk/$it.txt" to "x" }
        val archive = writeArchive(
            File(temporaryFolder.root, "many.zip"),
            payloadBytes = 1_024,
            extraEntries = many
        )
        val destination = File(temporaryFolder.root, "staging-many")

        val failure = runCatching { extract(archive, destination) }.exceptionOrNull()

        assertNotNull("entry flooding must be refused", failure)
        assertTrue(failure!!.message.orEmpty().contains("too many files"))
    }
}
