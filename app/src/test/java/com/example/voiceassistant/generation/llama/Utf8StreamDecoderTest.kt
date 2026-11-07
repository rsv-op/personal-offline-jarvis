package com.example.voiceassistant.generation.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streaming decoder is the only thing standing between llama.cpp's raw
 * byte output and correct non-ASCII text, so it is tested directly.
 */
class Utf8StreamDecoderTest {

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `ascii passes through unchanged`() {
        val decoder = Utf8StreamDecoder()
        assertEquals("hello", decoder.decode("hello".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `complete multi-byte character in one chunk is decoded`() {
        val decoder = Utf8StreamDecoder()
        // "é" is 0xC3 0xA9
        assertEquals("é", decoder.decode(bytes(0xC3, 0xA9)))
    }

    @Test
    fun `character split across chunks is reassembled`() {
        val decoder = Utf8StreamDecoder()
        // The lead byte arrives alone: nothing can be decoded yet.
        assertEquals("", decoder.decode(bytes(0xC3)))
        // The continuation byte completes it.
        assertEquals("é", decoder.decode(bytes(0xA9)))
    }

    @Test
    fun `three byte character split across three chunks is reassembled`() {
        val decoder = Utf8StreamDecoder()
        // "€" is 0xE2 0x82 0xAC
        assertEquals("", decoder.decode(bytes(0xE2)))
        assertEquals("", decoder.decode(bytes(0x82)))
        assertEquals("€", decoder.decode(bytes(0xAC)))
    }

    @Test
    fun `four byte emoji split across chunks is reassembled`() {
        val decoder = Utf8StreamDecoder()
        // "😀" is 0xF0 0x9F 0x98 0x80
        assertEquals("", decoder.decode(bytes(0xF0)))
        assertEquals("", decoder.decode(bytes(0x9F)))
        assertEquals("", decoder.decode(bytes(0x98)))
        assertEquals("😀", decoder.decode(bytes(0x80)))
    }

    @Test
    fun `mixed ascii and multibyte text streams correctly`() {
        val decoder = Utf8StreamDecoder()
        val text = "café €1 naïve"
        val bytes = text.toByteArray(Charsets.UTF_8)

        // Feed one byte at a time: the worst case for a streaming decoder.
        val rebuilt = StringBuilder()
        for (byte in bytes) {
            rebuilt.append(decoder.decode(byteArrayOf(byte)))
        }
        rebuilt.append(decoder.flush())

        assertEquals(text, rebuilt.toString())
    }

    @Test
    fun `decode never emits replacement characters for split input`() {
        val decoder = Utf8StreamDecoder()
        val bytes = "café".toByteArray(Charsets.UTF_8)

        val rebuilt = StringBuilder()
        for (byte in bytes) {
            val chunk = decoder.decode(byteArrayOf(byte))
            assertTrue("chunk must not contain U+FFFD", !chunk.contains('�'))
            rebuilt.append(chunk)
        }
        rebuilt.append(decoder.flush())

        assertEquals("café", rebuilt.toString())
    }

    @Test
    fun `flush releases a truncated trailing sequence`() {
        val decoder = Utf8StreamDecoder()
        assertEquals("", decoder.decode(bytes(0xC3)))
        // The character never completed. flush() must not silently drop the
        // bytes; it decodes them leniently instead.
        val flushed = decoder.flush()
        assertTrue("flush must return something for a pending byte", flushed.isNotEmpty())
    }

    @Test
    fun `empty chunk returns empty string`() {
        val decoder = Utf8StreamDecoder()
        assertEquals("", decoder.decode(ByteArray(0)))
    }

    @Test
    fun `decoder can be reused after a complete sequence`() {
        val decoder = Utf8StreamDecoder()
        assertEquals("a", decoder.decode(bytes(0x61)))
        assertEquals("é", decoder.decode(bytes(0xC3, 0xA9)))
        assertEquals("b", decoder.decode(bytes(0x62)))
    }
}
