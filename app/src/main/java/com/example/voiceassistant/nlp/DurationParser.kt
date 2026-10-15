package com.example.voiceassistant.nlp

/**
 * Small offline duration parser for timer requests.
 *
 * Handles digits and spoken numbers plus seconds/minutes/hours, including
 * "half an hour" and the common "set a timer for ten" (minutes) shorthand.
 * Everything is deterministic and local — no dependency on the speech engine's
 * idea of numbers.
 */
object DurationParser {

    private val wordNumbers: Map<String, Int> = linkedMapOf(
        "forty five" to 45,
        "thirty" to 30,
        "twenty" to 20,
        "fifteen" to 15,
        "ninety" to 90,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty five" to 25, "twenty one" to 21, "twenty two" to 22,
        "twenty three" to 23, "twenty four" to 24, "ten and a half" to 10
    )

    private val unitPattern = Regex(
        "\\b(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b"
    )

    /** Duration in seconds, or `null` when the utterance carries no usable amount. */
    fun parseSeconds(text: String): Int? {
        val normalized = text.lowercase()
        if (normalized.contains("half an hour") || normalized.contains("half hour")) {
            return 30 * 60
        }
        if (Regex("\\b(an hour|one hour|a hour)\\b").containsMatchIn(normalized)) {
            return 60 * 60
        }

        val amount = findAmount(normalized) ?: return null
        val unit = unitPattern.find(normalized)?.value
        val multiplier = when {
            unit == null -> 60 // "set a timer for ten" almost always means minutes
            unit.startsWith("sec") -> 1
            unit.startsWith("min") -> 60
            else -> 60 * 60
        }
        return amount * multiplier
    }

    /** Formats seconds as "ten minutes" style text for spoken confirmations. */
    fun describe(seconds: Int): String {
        if (seconds <= 0) return "no time"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        val parts = mutableListOf<String>()
        if (hours > 0) parts += plural(hours, "hour")
        if (minutes > 0) parts += plural(minutes, "minute")
        if (remainingSeconds > 0 && hours == 0) parts += plural(remainingSeconds, "second")
        return parts.joinToString(" and ")
    }

    private fun findAmount(text: String): Int? {
        Regex("\\b([0-9]{1,3})\\b").find(text)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
        for ((word, value) in wordNumbers) {
            if (Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(text)) return value
        }
        return null
    }

    private fun plural(value: Int, unit: String): String =
        if (value == 1) "$value $unit" else "$value ${unit}s"
}
