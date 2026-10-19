package com.example.voiceassistant.stt

import java.io.File

/**
 * Pure-JVM content validation for a sherpa-onnx Streaming Zipformer model.
 *
 * Lifted out of [SherpaOnnxSttEngine] so the rules can be exercised on
 * the JVM (no native loader) and so the engine class stays focused on
 * recogniser lifecycle.
 *
 * A valid model directory contains:
 *
 *   * `tokens.txt` — non-empty, the BPE/SentencePiece vocabulary
 *   * `encoder-*.onnx` — the streaming encoder graph
 *   * `decoder-*.onnx` — the per-token decoder graph
 *   * `joiner-*.onnx` — the joiner that combines encoder and decoder
 *
 * Total on-disk size must be at least 1 MB. That floor is what catches
 * the "import wrote empty files" class of bug before the native loader
 * does: an empty encoder.onnx passes the file-existence check, fails
 * here, and the user sees an actionable reason.
 */
internal object SherpaOnnxModelValidator {

    /** A sherpa-onnx zipformer model is useless without a non-trivial tokens file. */
    const val TOKENS_FILE = "tokens.txt"

    /** Anything smaller than this cannot be a usable streaming speech model. */
    const val MIN_MODEL_BYTES = 1_048_576L

    /**
     * `null` when [directory] holds a model the app can actually load, otherwise
     * the reason it cannot. The size requirement is the important half: it is what
     * turns a silently empty extraction into a visible failure instead of a
     * crash when the engine loads it.
     */
    fun describeModelProblem(directory: File): String? {
        if (!directory.isDirectory) return "the model folder does not exist"
        val tokens = File(directory, TOKENS_FILE)
        if (!tokens.isFile) return "$TOKENS_FILE is missing"
        if (tokens.length() == 0L) return "$TOKENS_FILE is empty"
        if (findOnnx(directory, "encoder") == null) {
            return "encoder-*.onnx is missing — the model does not look like a streaming zipformer"
        }
        if (findOnnx(directory, "decoder") == null) {
            return "decoder-*.onnx is missing — the model does not look like a streaming zipformer"
        }
        if (findOnnx(directory, "joiner") == null) {
            return "joiner-*.onnx is missing — the model does not look like a streaming zipformer"
        }
        val bytes = modelSizeBytes(directory)
        if (bytes < MIN_MODEL_BYTES) {
            return "the model holds only $bytes bytes of data (an empty or truncated import)"
        }
        return null
    }

    fun isModelDirectory(directory: File): Boolean = describeModelProblem(directory) == null

    fun modelSizeBytes(directory: File): Long =
        directory.walkTopDown().filter(File::isFile).sumOf(File::length)

    /** Finds the first .onnx file inside [directory] whose name starts with [prefix]. */
    fun findOnnx(directory: File, prefix: String): File? =
        directory.walkTopDown()
            .filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            .firstOrNull()

    /** Explains why an extracted tree is not a sherpa-onnx model, for the UI status message. */
    fun describeRejection(directory: File): String {
        val files = directory.walkTopDown().filter(File::isFile).toList()
        val megabytes = files.sumOf(File::length) / (1024.0 * 1024.0)
        val hasTokens = files.any { it.name == TOKENS_FILE }
        val onnxCount = files.count { it.name.endsWith(".onnx") }
        return when {
            files.isEmpty() ->
                "Nothing was extracted from that archive — the download may be empty or not a .zip file."
            !hasTokens && onnxCount == 0 ->
                "That archive has no $TOKENS_FILE and no .onnx files (extracted ${files.size} files, " +
                    "%.1f MB) — it does not look like a sherpa-onnx model.".format(megabytes)
            !hasTokens ->
                "That archive has $onnxCount .onnx file(s) but no $TOKENS_FILE (extracted ${files.size} files, " +
                    "%.1f MB) — it does not look like a sherpa-onnx model.".format(megabytes)
            onnxCount == 0 ->
                "That archive has $TOKENS_FILE but no .onnx files (extracted ${files.size} files, " +
                    "%.1f MB) — it does not look like a sherpa-onnx model.".format(megabytes)
            else ->
                "The extracted model is too small to be usable (${files.size} files, " +
                    "%.1f MB) — the archive is incomplete.".format(megabytes)
        }
    }
}
