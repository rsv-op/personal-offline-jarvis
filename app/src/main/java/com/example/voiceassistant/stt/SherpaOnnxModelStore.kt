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
 * Copies a user-selected sherpa-onnx Streaming Zipformer model into
 * private app storage for fully offline use.
 *
 * Replaces [VoskModelStore] (PR #27). The model layout on disk is
 * different — sherpa-onnx uses flat `.onnx` files plus a `tokens.txt`
 * rather than Vosk's `conf/model.conf` + `am/final.mdl` + `graph/HCLG.fst`
 * structure — so the import paths and the validator are different too.
 *
 * Two import paths:
 *
 *  * [importModelArchive] — a `.zip` straight from a sherpa-onnx model
 *    download. The official sherpa-onnx model distribution is
 *    `.tar.bz2`, which is harder to extract on Android without a
 *    third-party library. We accept `.zip` for users who re-pack the
 *    archive; most users will use the folder path instead.
 *  * [importModelTree] — an already extracted folder, via the folder
 *    picker. **This is the recommended path**: the user downloads
 *    `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2` in a
 *    browser, extracts it on a computer, transfers the folder to the
 *    phone, and picks it.
 *
 * Both validate that the result really is a *usable* sherpa-onnx model
 * — non-empty `tokens.txt` plus all three of `encoder-*.onnx`,
 * `decoder-*.onnx`, `joiner-*.onnx`, plus the size floor — and install
 * it atomically, keeping the previous model until the new one is
 * complete.
 */
@Singleton
class SherpaOnnxModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDirectory = File(context.filesDir, "models")
    private val installedDirectory = File(modelsDirectory, "sherpa-model")
    private val stagingDirectory = File(modelsDirectory, "sherpa-import-staging")

    fun installedModelPath(): String? =
        installedDirectory.takeIf(SherpaOnnxModelValidator::isModelDirectory)?.absolutePath

    /** Human-readable size of the installed model, or `null` when none is installed. */
    fun installedModelSizeMb(): Double? =
        installedDirectory.takeIf(SherpaOnnxModelValidator::isModelDirectory)
            ?.let(SherpaOnnxModelValidator::modelSizeBytes)
            ?.let { bytes -> bytes / (1024.0 * 1024.0) }

    /** Imports a `.zip` archive containing the sherpa-onnx model files. */
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
            val modelRoot = findModelRoot(stagingDirectory)
                ?: throw IllegalArgumentException(SherpaOnnxModelValidator.describeRejection(stagingDirectory))
            install(modelRoot)
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    /**
     * Imports an extracted model folder (or a parent folder containing one).
     * This is the recommended path: the user downloads the
     * `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2` archive
     * on a computer, extracts it, and selects the resulting folder on
     * the phone.
     */
    suspend fun importModelTree(treeUri: Uri): String = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Unable to open the selected model folder")
        require(source.isDirectory) { "Select the extracted sherpa-onnx model folder" }

        prepareStaging()
        try {
            val budget = ModelArchiveExtractor.Budget()
            copyChildren(source, stagingDirectory, budget)
            val modelRoot = findModelRoot(stagingDirectory)
                ?: throw IllegalArgumentException(SherpaOnnxModelValidator.describeRejection(stagingDirectory))
            install(modelRoot)
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ install

    private fun install(modelRoot: File): String {
        val replacement = File(modelsDirectory, "sherpa-model-new")
        replacement.deleteRecursively()
        modelRoot.copyRecursively(replacement, overwrite = true)
        check(SherpaOnnxModelValidator.isModelDirectory(replacement)) {
            "The selected model is incomplete: tokens.txt or one of the .onnx files is missing or empty."
        }

        val backup = File(modelsDirectory, "sherpa-model-old")
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

    private fun findModelRoot(start: File): File? {
        if (SherpaOnnxModelValidator.isModelDirectory(start)) return start
        return start.walkTopDown()
            .maxDepth(ModelArchiveExtractor.MAX_SEARCH_DEPTH)
            .firstOrNull(SherpaOnnxModelValidator::isModelDirectory)
    }

    private fun requireArchiveName(uri: Uri) {
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        // Some pickers report no name at all; we accept anything the
        // user pointed at and validate the contents instead, but if
        // there is a name, it should look like an archive.
        if (displayName != null) {
            val lower = displayName.lowercase()
            require(
                lower.endsWith(".zip") ||
                    lower.endsWith(".tar.bz2") ||
                    lower.endsWith(".tbz2") ||
                    lower.endsWith(".tgz")
            ) {
                "Choose the .zip or .tar.bz2 archive you downloaded from the sherpa-onnx model catalog"
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
