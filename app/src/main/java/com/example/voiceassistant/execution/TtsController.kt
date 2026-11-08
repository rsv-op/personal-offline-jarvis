package com.example.voiceassistant.execution

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

sealed interface TtsEvent {
    data object OnReady : TtsEvent
    data class OnStart(val utteranceId: String) : TtsEvent
    data class OnDone(val utteranceId: String) : TtsEvent
    data class OnError(val exception: Exception) : TtsEvent

    /**
     * Playback was cut off by the user (`"shaz"`) rather than finishing.
     *
     * It is a separate event, not an [OnDone], because the caller has to tell the two apart:
     * a finished reply goes back to idle, whereas an interrupted one must not continue into
     * the next sentence of the same answer.
     */
    data class OnInterrupted(val utteranceId: String) : TtsEvent
}

/**
 * The offline speech-output path, with an absolute interrupt.
 *
 * Everything here runs on the device's own TextToSpeech engine; the app holds no
 * `INTERNET` permission, so the engine cannot fetch a cloud voice even if one were
 * installed.
 *
 * **The interrupt is the important part.** `"shaz"` has to stop the audio *now*, not after
 * the current utterance ends:
 *
 *  * [interrupt] calls `TextToSpeech.stop()` on the engine thread, which aborts the
 *    utterance and drops the queued audio buffer inside the TTS service — that is the
 *    flush;
 *  * [isInterrupted] and [interruptGeneration] let a caller that is streaming a *sequence*
 *    of sentences (a token-streamed llama.cpp answer) notice the interrupt between
 *    sentences and stop synthesising the rest, instead of draining a queue for another
 *    ten seconds;
 *  * [OnInterrupted] then tells the voice loop that the turn ended early, so the state
 *    machine does not wait for an utterance completion that will never arrive.
 */
class TtsController(context: Context) : TextToSpeech.OnInitListener {
    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false
    private val pendingUtterances = mutableListOf<Pair<String, String>>()

    /** Incremented by every [interrupt]; a streaming speaker compares it to stop early. */
    private val generation = AtomicLong(0)

    /** The utterance the engine is currently speaking, or `null`. */
    @Volatile private var activeUtteranceId: String? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _events.tryEmit(TtsEvent.OnError(IllegalStateException("TTS initialization failed")))
            return
        }

        isReady = true
        _events.tryEmit(TtsEvent.OnReady)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                activeUtteranceId = utteranceId
                _events.tryEmit(TtsEvent.OnStart(utteranceId))
            }

            override fun onDone(utteranceId: String) {
                if (utteranceId == activeUtteranceId) activeUtteranceId = null
                _events.tryEmit(TtsEvent.OnDone(utteranceId))
            }

            override fun onError(utteranceId: String) {
                if (utteranceId == activeUtteranceId) activeUtteranceId = null
                _events.tryEmit(TtsEvent.OnError(IllegalStateException("TTS failed for $utteranceId")))
            }
        })

        synchronized(pendingUtterances) {
            pendingUtterances.toList().forEach { (text, id) -> speakImmediate(text, id) }
            pendingUtterances.clear()
        }
    }

    suspend fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()) {
        synchronized(pendingUtterances) {
            if (isReady) {
                speakImmediate(text, utteranceId)
            } else {
                pendingUtterances += text to utteranceId
            }
        }
    }

    private fun speakImmediate(text: String, id: String) {
        activeUtteranceId = id
        // QUEUE_FLUSH keeps the newest answer authoritative: a stale sentence left over
        // from the previous turn must never play over the new one.
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /**
     * The `"shaz"` interrupt.
     *
     * Stops the engine (which flushes its own audio buffer), discards anything still waiting
     * to be spoken, bumps the generation counter so a streaming speaker stops feeding new
     * sentences, and emits [TtsEvent.OnInterrupted] for the turn that was cut off.
     *
     * Safe to call from any thread and when nothing is playing.
     *
     * @return the utterance id that was interrupted, or `null` when nothing was playing.
     */
    fun interrupt(): String? {
        generation.incrementAndGet()
        synchronized(pendingUtterances) {
            pendingUtterances.clear()
        }
        val interrupted = activeUtteranceId
        activeUtteranceId = null
        val stopped = runCatching { tts?.stop() }.getOrElse { error ->
            _events.tryEmit(TtsEvent.OnError(IllegalStateException("TTS stop failed", error)))
            null
        }
        if (interrupted != null) {
            // The engine's own audio buffer is flushed by stop(); this tells the voice loop
            // that the turn ended early so it does not wait for an onDone that never comes.
            _events.tryEmit(TtsEvent.OnInterrupted(interrupted))
        } else if (stopped != TextToSpeech.SUCCESS && stopped != null) {
            _events.tryEmit(
                TtsEvent.OnError(IllegalStateException("The speech engine refused to stop (code $stopped)"))
            )
        }
        return interrupted
    }

    /** The token a streaming speaker holds; it changes on every [interrupt]. */
    fun interruptGeneration(): Long = generation.get()

    /** True when [token] was taken before the most recent interrupt. */
    fun isInterrupted(token: Long): Boolean = generation.get() != token

    /** True when there is a live engine that could still be speaking. */
    fun isSpeaking(): Boolean = isReady && activeUtteranceId != null

    fun shutdown() {
        interrupt()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
