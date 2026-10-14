package com.example.voiceassistant.nlp

sealed interface AppIntent {
    data class ToggleFlashlight(val isOn: Boolean) : AppIntent
    data class SetAlarm(val time: String?) : AppIntent

    /** A recognized model for confirmation flows; no file-deletion executor is wired in this app yet. */
    data class DeleteFile(val fileName: String) : AppIntent

    data object UnknownIntent : AppIntent
}

enum class IntentSensitivity {
    LOW,
    HIGH
}
