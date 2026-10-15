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
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class OfflineSttIntegrator(
    private val engine: SttEngine,
    private val audioStream: SharedFlow<ShortArray>,
    private val frameRecycler: (ShortArray) -> Unit,
    private val eventDispatcher: (SttEvent) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var transcriptionJob: Job? = null
    private val isInitialized = AtomicBoolean(false)
    private val isTranscribing = AtomicBoolean(false)

    /**
     * Why the last initialization attempt failed, or `null` if it succeeded. The caller
     * surfaces this instead of a generic "could not initialize" message, so a broken model
     * import is diagnosable from the phone alone.
     */
    @Volatile
    var lastInitializationError: String? = null
        private set

    fun loadModel(modelPath: String) {
        scope.launch { initializeModel(modelPath) }
    }

    suspend fun initializeModel(modelPath: String): Boolean = withContext(dispatcher) {
        try {
            engine.initialize(modelPath)
            isInitialized.set(true)
            lastInitializationError = null
            true
        } catch (e: Exception) {
            val reason = e.message ?: e::class.java.simpleName
            val message = "Failed to load the offline speech model: $reason"
            lastInitializationError = message
            eventDispatcher(SttEvent.OnError(Exception(message, e)))
            false
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

    fun stopTranscription(emitFinalResult: Boolean = true) {
        if (!isTranscribing.getAndSet(false)) return
        val activeJob = transcriptionJob
        activeJob?.cancel()
        scope.launch {
            activeJob?.join()
            try {
                if (emitFinalResult) emitFinalAndReset() else engine.reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A speech engine that fails while finalising must not take the scope
                // down with it: report it and let the assistant ask the user to repeat.
                eventDispatcher(SttEvent.OnError(e))
            }
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
        isTranscribing.set(false)
        val activeJob = transcriptionJob
        activeJob?.cancel()
        scope.launch {
            activeJob?.join()
            engine.release()
            isInitialized.set(false)
            scope.cancel()
        }
    }
}
