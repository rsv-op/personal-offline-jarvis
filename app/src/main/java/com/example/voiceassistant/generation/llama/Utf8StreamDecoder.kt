package com.example.voiceassistant.generation.llama

/**
 * Reassembles a stream of raw byte chunks into valid UTF-8 text.
 *
 * Why this exists: llama.cpp emits *bytes*, one chunk per token. A BPE token is
 * not necessarily a whole character — for example "é" in UTF-8 is `0xC3 0xA9`, and
 * a tokenizer may split that across two tokens, delivering `0xC3` in one chunk
 * and `0xA9` in the next. Decoding each chunk independently with
 * `String(bytes, UTF_8)` replaces the invalid byte with `U+FFFD`, so the text
 * comes out as mojibake ("Ã©").
 *
 * The decoder therefore holds back the tail of the buffer whenever it ends in
 * the middle of a multi-byte sequence, and only releases it once the
 * continuation bytes arrive.
 *
 * This class is pure and has no Android dependencies, so it is unit-tested
 * directly.
 */
class Utf8StreamDecoder {

    private val pending = ArrayDeque<Byte>()

    /**
     * Feeds a chunk of raw bytes and returns whatever text is now complete.
     *
     * The returned string may be empty when the chunk contained only the
     * beginning of a multi-byte character.
     */
    fun decode(chunk: ByteArray): String {
        if (chunk.isEmpty()) return ""
        for (byte in chunk) pending.addLast(byte)
        return drain(force = false)
    }

    /**
     * Flushes any bytes still held back.
     *
     * Called once the generation is finished: a truncated final character
     * cannot be completed, so the remaining bytes are decoded leniently rather
     * than being silently dropped.
     */
    fun flush(): String = drain(force = true)

    private fun drain(force: Boolean): String {
        if (pending.isEmpty()) return ""

        val bytes = ByteArray(pending.size) { pending.removeFirst() }

        // Find the first byte that starts an incomplete UTF-8 sequence.
        var completeLength = bytes.size
        if (!force) {
            val holdFrom = incompleteSequenceStart(bytes)
            if (holdFrom >= 0) {
                completeLength = holdFrom
            }
        }

        // Put the held-back bytes back for the next round BEFORE returning.
        // This has to happen even when there is nothing complete to emit:
        // otherwise the partial character is dropped and the next chunk is
        // decoded on its own, which is exactly the mojibake this class exists
        // to prevent.
        for (index in completeLength until bytes.size) {
            pending.addLast(bytes[index])
        }

        if (completeLength <= 0) return ""

        return String(bytes, 0, completeLength,Charsets.UTF_8)
    }

    /**
     * Returns the index at which an incomplete UTF-8 sequence starts, or -1 if
     * the whole buffer is complete.
     */
    private fun incompleteSequenceStart(bytes: ByteArray): Int {
        // A UTF-8 sequence is at most 4 bytes, so only the last 3 bytes can
        // start an incomplete sequence.
        val from = maxOf(0, bytes.size - 3)
        for (index in bytes.size - 1 downTo from) {
            val byte = bytes[index].toInt() and 0xFF
            if (byte and 0xC0 == 0x80) {
                // Continuation byte (10xxxxxx) — keep walking backwards.
                continue
            }
            // This is a lead byte (or ASCII). Work out how long the sequence is.
            val expected = when {
                byte and 0x80 == 0x00 -> 1
                byte and 0xE0 == 0xC0 -> 2
                byte and 0xF0 == 0xE0 -> 3
                byte and 0xF8 == 0xF0 -> 4
                else -> 1 // invalid lead byte; treat it as a single byte
            }
            val available = bytes.size - index
            return if (available < expected) index else -1
        }
        // The tail consisted only of continuation bytes with no lead byte: the
        // data is malformed, so let the lenient decode handle it.
        return -1
    }
}
