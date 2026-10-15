package com.example.voiceassistant.stt

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * The pure-JVM half of the speech-model import: unzipping, validation and model lookup.
 *
 * This used to live inside [VoskModelStore] next to the Android types, which made it
 * untestable on the JVM — and that is exactly how a serious bug shipped: the zip entry was
 * closed *before* its bytes were read, so every extracted file was created empty. The
 * resulting model still passed the old "conf/model.conf exists" check, so the import was
 * reported as successful and only failed much later, when Vosk tried to load it
 * ("Could not initialise the offline speech model").
 *
 * Nothing here touches Android, so `ModelArchiveExtractorTest` can assert the real bytes.
 */
internal object ModelArchiveExtractor {

    /** A Vosk model is useless without a non-trivial configuration file and payload. */
    const val CONFIG_FILE = "conf/model.conf"

    /** Anything smaller than this cannot be a usable speech model. */
    const val MIN_MODEL_BYTES = 1_048_576L

    const val MAX_MODEL_BYTES = 2_000_000_000L
    const val MAX_SEARCH_DEPTH = 6
    const val MAX_ARCHIVE_ENTRIES = 5_000

    /** Tracks how much has been written so a hostile or wrong archive cannot fill the disk. */
    class Budget(private val limitBytes: Long = ModelArchiveExtractor.MAX_MODEL_BYTES) {
        var copiedBytes = 0L
            private set

        fun add(bytes: Int) {
            copiedBytes += bytes
            require(copiedBytes <= limitBytes) {
                "Model exceeds the ${limitBytes / (1024 * 1024)} MB import limit"
            }
        }
    }

    /**
     * Extracts [zip] into [destination].
     *
     * Each entry's bytes are read to the end *before* [ZipInputStream.closeEntry] is called:
     * closing first makes `read` return `-1` immediately (the JDK contract drains the entry),
     * which silently produces empty files.
     */
    fun extract(zip: ZipInputStream, destination: File, budget: Budget = Budget()) {
        var entryCount = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                error("Archive contains too many files to be a speech model")
            }

            val name = entry.name.replace('\\', '/').trim()
            if (entry.isDirectory || name.isEmpty() || name.endsWith("/")) {
                if (name.isNotEmpty()) {
                    resolveSafely(destination, name)?.mkdirs()
                }
                zip.closeEntry()
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
            zip.closeEntry()
        }
    }

    /** Resolves a zip entry inside [destination], rejecting absolute paths and `..` escapes. */
    fun resolveSafely(destination: File, entryName: String): File? {
        if (entryName.startsWith("/") || entryName.contains(":")) return null
        if (entryName.contains('\u0000')) return null
        val target = File(destination, entryName)
        val canonicalDestination = destination.canonicalPath + File.separator
        return try {
            if (target.canonicalPath.startsWith(canonicalDestination)) target else null
        } catch (_: IOException) {
            null
        }
    }

    /**
     * `null` when [directory] holds a model the app can actually load, otherwise the reason
     * it cannot. The size requirement is the important half: it is what turns a silently
     * empty extraction into a visible failure instead of a crash when the engine loads it.
     */
    fun describeModelProblem(directory: File): String? {
        if (!directory.isDirectory) return "the model folder does not exist"
        val config = File(directory, CONFIG_FILE)
        if (!config.isFile) return "$CONFIG_FILE is missing"
        if (config.length() == 0L) return "$CONFIG_FILE is empty"
        val bytes = modelSizeBytes(directory)
        if (bytes < MIN_MODEL_BYTES) {
            return "the model holds only $bytes bytes of data (an empty or truncated import)"
        }
        return null
    }

    /** `true` when [directory] holds a model the app can actually load. */
    fun isModelDirectory(directory: File): Boolean = describeModelProblem(directory) == null

    fun modelSizeBytes(directory: File): Long =
        directory.walkTopDown().filter(File::isFile).sumOf(File::length)

    /** Finds the model directory inside an extracted tree (archives wrap it in a folder). */
    fun findModelRoot(start: File): File? {
        if (isModelDirectory(start)) return start
        return start.walkTopDown()
            .maxDepth(MAX_SEARCH_DEPTH)
            .firstOrNull(::isModelDirectory)
    }

    /** Explains why an extracted tree is not a model, for the UI status message. */
    fun describeRejection(directory: File): String {
        val files = directory.walkTopDown().filter(File::isFile).toList()
        val megabytes = files.sumOf(File::length) / (1024.0 * 1024.0)
        val configs = files.filter { it.name == "model.conf" && it.parentFile?.name == "conf" }
        return when {
            files.isEmpty() ->
                "Nothing was extracted from that archive — the download may be empty or not a .zip file."
            configs.isEmpty() ->
                "That archive has no conf/model.conf (extracted ${files.size} files, " +
                    "%.1f MB) — it does not look like a Vosk model.".format(megabytes)
            configs.all { it.length() == 0L } ->
                "The extracted conf/model.conf is empty (extracted ${files.size} files, " +
                    "%.1f MB) — the archive is incomplete.".format(megabytes)
            else ->
                "The extracted model is too small to be usable (${files.size} files, " +
                    "%.1f MB) — the archive is incomplete.".format(megabytes)
        }
    }
}
