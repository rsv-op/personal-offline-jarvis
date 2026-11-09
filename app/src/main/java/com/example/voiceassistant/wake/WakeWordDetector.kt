package com.example.voiceassistant.wake

/**
 * Text-side wake-word detection: *"did what the recogniser heard amount to the wake word?"*
 *
 * This mirrors the reference `WakeWordDetector` from Heretic v3.44 — the same three
 * answers (wake, wake-with-command, or nothing) and the same tolerance for filler before
 * the name — with two additions that matter on a phone:
 *
 *  * the accepted forms are **"Heretic"**, **"Hey Heretic"** and **"Jarvis"** (the app's own
 *    name), because users say the name of the thing in front of them;
 *  * the immediate stop word **"shaz"** is matched here too, so a barge-in never depends on
 *    the recogniser's capitalisation or on a punctuation mark.
 *
 * The class is pure Kotlin and covered by `WakeWordDetectorTest`; the audio capture, the
 * recogniser and the playback flush live in [ContinuousWakeWordEngine] and
 * [com.example.voiceassistant.execution.TtsController].
 */
object WakeWordDetector {

    /** Spoken names that open the command window. */
    val WAKE_PHRASES = listOf("heretic", "jarvis")

    /** Optional words people put in front of the name. */
    private val LEADING_FILLER = listOf(
        "hey", "hi", "hello", "ok", "okay", "yo", "wake up", "listen", "oi",
    )

    /** The absolute interrupt word. Anything after it is ignored. */
    const val STOP_KEYWORD = "shaz"

    /** Extra stop words, in the same spirit but unambiguous enough to be safe. */
    private val STOP_PHRASES = listOf(
        "shaz", "stop talking", "stop speaking", "be quiet", "shut up", "cancel speech",
    )

    /** How long a wake opens the hands-free window for. Matches the reference (20 s). */
    const val DEFAULT_AWAKE_WINDOW_MS = 20_000L

    /** The recogniser's own score below which a match is treated as room noise. */
    const val MIN_TEXT_CONFIDENCE = 0.35f

    sealed interface Result {
        /** "shaz": flush playback and halt every generation pipeline, right now. */
        data object ImmediateStop : Result

        /** Only the wake word: open the window and acknowledge. */
        data object WakeOnly : Result

        /** "Hey Heretic, what's on my calendar": wake *and* run the command. */
        data class WakeWithCommand(val command: String) : Result

        /** Ordinary speech with no wake word in it. */
        data class Plain(val text: String) : Result
    }

    /**
     * Classifies one final transcript.
     *
     * @param confidence the recogniser's own confidence for this utterance, when it has one.
     *   A wake word heard at [MIN_TEXT_CONFIDENCE] or below is not trusted: an ambient
     *   "heretic" from a podcast must not open the microphone.
     */
    fun detect(rawTranscript: String, confidence: Float = 1f): Result {
        val text = normalise(rawTranscript)
        if (text.isEmpty()) return Result.Plain("")
        if (confidence < MIN_TEXT_CONFIDENCE) return Result.Plain(rawTranscript.trim())

        if (isStopPhrase(text)) return Result.ImmediateStop

        for (name in WAKE_PHRASES) {
            val match = matchName(text, name) ?: continue
            val remainder = match.trim().trimStart(',', ':', ';', '-', '.', '!', '?', ' ')
            if (remainder.isEmpty()) return Result.WakeOnly
            if (isStopPhrase(remainder)) return Result.ImmediateStop
            return Result.WakeWithCommand(remainder)
        }
        return Result.Plain(rawTranscript.trim())
    }

    /** `true` when [rawTranscript] is the wake word, with or without a command. */
    fun isWake(rawTranscript: String): Boolean = when (val result = detect(rawTranscript)) {
        is Result.WakeOnly, is Result.WakeWithCommand -> true
        else -> false
    }

    /** `true` when [rawTranscript] is the absolute interrupt word. */
    fun isStop(rawTranscript: String): Boolean =
        isStopPhrase(normalise(rawTranscript))

    /**
     * Acoustic-side matcher used when the recogniser returns a *partial* hypothesis.
     *
     * A streaming recogniser emits "her", "here", "hectic" on the way to "heretic", so an
     * exact match on a partial would never fire. This returns a 0..1 score of how close the
     * tail of [partial] is to any wake phrase, using a bounded Levenshtein distance. A score
     * at or above [PARTIAL_MATCH_THRESHOLD] is enough to *arm* the window; only a final
     * transcript actually opens it (see [ContinuousWakeWordEngine]).
     */
    fun partialScore(partial: String): Float {
        val text = normalise(partial)
        if (text.isEmpty()) return 0f
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return 0f
        // Compare against the last one and the last two tokens: "he heret" vs "heretic".
        val candidates = buildList {
            add(tokens.last())
            if (tokens.size >= 2) add(tokens[tokens.size - 2] + tokens.last())
        }
        var best = 0f
        for (name in WAKE_PHRASES) {
            for (candidate in candidates) {
                val distance = editDistance(candidate, name)
                val ratio = 1f - distance.toFloat() / maxOf(candidate.length, name.length)
                // A prefix of the name is *progress*, not a near-miss: "heret" is five
                // sevenths of "heretic" and still two edits away, so the Levenshtein ratio
                // alone (0.57) never arms the chip while the user is still saying the word.
                // Prefixes are scored on how much of the name has arrived, with a floor high
                // enough that a two- or three-letter fragment of ordinary speech ("her",
                // "he") does not flicker the chip.
                val prefixRatio = if (name.startsWith(candidate) && candidate.isNotEmpty()) {
                    PREFIX_SCORE_BASE + (1f - PREFIX_SCORE_BASE) * candidate.length / name.length
                } else {
                    0f
                }
                if (ratio > best) best = ratio
                if (prefixRatio > best) best = prefixRatio
            }
        }
        return best.coerceIn(0f, 1f)
    }

    /** Score needed before a partial hypothesis is treated as a probable wake word. */
    const val PARTIAL_MATCH_THRESHOLD = 0.78f

    /**
     * Score floor for a candidate that is a prefix of a wake word. With 0.5, a prefix arms
     * the chip once it covers ~55% of the name ("here" of "heretic"), while "her" stays
     * below the threshold: the chip is feedback, and false positives make it noise.
     */
    private const val PREFIX_SCORE_BASE = 0.5f

    // --------------------------------------------------------------- internals

    /** Lower-cases, strips punctuation, and collapses whitespace. */
    fun normalise(raw: String): String = raw
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s'’-]"), " ")
        .replace(Regex("[\\s]+"), " ")
        .trim()

    /**
     * True when [text] is a stop phrase *in full*.
     *
     * The match is anchored on purpose. An unanchored `startsWith` let the trailing half of
     * the rule ("shaz") also match the *leading* half of an ordinary sentence, so a reply
     * beginning with the name was classified as an interrupt and dropped: "Heretic, what is
     * the battery level" became `ImmediateStop` before the name matcher ever ran.
     */
    private fun isStopPhrase(text: String): Boolean =
        STOP_PHRASES.any { phrase -> text == phrase || text.endsWith(" $phrase") }

    /**
     * Matches "heretic", "hey heretic", "okay jarvis", "heretics", "hey, heretic!".
     *
     * @return whatever followed the name (possibly empty), or `null` when the name is not
     *   at the start of the utterance. The name must be at the *start*: "I read a book about
     *   heretic sects" must not wake the assistant.
     */
    private fun matchName(text: String, name: String): String? {
        var remaining = text
        var strippedAny = true
        while (strippedAny) {
            strippedAny = false
            for (filler in LEADING_FILLER) {
                if (remaining.startsWith("$filler ")) {
                    remaining = remaining.removePrefix("$filler ").trimStart()
                    strippedAny = true
                }
            }
        }

        if (!remaining.startsWith(name)) return null
        val after = remaining.substring(name.length).trimStart()
        if (after.isEmpty()) return ""

        // The recogniser sometimes emits the plural or the possessive ("heretics",
        // "heretic's"). Those still count; any other continuation does not, so the word
        // "heretical" in a sentence cannot open the microphone.
        return when {
            after.startsWith("'") || after.startsWith("\u2019") ->
                after.drop(1).trimStart().removePrefix("s").trimStart()
            after == "s" -> ""
            after.startsWith("s ") -> after.removePrefix("s").trimStart()
            else -> null
        }
    }

    /** Bounded Levenshtein distance; enough for short wake words. */
    internal fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}

/** Where the assistant is in the wake cycle; drives the status line and the orb. */
enum class WakeState {
    /** No microphone capture; nothing is being listened for. */
    OFF,

    /** Capture is running, but only the wake word will be acted on. */
    STANDBY,

    /** The wake word was heard; commands are accepted until the window closes. */
    ARMED,

    /** A command is being transcribed / executed. */
    BUSY,
}

/**
 * The time-boxed hands-free window.
 *
 * Copied in behaviour from the reference `WakeWordSessionGate`, and extended in one way:
 * [remainingMs] is exposed so the UI can show the window closing, and [lastActivityMs] is
 * refreshed by every accepted command, so a user who keeps talking never has to repeat the
 * wake word mid-conversation.
 */
class WakeSessionGate(
    var wakeWordRequired: Boolean = true,
    private val awakeWindowMs: Long = WakeWordDetector.DEFAULT_AWAKE_WINDOW_MS,
) {
    private var awakeUntil = 0L
    private var armedAt = 0L

    @Synchronized
    fun isArmed(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < awakeUntil

    @Synchronized
    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long =
        (awakeUntil - nowMs).coerceAtLeast(0L)

    @Synchronized
    fun arm(nowMs: Long = System.currentTimeMillis()) {
        awakeUntil = nowMs + awakeWindowMs
        armedAt = nowMs
    }

    @Synchronized
    fun sleep() {
        awakeUntil = 0L
        armedAt = 0L
    }

    /**
     * The state to show for [busy]/[listening] at [nowMs].
     *
     * [nowMs] is a parameter, not a call to the wall clock: the controller drives this class
     * with an injectable clock, and reading `System.currentTimeMillis()` here made the two
     * disagree — under a test clock the gate was armed for 20 s of gate-time while `state()`
     * compared against wall time and reported STANDBY, so the chip lied.
     */
    @Synchronized
    fun state(
        busy: Boolean,
        listening: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): WakeState = when {
        !listening -> WakeState.OFF
        busy -> WakeState.BUSY
        isArmed(nowMs) -> WakeState.ARMED
        else -> WakeState.STANDBY
    }

    /** How long the current window has been open, for the "listening for 12 s" label. */
    @Synchronized
    fun armedSinceMs(): Long = armedAt

    /** The decision for one final transcript, combining the text matcher and the window. */
    @Synchronized
    fun evaluate(
        rawTranscript: String,
        fromContinuousCapture: Boolean,
        confidence: Float = 1f,
        nowMs: Long = System.currentTimeMillis(),
    ): Decision {
        when (val result = WakeWordDetector.detect(rawTranscript, confidence)) {
            WakeWordDetector.Result.ImmediateStop -> return Decision.Stop

            WakeWordDetector.Result.WakeOnly -> {
                arm(nowMs)
                return Decision.Woke
            }

            is WakeWordDetector.Result.WakeWithCommand -> {
                arm(nowMs)
                return Decision.Run(result.command, wokeNow = true)
            }

            is WakeWordDetector.Result.Plain -> {
                val text = result.text.trim()
                if (text.isEmpty()) return Decision.Ignore
                if (!fromContinuousCapture || !wakeWordRequired) {
                    return Decision.Run(text, wokeNow = false)
                }
                if (isArmed(nowMs)) {
                    arm(nowMs) // keep the window open while the user keeps talking
                    return Decision.Run(text, wokeNow = false)
                }
                return Decision.Ignore
            }
        }
    }

    sealed interface Decision {
        /** Unprefixed speech outside the window: drop it silently. */
        data object Ignore : Decision

        /** "shaz": halt playback and generation. */
        data object Stop : Decision

        /** The name alone: open the window and acknowledge. */
        data object Woke : Decision

        /** Run [command]; [wokeNow] is true when the wake word was in this same utterance. */
        data class Run(val command: String, val wokeNow: Boolean) : Decision
    }
}
