package com.example.voiceassistant.stt

import com.example.voiceassistant.stt.AudioDiagnostics.Level
import com.example.voiceassistant.stt.AudioDiagnostics.RollingAudioBuffer
import com.example.voiceassistant.stt.AudioDiagnostics.WavWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/**
 * The diagnose screen stands or falls on the math being right. These tests are the
 * contract: a level meter that lies about the level, or a WAV writer that emits a
 * header QuickTime rejects, turns a debug screen into noise.
 */
class AudioDiagnosticsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sampleRate = 16_000

    @Test
    fun `silent input reports -60 dBFS`() {
        val level = AudioDiagnostics.levelOf(ShortArray(1600))
        assertEquals(0f, level.peak, 0f)
        assertEquals(0f, level.rms, 0f)
        assertEquals(-60f, level.peakDb, 0f)
        assertEquals(-60f, level.rmsDb, 0f)
    }

    @Test
    fun `full-scale sine reports 0 dBFS peak`() {
        // 1 kHz tone, ~50% amplitude so the peak is well-defined.
        val samples = ShortArray(1600) { i ->
            (32767.0 * kotlin.math.sin(2.0 * Math.PI * 1000.0 * i / sampleRate)).toInt().toShort()
        }
        val level = AudioDiagnostics.levelOf(samples)
        assertTrue("peak should be near 1.0, was ${level.peak}", level.peak > 0.95f)
        assertTrue("peak dB should be near 0, was ${level.peakDb}", level.peakDb > -1.0f)
        assertTrue("rms should be ~0.707, was ${level.rms}", abs(level.rms - 0.707f) < 0.05f)
        assertTrue("rms dB should be near -3, was ${level.rmsDb}", abs(level.rmsDb - (-3.0f)) < 1.0f)
    }

    @Test
    fun `small signal reports a dBFS below zero`() {
        val samples = ShortArray(1600) { (it % 200).toShort() }
        val level = AudioDiagnostics.levelOf(samples)
        assertTrue("peak should be < 0.01, was ${level.peak}", level.peak < 0.01f)
        assertTrue("peak dB should be < -30, was ${level.peakDb}", level.peakDb < -30f)
    }

    @Test
    fun `rolling buffer keeps only the last capacity samples`() {
        val buffer = RollingAudioBuffer(capacitySamples = 100)
        for (i in 0 until 250) buffer.append(shortArrayOf(i.toShort()))
        val snapshot = buffer.snapshot()
        assertEquals(100, snapshot.size)
        // 250 written, capacity 100 -> indices 150..249 are kept.
        assertEquals(150.toShort(), snapshot.first())
        assertEquals(249.toShort(), snapshot.last())
    }

    @Test
    fun `rolling buffer under capacity returns exactly what was written`() {
        val buffer = RollingAudioBuffer(capacitySamples = 100)
        buffer.append(shortArrayOf(1, 2, 3, 4, 5))
        val snapshot = buffer.snapshot()
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), snapshot)
    }

    @Test
    fun `rolling buffer snapshot is empty before anything is written`() {
        val buffer = RollingAudioBuffer(capacitySamples = 100)
        assertEquals(0, buffer.snapshot().size)
    }

    @Test
    fun `rolling buffer clear resets the size and write head`() {
        val buffer = RollingAudioBuffer(capacitySamples = 100)
        for (i in 0 until 200) buffer.append(shortArrayOf(i.toShort()))
        buffer.clear()
        assertEquals(0, buffer.snapshot().size)
        buffer.append(shortArrayOf(42))
        assertArrayEquals(shortArrayOf(42), buffer.snapshot())
    }

    @Test
    fun `WAV writer produces a header readable as 16-bit mono PCM`() {
        val samples = ShortArray(sampleRate) { it.toShort() } // 1 second
        val file = File(temporaryFolder.root, "test.wav")
        WavWriter.write(file, sampleRate, samples)

        assertTrue("file should exist", file.isFile)
        assertEquals(44 + samples.size * 2L, file.length())
        assertTrue("WavWriter should accept its own output", WavWriter.isWellFormed(file, sampleRate))
    }

    @Test
    fun `WAV writer refuses to emit a zero-sample file`() {
        val failure = runCatching {
            WavWriter.write(File(temporaryFolder.root, "empty.wav"), sampleRate, ShortArray(0))
        }.exceptionOrNull()
        assertTrue("zero-sample WAV should be refused: $failure", failure is IllegalArgumentException)
    }

    @Test
    fun `WAV writer header reports the requested sample rate`() {
        val samples = ShortArray(8000) { 0 }
        val file = File(temporaryFolder.root, "rate.wav")
        WavWriter.write(file, 8_000, samples)
        // The file is well-formed for its own sample rate...
        assertTrue(WavWriter.isWellFormed(file, 8_000))
        // ...but not for a different one.
        assertFalse(WavWriter.isWellFormed(file, 16_000))
    }

    @Test
    fun `WAV writer round-trips samples exactly`() {
        val samples = ShortArray(4000) { ((it * 17) % 30000 - 15000).toShort() }
        val file = File(temporaryFolder.root, "roundtrip.wav")
        WavWriter.write(file, sampleRate, samples)

        val readBack = file.readBytes()
        // Skip the 44-byte header and compare 16-bit little-endian.
        val data = readBack.copyOfRange(44, readBack.size)
        val shorts = ShortArray(samples.size) { i ->
            val lo = data[i * 2].toInt() and 0xFF
            val hi = data[i * 2 + 1].toInt()
            (hi shl 8 or lo).toShort()
        }
        assertArrayEquals(samples, shorts)
    }
}
