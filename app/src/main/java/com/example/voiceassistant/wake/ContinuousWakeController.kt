package com.example.voiceassistant.wake

import android.util.Log
import com.example.voiceassistant.stt.AudioCaptureSource
import com.example.voiceassistant.stt.OfflineSttIntegrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hands-free standby: the microphone stays open, but only a wake word is acted on.
 *
 * The controller is deliberately thin. It owns three things and nothing else:
 *
 *  1. **The listening session.** It starts and stops the capture + recogniser pair as one
 *     unit, so there is no state where the mic is open but nothing consumes it (a battery
 *     leak) or where the recogniser is running with no microphone (a silent assistant).
 *  2. **The wake window.** [WakeSessionGate] decides whether an unprefixed transcript is
 *     acted on; this class keeps the UI state in step with that decision and closes the
 *     window again when it expires.
 *  3. **The interrupt.** `"shaz"` — and any final transcript that is the stop word — is
 *     delivered to [Callbacks.onInterrupt] *before* anything else happens, so the audio
 *     buffer is flushed on the same frame the word was recognised.
 *
 * It performs no generation, no playback and no persistence: every one of those is a
 * callback, which is what lets the whole wake cycle be tested with a fake recogniser and no
 * microphone (`ContinuousWakeControllerTest`).
 */
class ContinuousWakeController(
    private val capture: AudioCaptureSource,
    private val integrator: OfflineSttIntegrator,
    val gate: WakeSessionGate,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    interface Callbacks {
        /** A wake word opened the window ("hey heretic"). */
        fun onWakeAcknowledged()

        /** A command should run. [wokeNow] is true when the wake word was in this utterance. */
        fun onCommand(command: String, wokeNow: Boolean)

        /** `"shaz"` (or an explicit stop phrase): flush playback and halt generation now. */
        fun onInterrupt()

        /** Unprefixed room speech outside the window was dropped. */
        fun onIgnoredAmbient(text: String)

        /** Running partial hypothesis, for the live transcript line. */
        fun onPartial(text: String)

        /** A user-facing problem (recogniser failure, microphone busy). */
        fun onProblem(message: String)
    }

    private val _state = MutableStateFlow(WakeState.OFF)
    val state: StateFlow<WakeState> = _state.asStateFlow()

    private val _lastWakeAtMs = MutableStateFlow(0L)
    val lastWakeAtMs: StateFlow<Long> = _lastWakeAtMs.asStateFlow()

    private val _wakeCount = MutableStateFlow(0)
    val wakeCount: StateFlow<Int> = _wakeCount.asStateFlow()

    /** Most recent partial whose [WakeWordDetector.partialScore] crossed the threshold. */
    private val _partialArmed = MutableStateFlow(false)
    val partialArmed: StateFlow<Boolean> = _partialArmed.asStateFlow()

    @Volatile
    private var callbacks: Callbacks? = null

    @Volatile
    private var listening = false

    @Volatile
    private var busy = false

    private var windowJob: Job? = null

    val isListening: Boolean get() = listening

    /** True when a command may be accepted without repeating the wake word. */
    fun isArmed(): Boolean = gate.isArmed(clock())

    fun remainingWindowMs(): Long = gate.remainingMs(clock())

    /**
     * Opens the microphone and starts listening for the wake word.
     *
     * The recogniser must already be loaded ([OfflineSttIntegrator.initializeModel]); the
     * caller owns that, because it is the same model the push-to-talk path uses.
     */
    suspend fun start(callbacks: Callbacks): Boolean {
        if (listening) {
            this.callbacks = callbacks
            return true
        }
        this.callbacks = callbacks
        return try {
            capture.start()
            integrator.startTranscription()
            listening = true
            _state.value = if (gate.isArmed(clock())) WakeState.ARMED else WakeState.STANDBY
            scheduleWindowExpiry()
            true
        } catch (error: Throwable) {
            listening = false
            _state.value = WakeState.OFF
            Log.w(TAG, "Continuous listening could not start: ${error.message}")
            callbacks.onProblem(
                error.message ?: "The microphone could not be opened for hands-free listening"
            )
            false
        }
    }

    /** Closes the microphone and tears the session down. Safe to call when already stopped. */
    suspend fun stop() {
        windowJob?.cancel()
        windowJob = null
        gate.sleep()
        _partialArmed.value = false
        if (!listening) {
            _state.value = WakeState.OFF
            return
        }
        listening = false
        busy = false
        _state.value = WakeState.OFF
        runCatching { capture.stop() }
        runCatching { integrator.stopTranscription(emitFinalResult = false) }
    }

    /** Marks the assistant as answering, so a stray transcript cannot cut it off. */
    fun markBusy(isBusy: Boolean) {
        busy = isBusy
        refreshState()
    }

    /** Pushes a partial hypothesis into the acoustic matcher. */
    fun onPartialHypothesis(partial: String) {
        callbacks?.onPartial(partial)
        if (!gate.wakeWordRequired) return
        val score = WakeWordDetector.partialScore(partial)
        _partialArmed.value = score >= WakeWordDetector.PARTIAL_MATCH_THRESHOLD
    }

    /**
     * Feeds one final transcript through the wake gate.
     *
     * This is the method the recogniser's endpoint calls, and it is the only place a
     * transcript can become an action while listening hands-free.
     */
    fun onFinalTranscript(rawText: String, confidence: Float = 1f) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        when (val decision = gate.evaluate(
            rawTranscript = text,
            fromContinuousCapture = listening,
            confidence = confidence,
            nowMs = clock(),
        )) {
            WakeSessionGate.Decision.Ignore -> {
                _partialArmed.value = false
                callbacks?.onIgnoredAmbient(text)
                _state.value = if (listening) WakeState.STANDBY else WakeState.OFF
            }

            WakeSessionGate.Decision.Stop -> {
                _partialArmed.value = false
                callbacks?.onInterrupt()
                refreshState()
            }

            WakeSessionGate.Decision.Woke -> {
                _partialArmed.value = false
                _lastWakeAtMs.value = clock()
                _wakeCount.value = _wakeCount.value + 1
                callbacks?.onWakeAcknowledged()
                scheduleWindowExpiry()
                refreshState()
            }

            is WakeSessionGate.Decision.Run -> {
                _partialArmed.value = false
                if (decision.wokeNow) {
                    _lastWakeAtMs.value = clock()
                    _wakeCount.value = _wakeCount.value + 1
                }
                callbacks?.onCommand(decision.command, decision.wokeNow)
                scheduleWindowExpiry()
                refreshState()
            }
        }
    }

    /** Re-arms the window from an external trigger (the wake chip in the UI). */
    fun armNow() {
        gate.arm(clock())
        _lastWakeAtMs.value = clock()
        _wakeCount.value = _wakeCount.value + 1
        scheduleWindowExpiry()
        refreshState()
    }

    fun sleepNow() {
        gate.sleep()
        _partialArmed.value = false
        refreshState()
    }

    /** Sets whether unprefixed speech needs the wake word. */
    fun setWakeWordRequired(required: Boolean) {
        gate.wakeWordRequired = required
        if (!required) gate.sleep()
        refreshState()
    }

    /** Recomputes [state] from the gate, the microphone and the busy flag. */
    fun refreshState() {
        // Pass the controller's clock: the gate must judge "armed" on the same time base
        // the rest of the loop uses, or the chip disagrees with the window.
        _state.value = gate.state(busy = busy, listening = listening, nowMs = clock())
    }

    /**
     * Keeps the UI honest about the window closing.
     *
     * Without this the chip would say "listening for a command" forever, because nothing
     * else polls the gate. The job sleeps for exactly the remaining window and then drops
     * the state back to [WakeState.STANDBY].
     */
    private fun scheduleWindowExpiry() {
        windowJob?.cancel()
        if (!listening) return
        val remaining = gate.remainingMs(clock())
        if (remaining <= 0L) {
            refreshState()
            return
        }
        windowJob = scope.launch {
            delay(remaining + WINDOW_SLACK_MS)
            if (isActive) {
                _partialArmed.value = false
                refreshState()
            }
        }
    }

    private companion object {
        const val TAG = "JarvisWake"

        /** Extra wait so the state flip never lands a millisecond before the window closes. */
        const val WINDOW_SLACK_MS = 50L
    }
}
