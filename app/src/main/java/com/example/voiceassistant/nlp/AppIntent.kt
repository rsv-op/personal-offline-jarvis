package com.example.voiceassistant.nlp

sealed interface AppIntent {
    data class ToggleFlashlight(val isOn: Boolean) : AppIntent
    data class SetAlarm(val time: String?) : AppIntent

    /** Timer duration in seconds; `null` means the duration still has to be asked for. */
    data class SetTimer(val durationSeconds: Int?) : AppIntent

    /** Reads device facts (battery, storage, brain status). Read-only, so no confirmation. */
    data object StatusReport : AppIntent

    /**
     * Looks up a contact by name and reports their phone number.
     *
     * Read-only: it reads the contacts provider and never writes to it, so it
     * does not change device state and does not need the confirmation gate.
     */
    data class LookUpContact(val name: String) : AppIntent

    /** A recognized model for confirmation flows; no file-deletion executor is wired in this app. */
    data class DeleteFile(val fileName: String) : AppIntent

    data object UnknownIntent : AppIntent
}

enum class IntentSensitivity {
    LOW,
    HIGH
}

/** Every intent that changes the device and therefore requires an explicit confirmation. */
val AppIntent.changesDeviceState: Boolean
    get() = this is AppIntent.ToggleFlashlight ||
        this is AppIntent.SetAlarm ||
        this is AppIntent.SetTimer ||
        this is AppIntent.DeleteFile
