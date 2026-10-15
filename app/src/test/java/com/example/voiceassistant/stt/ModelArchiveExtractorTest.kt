package com.example.voiceassistant.stt

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
