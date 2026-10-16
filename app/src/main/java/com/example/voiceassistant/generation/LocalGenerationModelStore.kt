package com.example.voiceassistant.generation

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

/** Keeps one user-provisioned LiteRT-LM model in app-private storage. */
@Singleton
class LocalGenerationModelStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelsDirectory = File(context.filesDir, "models")
    private val installedModel = File(modelsDirectory, "local-reply-model.litertlm")

    fun installedModelPath(): String? =
        installedModel.takeIf { it.isFile && it.length() > 0L }?.absolutePath

    /** Human-readable size of the installed reply model, or `null` when none is installed. */
    fun installedModelSizeMb(): Double? =
        installedModel.takeIf { it.isFile && it.length() > 0L }
            ?.length()
            ?.let { bytes -> bytes / (1024.0 * 1024.0) }

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
        require(displayName == null || displayName.endsWith(MODEL_EXTENSION, ignoreCase = true)) {
            "Choose a LiteRT-LM .litertlm model file"
        }

        check(modelsDirectory.exists() || modelsDirectory.mkdirs()) {
            "Could not create private model storage"
        }
        val stagingFile = File(modelsDirectory, "local-reply-model.importing")
        stagingFile.delete()
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Could not read the selected model file")
            input.use { source -> copyLimited(source, stagingFile) }
            require(stagingFile.isFile && stagingFile.length() > 0L) {
                "The selected model file is empty"
            }

            val backupFile = File(modelsDirectory, "local-reply-model.previous")
            backupFile.delete()
            if (installedModel.exists()) {
                check(installedModel.renameTo(backupFile)) { "Could not replace the current reply model" }
            }
            if (!stagingFile.renameTo(installedModel)) {
                backupFile.renameTo(installedModel)
                error("Could not install the selected reply model")
            }
            backupFile.delete()
            installedModel.absolutePath
        } finally {
            stagingFile.delete()
        }
    }

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
        const val MODEL_EXTENSION = ".litertlm"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_MODEL_BYTES = 4_000_000_000L
    }
}
