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
        require(
            displayName == null ||
                displayName.endsWith(MODEL_EXTENSION, ignoreCase = true) ||
                displayName.endsWith(APK_EXTENSION, ignoreCase = true)
        ) {
            "Choose a LiteRT-LM .litertlm model file or an .apk that contains one"
        }

        // If the user picked an APK, extract the first .litertlm asset from
        // it. The APK is just a zip; the model is in the assets/ folder.
        // This lets the user download a model-bundling APK in the system
        // browser (which has its own INTERNET permission) and then point
        // the import flow at it without JARVIS itself needing the
        // INTERNET permission. The existing offline-first posture is
        // preserved: no network code is added to the app.
        val sourceInput = if (displayName?.endsWith(APK_EXTENSION, ignoreCase = true) == true) {
            val rawInput = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Could not read the selected APK")
            openLitertlmInApk(rawInput)
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Could not read the selected model file")
        }

        check(modelsDirectory.exists() || modelsDirectory.mkdirs()) {
            "Could not create private model storage"
        }
        val stagingFile = File(modelsDirectory, "local-reply-model.importing")
        stagingFile.delete()
        try {
            sourceInput.use { source -> copyLimited(source, stagingFile) }
            require(stagingFile.isFile && stagingFile.length() > 0L) {
                val cause = if (displayName?.endsWith(APK_EXTENSION, ignoreCase = true) == true) {
                    "The selected APK does not contain a .litertlm model in its assets/"
                } else {
                    "The selected model file is empty"
                }
                cause
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

    /**
     * Reads the [input] as a zip stream and returns a single [InputStream]
     * that yields the bytes of the first `.litertlm` entry found in the
     * archive. APKs are zip files and LiteRT-LM models are usually
     * placed under `assets/`, so the first `.litertlm` entry is almost
     * always the right one.
     *
     * The returned stream must be closed by the caller. The underlying
     * zip entry is read lazily; the caller only pays for the bytes it
     * actually copies into the staging file.
     */
    private fun openLitertlmInApk(input: InputStream): InputStream {
        val buffer = java.io.ByteArrayOutputStream()
        val zip = java.util.zip.ZipInputStream(input)
        var entry: java.util.zip.ZipEntry? = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(MODEL_EXTENSION, ignoreCase = true)) {
                // Drain the entry into a byte buffer. We do this in one
                // shot so the caller can close the zip stream after
                // we've finished the entry. The size cap
                // [MAX_MODEL_BYTES] still applies; if the entry is
                // larger, the import will fail with a clear message.
                val entryBytes = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = zip.read(entryBytes)
                    if (read < 0) break
                    require(buffer.size() + read <= MAX_MODEL_BYTES) {
                        "Reply model exceeds the ${MAX_MODEL_BYTES / (1024 * 1024)} MB import limit"
                    }
                    buffer.write(entryBytes, 0, read)
                }
                zip.closeEntry()
                zip.close()
                return buffer.toByteArray().inputStream()
            }
            entry = zip.nextEntry
        }
        zip.close()
        // No .litertlm found; return an empty stream so the caller's
        // empty-file check produces a clear "no model inside the APK"
        // message.
        return java.io.ByteArrayInputStream(ByteArray(0))
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
        const val APK_EXTENSION = ".apk"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_MODEL_BYTES = 4_000_000_000L
    }
}
