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
 * Both validate that the result really is a Vosk model (`conf/model.conf`) and install
 * it atomically, keeping the previous model until the new one is complete.
 */
@Singleton
class VoskModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDirectory = File(context.filesDir, "models")
    private val installedDirectory = File(modelsDirectory, "vosk-model")
    private val stagingDirectory = File(modelsDirectory, "vosk-import-staging")

    fun installedModelPath(): String? =
        installedDirectory.takeIf(::isVoskModelDirectory)?.absolutePath

    /** Human-readable size of the installed model, or `null` when none is installed. */
    fun installedModelSizeMb(): Double? =
        installedDirectory.takeIf(::isVoskModelDirectory)
            ?.let { directory -> directory.walkTopDown().filter(File::isFile).sumOf(File::length) }
            ?.let { bytes -> bytes / (1024.0 * 1024.0) }

    /** Imports a `.zip` (or `.tar`-less archive) downloaded from the Vosk model catalog. */
    suspend fun importModelArchive(uri: Uri): String = withContext(Dispatchers.IO) {
        requireArchiveName(uri)
        prepareStaging()
        try {
            val budget = CopyBudget(MAX_MODEL_BYTES)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Could not read the selected archive")
            input.use { stream ->
                ZipInputStream(stream.buffered()).use { zip ->
                    extractArchive(zip, stagingDirectory, budget)
                }
            }
            val modelRoot = findModelRoot(stagingDirectory)
                ?: throw IllegalArgumentException(
                    "No Vosk model found in that archive. Expected a folder containing conf/model.conf."
                )
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
            val budget = CopyBudget(MAX_MODEL_BYTES)
            copyChildren(source, stagingDirectory, budget)
            val modelRoot = findModelRoot(stagingDirectory)
                ?: throw IllegalArgumentException(
                    "No Vosk model found. Select a folder containing conf/model.conf."
                )
            install(modelRoot)
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ install

    private fun install(modelRoot: File) {
        val replacement = File(modelsDirectory, "vosk-model-new")
        replacement.deleteRecursively()
        modelRoot.copyRecursively(replacement, overwrite = true)
        check(isVoskModelDirectory(replacement)) { "The selected folder is not a valid Vosk model" }

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
    }

    private fun prepareStaging() {
        check(modelsDirectory.exists() || modelsDirectory.mkdirs()) {
            "Could not create private model storage"
        }
        stagingDirectory.deleteRecursively()
        check(stagingDirectory.mkdirs()) { "Could not create the model import staging folder" }
    }

    // -------------------------------------------------------------------- zip

    private fun extractArchive(zip: ZipInputStream, destination: File, budget: CopyBudget) {
        var entryCount = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                error("Archive contains too many files to be a speech model")
            }
            val name = entry.name.replace('\\', '/').trim()
            zip.closeEntry()

            if (name.isEmpty() || name.endsWith("/")) {
                if (name.isNotEmpty()) {
                    resolveSafely(destination, name)?.mkdirs()
                }
                continue
            }
            val target = resolveSafely(destination, name)
                ?: throw IllegalArgumentException("Archive entry escapes the model folder: $name")
            target.parentFile?.mkdirs()
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    budget.add(read)
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    /** Resolves a zip entry inside `destination`, rejecting absolute paths and `..` escapes. */
    private fun resolveSafely(destination: File, entryName: String): File? {
        if (entryName.startsWith("/") || entryName.contains(":")) return null
        if (entryName.contains('\u0000')) return null
        val target = File(destination, entryName)
        val canonicalDestination = destination.canonicalPath + File.separator
        return try {
            if (target.canonicalPath.startsWith(canonicalDestination)) target else null
        } catch (_: java.io.IOException) {
            null
        }
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

    private fun copyChildren(source: DocumentFile, destination: File, budget: CopyBudget) {
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

    private fun copyLimited(input: InputStream, destination: File, budget: CopyBudget) {
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

    private fun findModelRoot(start: File): File? {
        if (isVoskModelDirectory(start)) return start
        return start.walkTopDown()
            .maxDepth(MAX_SEARCH_DEPTH)
            .firstOrNull(::isVoskModelDirectory)
    }

    private fun isVoskModelDirectory(directory: File): Boolean =
        directory.isDirectory && File(directory, "conf/model.conf").isFile

    private class CopyBudget(private val limitBytes: Long) {
        private var copiedBytes = 0L

        fun add(bytes: Int) {
            copiedBytes += bytes
            require(copiedBytes <= limitBytes) {
                "Model exceeds the ${limitBytes / (1024 * 1024)} MB import limit"
            }
        }
    }

    private companion object {
        const val MAX_MODEL_BYTES = 2_000_000_000L
        const val MAX_SEARCH_DEPTH = 6
        const val MAX_ARCHIVE_ENTRIES = 5_000
    }
}
