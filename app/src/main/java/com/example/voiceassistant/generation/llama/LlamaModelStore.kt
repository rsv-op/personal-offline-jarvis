package com.example.voiceassistant.generation.llama

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps one user-provisioned GGUF model in app-private storage.
 *
 * The model is imported from a local file the user picks. There is no
 * downloader and no network access of any kind: the app holds no INTERNET
 * permission, so a model can only arrive through a file the user explicitly
 * chose. The same pattern is already used for the sherpa-onnx speech model.
 */
@Singleton
class LlamaModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDirectory = File(context.filesDir, "models")
    private val installedModel = File(modelsDirectory, "local-reply-model.gguf")

    /** Absolute path of the installed model, or `null` when none is installed. */
    fun installedModelPath(): String? =
        installedModel.takeIf { it.isFile && it.length() > 0L }?.absolutePath

    /** Size of the installed model in megabytes, or `null` when none is installed. */
    fun installedModelSizeMb(): Double? =
        installedModel.takeIf { it.isFile && it.length() > 0L }
            ?.length()
            ?.let { bytes -> bytes / (1024.0 * 1024.0) }

    /** Size of the installed model in bytes, or 0 when none is installed. */
    fun installedModelSizeBytes(): Long =
        installedModel.takeIf { it.isFile }?.length() ?: 0L

    /**
     * Imports a GGUF model chosen by the user.
     *
     * The file is copied into app-private storage so the assistant keeps
     * working after the user revokes access to the original location, and so
     * llama.cpp can memory-map it from a stable path.
     */
    suspend fun importModel(uri: Uri): String = withContext(Dispatchers.IO) {
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        require(
            displayName == null || displayName.endsWith(MODEL_EXTENSION, ignoreCase = true)
        ) {
            "Choose a GGUF model file (.gguf)"
        }

        modelsDirectory.mkdirs()
        // Write to a temporary name first so a failed copy never leaves a
        // half-written model that llama.cpp would try to load.
        val temporary = File(modelsDirectory, "local-reply-model.gguf.part")
        temporary.delete()

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copyLimited(input, temporary)
            } ?: throw IllegalArgumentException("The selected file could not be opened")

            require(temporary.length() > 0L) { "The selected model file is empty" }

            if (installedModel.exists()) installedModel.delete()
            if (!temporary.renameTo(installedModel)) {
                // renameTo can fail across some filesystems; fall back to a copy.
                temporary.copyTo(installedModel, overwrite = true)
                temporary.delete()
            }
        } finally {
            temporary.delete()
        }

        installedModel.absolutePath
    }

    /** Removes the installed model, if any. */
    fun deleteModel(): Boolean = installedModel.delete()

    private fun copyLimited(input: InputStream, destination: File) {
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            var totalBytes = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                totalBytes += read
                require(totalBytes <= MAX_MODEL_BYTES) {
                    "Reply model exceeds the ${MAX_MODEL_BYTES / (1024 * 1024)} MB import limit"
                }
                output.write(buffer, 0, read)
            }
        }
    }

    private companion object {
        const val MODEL_EXTENSION = ".gguf"
        const val COPY_BUFFER_SIZE = 64 * 1024

        /**
         * 4 GB. Large enough for the biggest model a phone can realistically
         * run, small enough that a mistyped file cannot fill the device.
         */
        const val MAX_MODEL_BYTES = 4_000_000_000L
    }
}
