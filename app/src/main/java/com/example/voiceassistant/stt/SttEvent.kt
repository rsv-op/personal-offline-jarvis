package com.example.voiceassistant.stt

sealed interface SttEvent {
    data class OnPartialResult(val text: String) : SttEvent
    data class OnFinalResult(val text: String) : SttEvent
    data object OnEmptyAcoustic : SttEvent
    data class OnError(val exception: Exception) : SttEvent
}
