package com.example.voiceassistant

import android.app.Application
import com.example.voiceassistant.core.Capability
import com.example.voiceassistant.core.DecisionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Process entry point.
 *
 * Its one job beyond `@HiltAndroidApp` is to open the decision log and write the first-run
 * authorisation records for the capabilities the assistant cannot work without: the
 * microphone, on-device speech output and local model loading. The SAF/RAG capabilities are
 * *not* seeded here — those are granted the first time the user picks a file, because that
 * pick is the consent.
 *
 * The work runs on an application-scoped coroutine so a slow first-run disk write cannot
 * delay the first frame of the UI, and so it cannot be cancelled by an Activity going away.
 */
@HiltAndroidApp
class VoiceAssistantApplication : Application() {

    @Inject
    lateinit var decisionLog: DecisionLog

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            runCatching {
                decisionLog.seedDefaultGrants()
                decisionLog.note(
                    capability = Capability.FILE_READ,
                    action = "log.opened",
                    subject = decisionLog.logFile?.name ?: "decisions.jsonl",
                    because = "Every capability decision from now on is appended to this hash-chained file.",
                )
            }
        }
    }
}
