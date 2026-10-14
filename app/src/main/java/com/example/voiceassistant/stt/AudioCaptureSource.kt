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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
                        val count = audioRecord.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) {
                            _frames.emit(frame.copyOf(count))
                        } else if (count == 0) {
                            continue
                        } else if (count == AudioRecord.ERROR_INVALID_OPERATION ||
                            count == AudioRecord.ERROR_BAD_VALUE
                        ) {
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

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_SIZE = 1_600 // 100 ms at 16 kHz
    }
}
