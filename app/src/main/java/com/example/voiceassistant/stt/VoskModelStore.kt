package com.example.voiceassistant.stt

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a user-selected Vosk model into private app storage for fully offline use.
 *
 * Two import paths, because models are large and phones are awkward:
 *
 *  * [importModelArchive] — a `.zip` straight from the Vosk download page. This is the
 *    recommended path: Android's Files app cannot unzip, so the app does it (safely,
 *    with zip-slip protection and a size budget).
 *  * [importModelTree] — an already extracted folder, via the folder picker.
 *
 * Both validate that the result really is a *usable* Vosk model — a non-empty
 * `conf/model.conf` plus a real payload, never just the presence of a file name that an
 * empty extraction also produces — and install it atomically, keeping the previous model
 * until the new one is complete.
 */
@Singleton
class VoskModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDirectory = File(context.filesDir, "models")
    private val installedDirectory = File(modelsDirectory, "vosk-model")
    private val stagingDirectory = File(modelsDirectory, "vosk-import-staging")

    fun installedModelPath(): String? =
        installedDirectory.takeIf(ModelArchiveExtractor::isModelDirectory)?.absolutePath

    /** Human-readable size of the installed model, or `null` when none is installed. */
    fun installedModelSizeMb(): Double? =
        installedDirectory.takeIf(ModelArchiveExtractor::isModelDirectory)
            ?.let(ModelArchiveExtractor::modelSizeBytes)
            ?.let { bytes -> bytes / (1024.0 * 1024.0) }

    /** Imports a `.zip` (or `.tar`-less archive) downloaded from the Vosk model catalog. */
    suspend fun importModelArchive(uri: Uri): String = withContext(Dispatchers.IO) {
        requireArchiveName(uri)
        prepareStaging()
        try {
            val budget = ModelArchiveExtractor.Budget()
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Could not read the selected archive")
            input.use { stream ->
                ZipInputStream(stream.buffered()).use { zip ->
                    ModelArchiveExtractor.extract(zip, stagingDirectory, budget)
                }
            }
            val modelRoot = ModelArchiveExtractor.findModelRoot(stagingDirectory)
                ?: throw IllegalArgumentException(ModelArchiveExtractor.describeRejection(stagingDirectory))
            install(modelRoot)
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    /** Imports an extracted model folder (or a parent folder containing one). */
    suspend fun importModelTree(treeUri: Uri): String = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Unable to open the selected model folder")
        require(source.isDirectory) { "Select the extracted Vosk model folder" }

        prepareStaging()
        try {
            val budget = ModelArchiveExtractor.Budget()
            copyChildren(source, stagingDirectory, budget)
            val modelRoot = ModelArchiveExtractor.findModelRoot(stagingDirectory)
                ?: throw IllegalArgumentException(ModelArchiveExtractor.describeRejection(stagingDirectory))
            install(modelRoot)
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ install

    private fun install(modelRoot: File): String {
        val replacement = File(modelsDirectory, "vosk-model-new")
        replacement.deleteRecursively()
        modelRoot.copyRecursively(replacement, overwrite = true)
        check(ModelArchiveExtractor.isModelDirectory(replacement)) {
            "The selected model is incomplete: conf/model.conf is missing or empty, or the " +
                "model contains no data."
        }

        val backup = File(modelsDirectory, "vosk-model-old")
        backup.deleteRecursively()
        if (installedDirectory.exists()) {
            check(installedDirectory.renameTo(backup)) { "Could not replace the current model" }
        }
        if (!replacement.renameTo(installedDirectory)) {
            backup.renameTo(installedDirectory)
            error("Could not install the selected model")
        }
        backup.deleteRecursively()
        return installedDirectory.absolutePath
    }

    private fun prepareStaging() {
        check(modelsDirectory.exists() || modelsDirectory.mkdirs()) {
            "Could not create private model storage"
        }
        stagingDirectory.deleteRecursively()
        check(stagingDirectory.mkdirs()) { "Could not create the model import staging folder" }
    }

    private fun requireArchiveName(uri: Uri) {
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        // Some pickers report no name at all; Vosk archives end in .zip, but we accept
        // anything the user pointed at and validate the contents instead.
        if (displayName != null) {
            val lower = displayName.lowercase()
            require(lower.endsWith(".zip")) {
                "Choose the .zip file you downloaded from the Vosk model catalog"
            }
        }
    }

    // --------------------------------------------------------------- documents

    private fun copyChildren(
        source: DocumentFile,
        destination: File,
        budget: ModelArchiveExtractor.Budget
    ) {
        for (child in source.listFiles()) {
            val name = child.name ?: continue
            require(name != "." && name != ".." && '/' !in name && '\\' !in name) {
                "Invalid model file name"
            }
            val target = File(destination, name)
            if (child.isDirectory) {
                check(target.exists() || target.mkdirs()) { "Could not create $name" }
                copyChildren(child, target, budget)
            } else if (child.isFile) {
                val input = context.contentResolver.openInputStream(child.uri)
                    ?: throw IllegalArgumentException("Could not read model file: $name")
                input.use { stream -> copyLimited(stream, target, budget) }
            }
        }
    }

    private fun copyLimited(
        input: InputStream,
        destination: File,
        budget: ModelArchiveExtractor.Budget
    ) {
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                budget.add(read)
                output.write(buffer, 0, read)
            }
        }
    }

}
