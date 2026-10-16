package com.example.voiceassistant.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Captures 16 kHz mono PCM audio and streams short frames to the offline recognizer. */
class AudioCaptureSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 4)
    val frames: SharedFlow<ShortArray> = _frames.asSharedFlow()

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    /**
     * Most-recent 100 ms frame's level, in dBFS (-60..0). The diagnose screen reads this
     * to draw the live meter. -60 means "effectively silent"; 0 means "clipping".
     */
    private val _levelDb = MutableStateFlow(-60f)
    val levelDb: StateFlow<Float> = _levelDb.asStateFlow()

    /**
     * Live capture stats: frames delivered, frames that returned 0 bytes, read errors,
     * the last read latency. The diagnose screen shows these so a user can see if audio
     * is being dropped (low framesDelivered, high framesReadZero).
     */
    val stats = AudioDiagnostics.CaptureStats()

    /**
     * The 10 s rolling audio buffer. Kept in-memory because 10 s × 16 kHz × 2 bytes is
     * 320 KB — small enough that streaming to a temp file would cost more than it saves.
     * Cleared on every start().
     */
    val rollingBuffer = AudioDiagnostics.RollingAudioBuffer(capacitySamples = 160_000)

    private val lifecycleMutex = Mutex()
    @Volatile private var recorder: AudioRecord? = null
    private var captureJob: Job? = null

    suspend fun start() = lifecycleMutex.withLock {
        if (captureJob?.isActive == true) return@withLock
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission is required for offline speech recognition")
        }

        rollingBuffer.clear()
        stats.framesDelivered = 0
        stats.framesReadZero = 0
        stats.readErrors = 0
        stats.lastReadLatencyMicros = 0
        stats.totalReadLatencyMicros = 0
        _levelDb.value = -60f

        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimumBufferBytes > 0) { "This device does not support 16 kHz microphone capture" }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimumBufferBytes, FRAME_SIZE * Short.SIZE_BYTES * 2)
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            error("Could not initialize microphone capture")
        }

        try {
            audioRecord.startRecording()
            recorder = audioRecord
            captureJob = scope.launch {
                val frame = ShortArray(FRAME_SIZE)
                try {
                    while (isActive) {
                        val readStartNanos = System.nanoTime()
                        val count = audioRecord.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                        val readLatencyMicros = (System.nanoTime() - readStartNanos) / 1_000L
                        stats.lastReadLatencyMicros = readLatencyMicros
                        if (count > 0) {
                            val copy = frame.copyOf(count)
                            stats.framesDelivered++
                            stats.totalReadLatencyMicros += readLatencyMicros
                            rollingBuffer.append(copy)
                            _levelDb.value = AudioDiagnostics.levelOf(copy).peakDb
                            _frames.emit(copy)
                        } else if (count == 0) {
                            stats.framesReadZero++
                            continue
                        } else if (count == AudioRecord.ERROR_INVALID_OPERATION ||
                            count == AudioRecord.ERROR_BAD_VALUE
                        ) {
                            stats.readErrors++
                            break
                        } else {
                            throw IllegalStateException("AudioRecord read failed: $count")
                        }
                    }
                } catch (_: CancellationException) {
                    // Expected when listening is stopped.
                } catch (error: Throwable) {
                    _errors.emit(error)
                }
            }
        } catch (error: Throwable) {
            recorder = null
            audioRecord.release()
            throw error
        }
    }

    suspend fun stop() = lifecycleMutex.withLock {
        val activeJob = captureJob
        val activeRecorder = recorder
        captureJob = null
        recorder = null
        activeJob?.cancel()
        try {
            if (activeRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                activeRecorder.stop()
            }
        } catch (_: IllegalStateException) {
            // The recorder can already be stopped after an audio-device error.
        }
        activeJob?.cancelAndJoin()
        activeRecorder?.release()
    }

    fun close() {
        captureJob?.cancel()
        recorder?.let { activeRecorder ->
            runCatching { activeRecorder.stop() }
            activeRecorder.release()
        }
        recorder = null
        captureJob = null
        scope.cancel()
    }

    /**
     * Writes the current contents of the rolling buffer to [destination] as a 16-bit
     * mono PCM WAV at the capture sample rate. Returns `true` on success. The diagnose
     * screen uses this to save the last 10 s of audio to `Download/` for offline
     * playback; the file is well-formed and `MediaPlayer` plays it back directly.
     */
    fun saveRollingBuffer(destination: File): Boolean {
        val parent = destination.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false
        val samples = rollingBuffer.snapshot()
        if (samples.isEmpty()) return false
        return try {
            AudioDiagnostics.WavWriter.write(destination, SAMPLE_RATE_HZ, samples)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Sample rate the capture loop runs at; also the rate Vosk is configured for. */
        const val SAMPLE_RATE_HZ = 16_000

        /** 100 ms at [SAMPLE_RATE_HZ]. */
        private const val FRAME_SIZE = 1_600
    }
}
