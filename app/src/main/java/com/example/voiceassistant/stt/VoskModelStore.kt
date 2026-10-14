package com.example.voiceassistant.stt

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Copies a user-selected Vosk model tree into private app storage for fully offline use. */
@Singleton
class VoskModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDirectory = File(context.filesDir, "models")
    private val installedDirectory = File(modelsDirectory, "vosk-model")

    fun installedModelPath(): String? =
        installedDirectory.takeIf(::isVoskModelDirectory)?.absolutePath

    suspend fun importModelTree(treeUri: Uri): String = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Unable to open the selected model folder")
        require(source.isDirectory) { "Select the extracted Vosk model folder" }

        check(modelsDirectory.exists() || modelsDirectory.mkdirs()) {
            "Could not create private model storage"
        }
        val stagingDirectory = File(modelsDirectory, "vosk-import-staging")
        stagingDirectory.deleteRecursively()
        check(stagingDirectory.mkdirs()) { "Could not create model import staging folder" }

        try {
            val budget = CopyBudget(MAX_MODEL_BYTES)
            copyChildren(source, stagingDirectory, budget)
            val modelRoot = findModelRoot(stagingDirectory)
                ?: throw IllegalArgumentException(
                    "No Vosk model found. Select a folder containing conf/model.conf."
                )

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
            installedDirectory.absolutePath
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

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
    }
}
