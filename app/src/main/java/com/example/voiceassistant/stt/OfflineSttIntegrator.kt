package com.example.voiceassistant.stt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class OfflineSttIntegrator(
    private val engine: SttEngine,
    private val audioStream: SharedFlow<ShortArray>,
    private val frameRecycler: (ShortArray) -> Unit,
    private val eventDispatcher: (SttEvent) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var transcriptionJob: Job? = null
    private val isInitialized = AtomicBoolean(false)
    private val isTranscribing = AtomicBoolean(false)

    fun loadModel(modelPath: String) {
        scope.launch {
            try {
                engine.initialize(modelPath)
                isInitialized.set(true)
            } catch (e: Exception) {
                eventDispatcher(SttEvent.OnError(
                    Exception("Failed to initialize STT model: ${e.message}", e)
                ))
            }
        }
    }

    fun startTranscription() {
        if (!isInitialized.get()) {
            eventDispatcher(SttEvent.OnError(IllegalStateException("STT Engine is not initialized.")))
            return
        }
        if (isTranscribing.getAndSet(true)) return

        engine.reset()
        transcriptionJob = scope.launch {
            try {
                audioStream.collect { frame ->
                    try {
                        val isEndpoint = engine.processAudio(frame)
                        if (isEndpoint) {
                            emitFinalAndReset()
                        } else {
                            engine.getPartialText().takeIf(String::isNotBlank)?.let {
                                eventDispatcher(SttEvent.OnPartialResult(it))
                            }
                        }
                    } finally {
                        frameRecycler(frame)
                    }
                }
            } catch (_: CancellationException) {
                // Normal when transcription is stopped.
            } catch (e: Exception) {
                eventDispatcher(SttEvent.OnError(e))
            } finally {
                isTranscribing.set(false)
            }
        }
    }

    fun stopTranscription() {
        if (!isTranscribing.getAndSet(false)) return
        val activeJob = transcriptionJob
        activeJob?.cancel()
        scope.launch {
            activeJob?.join()
            emitFinalAndReset()
        }
    }

    private fun emitFinalAndReset() {
        val final = engine.getFinalText()
        if (final.isNotBlank()) {
            eventDispatcher(SttEvent.OnFinalResult(final))
        } else {
            eventDispatcher(SttEvent.OnEmptyAcoustic)
        }
        engine.reset()
    }

    fun releaseModel() {
        stopTranscription()
        engine.release()
        isInitialized.set(false)
        scope.cancel()
    }
}
