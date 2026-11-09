package com.example.voiceassistant.stt

import android.content.Context
import kotlinx.coroutines.flow.SharedFlow
import org.robolectric.RuntimeEnvironment

/**
 * A real [AudioCaptureSource] whose microphone is a switch.
 *
 * Both `start()` and `stop()` are **suspend** functions, and that makes them impossible to
 * stub with Mockito: `doNothing()` rejects them outright ("Only void methods can
 * doNothing()!"), and a bare `mock()` answers the call with `null` instead of resuming the
 * caller's continuation, so the test coroutine never continues and the test JVM spins
 * forever with no output. The `whenever(call())` spelling appears to work — it compiles and
 * the stub is registered — but a mocked suspend function returns a *live* state machine,
 * not the `Unit` the stub returns, and the caller wedges anyway.
 *
 * So the microphone is faked for real instead of mocked. The class needs a [Context] to
 * construct, which is why the tests using it run under Robolectric; nothing else here
 * touches the framework. The capture loop is never started — [start] only records the call
 * and flips a flag — so no `AudioRecord`, no permission, no audio thread.
 *
 * Usage:
 * ```
 * val capture = FakeAudioCaptureSource()
 * val controller = ContinuousWakeController(capture, integrator, gate, scope) { 0L }
 * ...
 * assertTrue(capture.isStarted)
 * ```
 */
class FakeAudioCaptureSource : AudioCaptureSource(RuntimeEnvironment.getApplication()) {

    /** How many times [start] was called. */
    var startCalls: Int = 0
        private set

    /** How many times [stop] was called. */
    var stopCalls: Int = 0
        private set

    /** True between a successful [start] and the next [stop]. */
    var isStarted: Boolean = false
        private set

    /** When set, [start] throws it instead of opening — "the microphone is busy". */
    var startError: Throwable? = null

    /**
     * Replaces the frame flow, for a test that wants to push audio into the recogniser.
     * The real flow stays empty here: nothing is capturing.
     */
    var framesOverride: SharedFlow<ShortArray>? = null

    /** Replaces the error flow. */
    var errorsOverride: SharedFlow<Throwable>? = null

    override val frames: SharedFlow<ShortArray>
        get() = framesOverride ?: super.frames

    override val errors: SharedFlow<Throwable>
        get() = errorsOverride ?: super.errors

    override suspend fun start() {
        startCalls++
        startError?.let { throw it }
        isStarted = true
    }

    override suspend fun stop() {
        stopCalls++
        isStarted = false
    }
}
