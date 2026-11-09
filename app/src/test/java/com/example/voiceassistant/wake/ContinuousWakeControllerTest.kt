package com.example.voiceassistant.wake

import com.example.voiceassistant.stt.AudioCaptureSource
import com.example.voiceassistant.stt.FakeAudioCaptureSource
import com.example.voiceassistant.stt.OfflineSttIntegrator
import com.example.voiceassistant.stt.SherpaOnnxModelStore
import com.example.voiceassistant.stt.SttEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.mock

/**
 * The hands-free loop, driven by a fake recogniser: no microphone, no model, no playback.
 *
 * What these tests pin down, in the order a user hits them:
 *
 *  1. starting the loop opens the microphone **and** the recogniser as one unit;
 *  2. a wake word alone opens the window and says "yes", while ordinary room speech is
 *     dropped rather than executed;
 *  3. "hey heretic, <command>" wakes *and* runs the command in one utterance;
 *  4. "shaz" reaches [ContinuousWakeController.Callbacks.onInterrupt] and nothing else,
 *     even while the assistant is busy speaking — this is the absolute interrupt, so it
 *     must not be gated by the busy flag;
 *  5. the wake window closes on its own, so the chip in the UI cannot lie about it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
// Robolectric is only here for the fake microphone: `FakeAudioCaptureSource` extends the
// real `AudioCaptureSource`, whose constructor wants a `Context`. It never touches
// `AudioRecord`, so nothing audio-related runs.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContinuousWakeControllerTest {

    private class RecordingCallbacks : ContinuousWakeController.Callbacks {
        val wakes = mutableListOf<Unit>()
        val commands = mutableListOf<Pair<String, Boolean>>()
        var interrupts = 0
        val ignored = mutableListOf<String>()
        val partials = mutableListOf<String>()
        val problems = mutableListOf<String>()

        override fun onWakeAcknowledged() {
            wakes += Unit
        }

        override fun onCommand(command: String, wokeNow: Boolean) {
            commands += command to wokeNow
        }

        override fun onInterrupt() {
            interrupts++
        }

        override fun onIgnoredAmbient(text: String) {
            ignored += text
        }

        override fun onPartial(text: String) {
            partials += text
        }

        override fun onProblem(message: String) {
            problems += message
        }
    }

    /**
     * A controller wired to a real [OfflineSttIntegrator].
     *
     * The integrator is *not* mocked: `startTranscription()`/`stopTranscription()` are
     * final, plain functions that only flip an internal `AtomicBoolean`, and mocking them
     * would need a mockito-inline agent the rest of the suite does not use. A real one over
     * a mock [SttEngine] exercises the same state the app's microphone session does, which
     * is what the assertions below are about.
     */
    private fun controller(
        scope: TestScope,
        capture: AudioCaptureSource = fakeCapture(),
        gate: WakeSessionGate = WakeSessionGate(),
        clock: () -> Long = { 0L },
        integrator: OfflineSttIntegrator = realIntegrator(scope),
    ): ContinuousWakeController {
        val built = ContinuousWakeController(
            capture = capture,
            integrator = integrator,
            gate = gate,
            scope = scope,
            clock = clock,
        )
        started.add(built)
        return built
    }

    /**
     * Controllers created by this test, so [tearDown] can close their microphone sessions.
     *
     * A controller that is still `listening` holds a collector on the capture flow, which
     * is exactly the kind of leaked coroutine `runTest` waits on: without this teardown a
     * test that never calls `stop()` would leave the scope with live children and the test
     * would block until the 60 s default timeout — or, under a real clock, appear to hang.
     */
    private val started = mutableListOf<ContinuousWakeController>()

    @After
    fun tearDown() {
        runBlocking {
            started.forEach { controller -> runCatching { controller.stop() } }
        }
        started.clear()
    }

    /**
     * The fake microphone.
     *
     * See [FakeAudioCaptureSource] for why this is a real object and not a Mockito mock:
     * `start()`/`stop()` are suspend functions, and a mocked suspend function cannot be
     * made to resume its caller, which used to hang the whole test JVM.
     *
     * [startError] makes `start()` fail the way a busy microphone does.
     */
    private fun fakeCapture(startError: Throwable? = null): FakeAudioCaptureSource =
        FakeAudioCaptureSource().apply { this.startError = startError }

    private fun realIntegrator(scope: TestScope): OfflineSttIntegrator {
        val engine = mock<SttEngine>()
        val integrator = OfflineSttIntegrator(
            engine = engine,
            // A flow nobody emits into: the integrator's collector still runs, which is
            // what the assertions below observe.
            audioStream = kotlinx.coroutines.flow.MutableSharedFlow<ShortArray>(extraBufferCapacity = 1),
            frameRecycler = {},
            eventDispatcher = {},
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        // `startTranscription()` refuses to run without a loaded model, exactly as it does
        // in the app, so the fake engine has to be initialized first.
        kotlinx.coroutines.runBlocking { integrator.initializeModel("/fake/model") }
        return integrator
    }

    @Test
    fun `start opens the microphone and the recogniser together`() = runTest {
        val capture = fakeCapture()
        val integrator = realIntegrator(this)
        val controller = controller(this, capture = capture, integrator = integrator)

        assertTrue(controller.start(RecordingCallbacks()))

        assertTrue(controller.isListening)
        assertEquals("the microphone must be opened", 1, capture.startCalls)
        assertTrue(capture.isStarted)
        assertTrue("the recogniser must run whenever the mic is open", integrator.isTranscribingNow)
        assertEquals(WakeState.STANDBY, controller.state.value)
    }

    @Test
    fun `start reports a problem and stays off when the microphone fails`() = runTest {
        val capture = fakeCapture(startError = IllegalStateException("microphone busy"))
        val controller = controller(this, capture = capture)
        val callbacks = RecordingCallbacks()

        assertFalse(controller.start(callbacks))
        assertFalse(controller.isListening)
        assertEquals(WakeState.OFF, controller.state.value)
        assertEquals(listOf("microphone busy"), callbacks.problems)
    }

    @Test
    fun `the bare wake word opens the window and is acknowledged`() = runTest {
        val controller = controller(this)
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)

        controller.onFinalTranscript("hey heretic")

        assertEquals(1, callbacks.wakes.size)
        assertTrue(callbacks.commands.isEmpty())
        assertTrue(controller.isArmed())
        assertEquals(WakeState.ARMED, controller.state.value)
        assertEquals(1, controller.wakeCount.value)
    }

    @Test
    fun `ambient speech without the wake word is ignored, not executed`() = runTest {
        val controller = controller(this, gate = WakeSessionGate(wakeWordRequired = true))
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)

        controller.onFinalTranscript("turn on the kitchen lights")

        assertTrue(callbacks.commands.isEmpty())
        assertEquals(listOf("turn on the kitchen lights"), callbacks.ignored)
        assertFalse(controller.isArmed())
    }

    @Test
    fun `wake word and command in one utterance runs the command`() = runTest {
        val controller = controller(this)
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)

        controller.onFinalTranscript("hey heretic, what is on my calendar")

        assertEquals(listOf("what is on my calendar" to true), callbacks.commands)
        // No separate acknowledgement: [ContinuousWakeController.Callbacks.onWakeAcknowledged]
        // makes the assistant say "Yes?" and wait, and saying that *while* running the
        // command that came with the name would be it talking over itself. The wake is
        // still counted, which is what the chip shows.
        assertTrue(
            "a wake-with-command must not also ask \"Yes?\"",
            callbacks.wakes.isEmpty(),
        )
        assertEquals(1, controller.wakeCount.value)
    }

    @Test
    fun `a command inside an already open window does not need the wake word again`() = runTest {
        val controller = controller(this)
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)

        controller.onFinalTranscript("heretic")
        controller.onFinalTranscript("set a timer for ten minutes")

        assertEquals(
            listOf("set a timer for ten minutes" to false),
            callbacks.commands,
        )
    }

    @Test
    fun `shaz is the absolute interrupt and fires even while the assistant is busy`() = runTest {
        val controller = controller(this)
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)
        controller.markBusy(true)

        controller.onFinalTranscript("shaz")

        assertEquals(1, callbacks.interrupts)
        assertTrue(callbacks.commands.isEmpty())
        assertTrue(callbacks.ignored.isEmpty())
    }

    @Test
    fun `shaz also interrupts when no wake word was ever heard`() = runTest {
        val controller = controller(this, gate = WakeSessionGate(wakeWordRequired = true))
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)

        controller.onFinalTranscript("shaz")

        assertEquals(1, callbacks.interrupts)
        // A stop word is not a wake: the window stays shut and the mic stays open.
        assertFalse(controller.isArmed())
        assertEquals(0, controller.wakeCount.value)
    }

    @Test
    fun `markBusy keeps the state busy while an answer is playing`() = runTest {
        val controller = controller(this)
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)
        controller.onFinalTranscript("heretic")

        controller.markBusy(true)

        assertEquals(WakeState.BUSY, controller.state.value)
    }

    @Test
    fun `the wake window closes on its own`() = runTest {
        var now = 1_000L
        val controller = controller(this, clock = { now })
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)

        controller.onFinalTranscript("heretic")
        assertTrue(controller.isArmed())

        now += WakeWordDetector.DEFAULT_AWAKE_WINDOW_MS + 10
        advanceTimeBy(WakeWordDetector.DEFAULT_AWAKE_WINDOW_MS + 100)
        runCurrent()

        assertFalse(controller.isArmed())
        assertEquals(WakeState.STANDBY, controller.state.value)
    }

    @Test
    fun `stop closes the microphone and the window`() = runTest {
        val capture = fakeCapture()
        val integrator = realIntegrator(this)
        val controller = controller(this, capture = capture, integrator = integrator)
        controller.start(RecordingCallbacks())
        controller.onFinalTranscript("heretic")
        assertTrue(integrator.isTranscribingNow)

        controller.stop()

        assertFalse(controller.isListening)
        assertFalse(controller.isArmed())
        assertFalse(integrator.isTranscribingNow)
        assertEquals(WakeState.OFF, controller.state.value)
        assertEquals("the microphone must be released", 1, capture.stopCalls)
        assertFalse(capture.isStarted)
    }

    @Test
    fun `a partial hypothesis arms the chip without opening the window`() = runTest {
        val controller = controller(this)
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)

        controller.onPartialHypothesis("heret")

        assertTrue(controller.partialArmed.value)
        assertEquals(listOf("heret"), callbacks.partials)
        assertFalse("a partial must never open the window", controller.isArmed())
    }

    @Test
    fun `requiring no wake word runs every transcript`() = runTest {
        val controller = controller(this, gate = WakeSessionGate(wakeWordRequired = false))
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)

        controller.onFinalTranscript("what is the battery level")

        assertEquals(listOf("what is the battery level" to false), callbacks.commands)
    }

    @Test
    fun `turning the requirement off mid-session stops dropping ambient speech`() = runTest {
        val controller = controller(this)
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)
        controller.onFinalTranscript("turn the light on")
        assertTrue(callbacks.commands.isEmpty())

        controller.setWakeWordRequired(false)
        controller.onFinalTranscript("turn the light on")

        assertEquals(listOf("turn the light on" to false), callbacks.commands)
    }

    @Test
    fun `armNow from the wake chip opens the window`() = runTest {
        val controller = controller(this)
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)

        controller.armNow()
        advanceUntilIdle()

        assertTrue(controller.isArmed())
        assertEquals(1, controller.wakeCount.value)
        assertTrue(callbacks.wakes.isEmpty())
    }

    @Test
    fun `sleepNow closes the window immediately`() = runTest {
        val controller = controller(this)
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)
        controller.armNow()

        controller.sleepNow()

        assertFalse(controller.isArmed())
        assertEquals(0L, controller.remainingWindowMs())
    }

    @Test
    fun `a stop while listening keeps the loop alive`() = runTest {
        val controller = controller(this)
        val callbacks = RecordingCallbacks()
        controller.start(callbacks)
        controller.markBusy(true)

        controller.onFinalTranscript("shaz")

        assertEquals("a partial must never wake the assistant", 0, callbacks.wakes.size)
        assertTrue("shaz must not tear down the microphone", controller.isListening)
        assertEquals(1, callbacks.interrupts)
    }
}
