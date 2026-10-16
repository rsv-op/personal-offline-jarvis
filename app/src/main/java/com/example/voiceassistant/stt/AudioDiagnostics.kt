package com.example.voiceassistant.stt

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-JVM helpers for the in-app audio diagnostics screen. The screen exists so a user
 * can answer the question "why is my STT bad" by reading what the app actually heard,
 * rather than guessing from a transcript alone.
 *
 * Everything here is deterministic and free of Android types, so it is testable on the
 * JVM without Robolectric.
 */
object AudioDiagnostics {

    /**
     * Computes the peak amplitude (0..1) and the RMS (0..1) of a 16-bit PCM frame. The
     * caller decides the time window; 100 ms at 16 kHz (1600 samples) is a good default
     * for a level meter.
     */
    data class Level(val peak: Float, val rms: Float) {
        /** dBFS for the peak, clamped to [-60, 0]. -60 is "effectively silent". */
        val peakDb: Float get() = toDb(peak)
        val rmsDb: Float get() = toDb(rms)

        private fun toDb(amplitude: Float): Float {
            if (amplitude <= 0.0001f) return -60f
            return max(-60f, (20.0 * ln(amplitude.toDouble()) / ln(10.0)).toFloat())
        }

        companion object {
            val SILENT = Level(peak = 0f, rms = 0f)
        }
    }

    fun levelOf(samples: ShortArray): Level {
        if (samples.isEmpty()) return Level.SILENT
        var peak = 0
        var sumSquares = 0.0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
            sumSquares += s.toDouble() * s.toDouble()
        }
        val peakNorm = peak / 32768f
        val rmsNorm = sqrt(sumSquares / samples.size).toFloat() / 32768f
        return Level(peakNorm, rmsNorm)
    }

    /**
     * Keeps a rolling window of the last [capacitySamples] audio samples. When full, the
     * oldest samples are dropped. The buffer is a flat short[]; the writer copies in,
     * the reader copies out — no allocation per frame.
     */
    class RollingAudioBuffer(val capacitySamples: Int) {
        init {
            require(capacitySamples > 0) { "capacitySamples must be positive" }
        }

        private val data = ShortArray(capacitySamples)
        private var writeIndex = 0
        private var totalWritten = 0L

        val size: Int get() = min(totalWritten, capacitySamples.toLong()).toInt()

        fun append(samples: ShortArray) {
            if (samples.isEmpty()) return
            for (s in samples) {
                data[writeIndex] = s
                writeIndex = (writeIndex + 1) % capacitySamples
                totalWritten++
            }
        }

        /**
         * Returns the samples currently in the buffer, oldest first. Allocates a new
         * array of the current size.
         */
        fun snapshot(): ShortArray {
            val n = size
            if (n == 0) return ShortArray(0)
            val out = ShortArray(n)
            val start = if (totalWritten < capacitySamples) 0 else writeIndex
            if (start + n <= capacitySamples) {
                System.arraycopy(data, start, out, 0, n)
            } else {
                val first = capacitySamples - start
                System.arraycopy(data, start, out, 0, first)
                System.arraycopy(data, 0, out, first, n - first)
            }
            return out
        }

        fun clear() {
            writeIndex = 0
            totalWritten = 0
        }
    }

    /**
     * Incremental counters for the audio capture loop. Cheap to update and to read.
     */
    data class CaptureStats(
        var framesDelivered: Long = 0,
        var framesReadZero: Long = 0,
        var readErrors: Long = 0,
        var lastReadLatencyMicros: Long = 0,
        var totalReadLatencyMicros: Long = 0
    ) {
        val averageReadLatencyMicros: Long
            get() = if (framesDelivered == 0L) 0L else totalReadLatencyMicros / framesDelivered
    }

    /**
     * Writes a 16-bit mono PCM WAV file at the given sample rate. The format follows
     * the canonical RIFF/WAVE layout; Vosk, Android's `MediaPlayer`, and macOS
     * QuickTime all read this without complaint.
     */
    object WavWriter {
        const val PCM_FORMAT_TAG = 1 // PCM
        const val BITS_PER_SAMPLE = 16
        const val NUM_CHANNELS = 1

        fun write(file: File, sampleRateHz: Int, samples: ShortArray) {
            require(sampleRateHz > 0) { "sample rate must be positive" }
            require(samples.isNotEmpty()) { "refusing to write a zero-sample WAV" }
            val byteRate = sampleRateHz * NUM_CHANNELS * BITS_PER_SAMPLE / 8
            val dataSizeBytes = samples.size * NUM_CHANNELS * BITS_PER_SAMPLE / 8
            val totalSizeBytes = 36 + dataSizeBytes

            val buffer = ByteBuffer.allocate(44 + dataSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(totalSizeBytes)
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16) // fmt chunk size
            buffer.putShort(PCM_FORMAT_TAG.toShort())
            buffer.putShort(NUM_CHANNELS.toShort())
            buffer.putInt(sampleRateHz)
            buffer.putInt(byteRate)
            buffer.putShort((NUM_CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // block align
            buffer.putShort(BITS_PER_SAMPLE.toShort())
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(dataSizeBytes)
            for (s in samples) buffer.putShort(s)

            FileOutputStream(file).use { it.write(buffer.array()) }
        }

        /**
         * Validates that [file] looks like a 16-bit mono PCM WAV at the given sample
         * rate. Used by the diagnose screen to confirm a saved file is well-formed
         * before claiming success.
         */
        fun isWellFormed(file: File, expectedSampleRateHz: Int): Boolean {
            if (!file.isFile || file.length() < 44) return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val header = ByteArray(44)
                    if (raf.read(header) != 44) return false
                    val riff = String(header, 0, 4, Charsets.US_ASCII)
                    val wave = String(header, 8, 4, Charsets.US_ASCII)
                    val fmt = String(header, 12, 4, Charsets.US_ASCII)
                    val data = String(header, 36, 4, Charsets.US_ASCII)
                    if (riff != "RIFF" || wave != "WAVE" || fmt != "fmt " || data != "data") return false
                    val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    // Layout: 20..21 format tag, 22..23 channels, 24..27 sample rate,
                    // 28..31 byte rate, 32..33 block align, 34..35 bits per sample.
                    buffer.position(20)
                    val formatTag = buffer.short.toInt() and 0xFFFF
                    val channels = buffer.short.toInt() and 0xFFFF
                    val sampleRate = buffer.int
                    buffer.position(34)
                    val bitsPerSample = buffer.short.toInt() and 0xFFFF
                    formatTag == PCM_FORMAT_TAG &&
                        channels == 1 &&
                        bitsPerSample == BITS_PER_SAMPLE &&
                        sampleRate == expectedSampleRateHz
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
