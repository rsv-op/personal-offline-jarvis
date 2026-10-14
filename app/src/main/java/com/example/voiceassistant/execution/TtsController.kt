package com.example.voiceassistant.execution

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

sealed interface TtsEvent {
    data object OnReady : TtsEvent
    data class OnStart(val utteranceId: String) : TtsEvent
    data class OnDone(val utteranceId: String) : TtsEvent
    data class OnError(val exception: Exception) : TtsEvent
}

class TtsController(context: Context) : TextToSpeech.OnInitListener {
    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false
    private val pendingUtterances = mutableListOf<Pair<String, String>>()

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
                _events.tryEmit(TtsEvent.OnStart(utteranceId))
            }

            override fun onDone(utteranceId: String) {
                _events.tryEmit(TtsEvent.OnDone(utteranceId))
            }

            override fun onError(utteranceId: String) {
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
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
