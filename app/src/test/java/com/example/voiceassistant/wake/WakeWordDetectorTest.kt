package com.example.voiceassistant.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wake gate.
 *
 * The rules under test are the ones that decide whether the assistant acts on something it
 * overheard: the name must be at the *start* of the utterance, the filler in front of it is
 * optional, `"shaz"` always wins, and unprefixed speech outside the window is dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeWordDetectorTest {

    // ------------------------------------------------------------- the name

    @Test
    fun `the name alone is a wake`() {
        assertEquals(WakeWordDetector.Result.WakeOnly, WakeWordDetector.detect("Heretic"))
        assertEquals(WakeWordDetector.Result.WakeOnly, WakeWordDetector.detect("heretic."))
        assertEquals(WakeWordDetector.Result.WakeOnly, WakeWordDetector.detect("Jarvis"))
    }

    @Test
    fun `filler in front of the name is accepted`() {
        listOf(
            "hey heretic",
            "Hey, Heretic!",
            "okay jarvis",
            "hi jarvis",
            "wake up heretic",
        ).forEach { utterance ->
            assertEquals(
                "expected a wake for \"$utterance\"",
                WakeWordDetector.Result.WakeOnly,
                WakeWordDetector.detect(utterance),
            )
        }
    }

    @Test
    fun `a name followed by a command wakes and keeps the command`() {
        val result = WakeWordDetector.detect("Hey Heretic, what is the battery level")
        assertEquals(WakeWordDetector.Result.WakeWithCommand("what is the battery level"), result)

        val second = WakeWordDetector.detect("Jarvis: turn on the flashlight")
        assertEquals(WakeWordDetector.Result.WakeWithCommand("turn on the flashlight"), second)
    }

    @Test
    fun `the name in the middle of a sentence is not a wake`() {
        val utterance = "I was reading about heretic sects yesterday"
        assertTrue(WakeWordDetector.detect(utterance) is WakeWordDetector.Result.Plain)
        assertFalse(WakeWordDetector.isWake(utterance))
    }

    @Test
    fun `a longer word that starts with the name is not a wake`() {
        val result = WakeWordDetector.detect("heretical opinions are common")
        assertTrue(result is WakeWordDetector.Result.Plain)
    }

    @Test
    fun `a low-confidence transcript is not trusted`() {
        val result = WakeWordDetector.detect("heretic", confidence = 0.1f)
        assertTrue(result is WakeWordDetector.Result.Plain)
        // The same words at full confidence are a wake: it is the score that is rejected.
        assertTrue(WakeWordDetector.isWake("heretic"))
    }

    // ------------------------------------------------------------- shaz

    @Test
    fun `shaz is always an immediate stop`() {
        assertEquals(WakeWordDetector.Result.ImmediateStop, WakeWordDetector.detect("shaz"))
        assertEquals(WakeWordDetector.Result.ImmediateStop, WakeWordDetector.detect("Shaz!"))
        assertEquals(WakeWordDetector.Result.ImmediateStop, WakeWordDetector.detect("stop talking"))
        assertTrue(WakeWordDetector.isStop("SHAZ"))
    }

    @Test
    fun `a stop word after the wake word still stops`() {
        assertEquals(WakeWordDetector.Result.ImmediateStop, WakeWordDetector.detect("Heretic, shaz"))
        assertEquals(WakeWordDetector.Result.ImmediateStop, WakeWordDetector.detect("hey jarvis stop talking"))
    }

    // ------------------------------------------------------------- the window

    @Test
    fun `unprefixed speech is ignored outside the window and accepted inside it`() {
        var now = 1_000L
        val gate = WakeSessionGate(wakeWordRequired = true, awakeWindowMs = 20_000L)

        assertEquals(
            WakeSessionGate.Decision.Ignore,
            gate.evaluate("what time is it", fromContinuousCapture = true, nowMs = now),
        )

        assertEquals(
            WakeSessionGate.Decision.Woke,
            gate.evaluate("Heretic", fromContinuousCapture = true, nowMs = now),
        )
        assertTrue(gate.isArmed(now))

        // Inside the window an ordinary sentence runs.
        val inside = gate.evaluate("what time is it", fromContinuousCapture = true, nowMs = now + 1_000)
        assertEquals(WakeSessionGate.Decision.Run("what time is it", wokeNow = false), inside)

        // The window *slides*: an accepted command re-arms it from the moment it was heard,
        // so a user who keeps talking never repeats the name. `fromContinuousCapture` was
        // written as a positional fourth argument and silently landed in `confidence`, so
        // this evaluated as a push-to-talk utterance (which never needs the wake word) and
        // the lapse below could not happen.
        now += 19_999
        assertEquals(
            WakeSessionGate.Decision.Run("what time is it", wokeNow = false),
            gate.evaluate("what time is it", fromContinuousCapture = true, nowMs = now),
        )

        // Once it lapses — 20 s after the *last* thing heard — the same sentence is ignored.
        now += 20_001
        assertEquals(
            WakeSessionGate.Decision.Ignore,
            gate.evaluate("what time is it", fromContinuousCapture = true, nowMs = now),
        )
    }

    @Test
    fun `open mic mode accepts unprefixed speech`() {
        val gate = WakeSessionGate(wakeWordRequired = false)
        val decision = gate.evaluate(
            rawTranscript = "turn the lights on",
            fromContinuousCapture = true,
            confidence = 1f,
            nowMs = 0L,
        )
        assertEquals(WakeSessionGate.Decision.Run("turn the lights on", wokeNow = false), decision)
    }

    @Test
    fun `push-to-talk speech needs no wake word`() {
        val gate = WakeSessionGate(wakeWordRequired = true)
        val decision = gate.evaluate(
            rawTranscript = "what is my battery",
            fromContinuousCapture = false,
            confidence = 1f,
            nowMs = 0L,
        )
        assertEquals(WakeSessionGate.Decision.Run("what is my battery", wokeNow = false), decision)
    }

    @Test
    fun `the window state is reported for the UI`() {
        // `now` is 5_000: nowhere near the wall clock, which is the point — the gate must
        // report the state for the time it is *given*, not for the system clock, or the
        // chip in the UI disagrees with the window the controller armed.
        val now = 5_000L
        val gate = WakeSessionGate(awakeWindowMs = 20_000L)
        assertEquals(WakeState.OFF, gate.state(busy = false, listening = false, nowMs = now))
        assertEquals(WakeState.STANDBY, gate.state(busy = false, listening = true, nowMs = now))
        gate.arm(now)
        assertEquals(WakeState.ARMED, gate.state(busy = false, listening = true, nowMs = now))
        assertEquals(WakeState.BUSY, gate.state(busy = true, listening = true, nowMs = now))
        assertEquals(20_000L, gate.remainingMs(now))
        gate.sleep()
        assertEquals(WakeState.STANDBY, gate.state(busy = false, listening = true, nowMs = now))
        assertEquals(0L, gate.remainingMs(now))
    }

    // ------------------------------------------------- acoustic partials

    @Test
    fun `an incomplete hypothesis is scored close to the name`() {
        val score = WakeWordDetector.partialScore("he heretic")
        assertTrue("score was $score", score >= WakeWordDetector.PARTIAL_MATCH_THRESHOLD)
        assertTrue(WakeWordDetector.partialScore("and then we") < 0.5f)
        assertTrue(WakeWordDetector.partialScore("") == 0f)
    }

    @Test
    fun `a partial on its way to the name arms the chip, but a two-letter fragment does not`() {
        // "here" is 4/7 of "heretic": a prefix, not a near miss, so it must score high even
        // though the edit distance from "here" to "heretic" is three.
        val partial = WakeWordDetector.partialScore("here")
        assertTrue("score was $partial", partial >= WakeWordDetector.PARTIAL_MATCH_THRESHOLD)
        assertTrue(
            "a bare fragment must not flicker the chip",
            WakeWordDetector.partialScore("her") < WakeWordDetector.PARTIAL_MATCH_THRESHOLD,
        )
    }

    @Test
    fun `a wake word followed by a command is not mistaken for a stop phrase`() {
        // Regression: an unanchored stop-phrase match treated any sentence *starting* with
        // "shaz" — including "Heretic, shaz …" — correctly, but it also classified a
        // name-first sentence as an interrupt through the trailing half of the rule.
        val result = WakeWordDetector.detect("Heretic, what is the battery level")
        assertEquals(WakeWordDetector.Result.WakeWithCommand("what is the battery level"), result)
        // The stop word still wins wherever it really is the whole utterance.
        assertEquals(WakeWordDetector.Result.ImmediateStop, WakeWordDetector.detect("shaz"))
    }

    @Test
    fun `the edit distance is exact for short words`() {
        assertEquals(0, WakeWordDetector.editDistance("heretic", "heretic"))
        assertEquals(1, WakeWordDetector.editDistance("heretic", "heretick"))
        assertEquals(2, WakeWordDetector.editDistance("jarvis", "jervos"))
    }
}
