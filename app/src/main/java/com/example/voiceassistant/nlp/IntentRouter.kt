package com.example.voiceassistant.nlp

data class Evaluation(
    val intent: AppIntent,
    val confidence: Float,
    val sensitivity: IntentSensitivity
)

/**
 * Deterministic, rule-based first pass over an utterance.
 *
 * Rules deliberately answer only what they can answer *safely*: when a phrase is
 * ambiguous (for example "flashlight is not needed anymore" carries no on/off word)
 * the router reports no match and lets the on-device intent brain decide, which is
 * trained on exactly that kind of phrasing.
 */
class IntentRouter {

    private val flashlightPattern = Regex(".*(?:flashlight|torch).*")
    private val timerPattern = Regex(".*(?:timer|countdown|count down).*")
    private val alarmPattern = Regex(".*(?:alarm|wake me up).*")
    private val deletePattern = Regex(
        ".*(?:delete|erase|remove|wipe|throw away|get rid of).*" +
            "(?:file|files|document|documents|note|notes|download|downloads|draft|photo|photos).*"
    )
    private val statusPattern = Regex(
        ".*(?:battery|charging|charge|storage|free space|device status|phone status|" +
            "system status|are you online|what can you do|what model are you|your name|" +
            "how much memory).*"
    )
    // "what is Priya's number" is a contact lookup, so the possessive form
    // has to be recognised here too. A bare "number" is deliberately NOT a
    // trigger: "what is the serial number of the phone" is not a lookup.
    private val contactPattern = Regex(
        ".*(?:contact|contacts|phone number|number for|'s number|'s phone number|call|ring|dial).*"
    )

    private val offWords = listOf("off", "out", "kill", "disable", "stop", "shut", "dark", "dim")
    private val onWords = listOf("on", "enable", "start", "bright", "light up", "power up")

    private val articles = setOf(
        "the", "my", "this", "that", "a", "an", "it", "please", "named", "called",
        "your", "our", "their"
    )

    private val fileTypeWords = setOf(
        "file", "files", "document", "documents", "note", "notes", "download",
        "downloads", "draft", "photo", "photos"
    )

    private val deleteVerbs = setOf(
        "delete", "erase", "remove", "wipe", "throw", "away", "get", "rid", "of", "trash"
    )

    /**
     * Words that look like a contact name but identify nobody. A request
     * containing only these cannot be routed to the contacts tool.
     */
    private val nonNames = setOf(
        "me", "my", "him", "her", "them", "us", "it", "someone", "somebody", "anyone",
        "contact", "contacts", "the", "a", "an", "that", "this", "there"
    )

    /**
     * Words that carry no meaning for a name and are ignored when deciding
     * whether a phrase identifies somebody.
     */
    private val fillerWords = setOf("of", "for", "please", "and", "on", "in", "is", "the")

    private val timeMap = linkedMapOf(
        "forty five" to "45",
        "thirty" to "30",
        "fifteen" to "15",
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
        "eleven" to "11", "twelve" to "12"
    )

    fun route(text: String): Evaluation {
        if (text.matches(flashlightPattern)) {
            when (val isOn = polarity(text)) {
                null -> Unit // Ambiguous: let the intent brain decide.
                else -> return Evaluation(
                    intent = AppIntent.ToggleFlashlight(isOn = isOn),
                    confidence = 1.0f,
                    sensitivity = IntentSensitivity.LOW
                )
            }
        }

        if (text.matches(timerPattern)) {
            val seconds = DurationParser.parseSeconds(text)
            return Evaluation(
                intent = AppIntent.SetTimer(seconds),
                confidence = if (seconds == null) 0.4f else 1.0f,
                sensitivity = IntentSensitivity.LOW
            )
        }

        if (text.matches(alarmPattern)) {
            val extractedTime = extractAlarmTime(text)
            return Evaluation(
                intent = AppIntent.SetAlarm(extractedTime),
                confidence = if (extractedTime == null) 0.4f else 1.0f,
                sensitivity = IntentSensitivity.LOW
            )
        }

        if (text.matches(deletePattern)) {
            return Evaluation(
                intent = AppIntent.DeleteFile(guessFileName(text)),
                confidence = 0.8f,
                sensitivity = IntentSensitivity.HIGH
            )
        }

        if (text.matches(statusPattern)) {
            return Evaluation(
                intent = AppIntent.StatusReport,
                confidence = 0.9f,
                sensitivity = IntentSensitivity.LOW
            )
        }

        if (text.matches(contactPattern)) {
            val contactName = extractContactName(text)
            if (contactName != null) {
                return Evaluation(
                    intent = AppIntent.LookUpContact(contactName),
                    confidence = 0.85f,
                    sensitivity = IntentSensitivity.LOW
                )
            }
        }

        return Evaluation(AppIntent.UnknownIntent, 0.0f, IntentSensitivity.LOW)
    }

    /**
     * Pulls the person's name out of a contact lookup request.
     *
     * "what's Priya's number" -> "Priya"; "look up a contact called John Smith"
     * -> "John Smith"; "call mom" -> "mom" (which is how the contact is
     * probably saved).
     */
    fun extractContactName(text: String): String? {
        val lower = text.lowercase()

        val calledIndex = lower.indexOf("called")
        if (calledIndex >= 0) {
            val rest = text.substring(calledIndex + "called".length).trim()
            if (rest.isNotEmpty()) return rest.trim('.').trim()
        }

        // "'s number" / "'s phone number" -> take the possessive name.
        val possessive = Regex("([A-Za-z][A-Za-z'\\-]*)'s\\s+(?:phone\\s+)?number").find(text)
        if (possessive != null) return possessive.groupValues[1]

        val forIndex = lower.indexOf("number for")
        if (forIndex >= 0) {
            val rest = text.substring(forIndex + "number for".length).trim()
            if (rest.isNotEmpty()) return rest.trim('.').trim()
        }

        // "look up <name>" / "find <name>" / "call <name>" / "dial <name>"
        val verb = Regex("(?:look up|lookup|find|call|ring|dial)\\s+(?:a\\s+contact\\s+)?(.+)")
            .find(text)
        if (verb != null) {
            var name = verb.groupValues[1].trim().trim('.')
            if (name.startsWith("contact ", ignoreCase = true)) {
                name = name.substring("contact ".length).trim()
            }
            // A bare pronoun or determiner is not a name: "call my contact"
            // does not identify anybody, so the request cannot be routed.
            if (name.isNotEmpty() && !isNonName(name)) return name
        }

        return null
    }

    /**
     * True when [name] identifies nobody — it is made up only of pronouns,
     * determiners and the word "contact" itself.
     */
    private fun isNonName(name: String): Boolean {
        val words = name.lowercase().split(' ', '.', ',')
            .filter { it.isNotBlank() }
            .filterNot { it in fillerWords }
        if (words.isEmpty()) return true
        return words.all { it in nonNames }
    }

    /** `true` = turn on, `false` = turn off, `null` = the utterance does not say. */
    fun polarity(text: String): Boolean? {
        if (offWords.any { word -> text.containsWholeWord(word) }) return false
        if (onWords.any { word -> text.containsWholeWord(word) }) return true
        return null
    }

    /**
     * "seven thirty am" -> "07:30 am"; `null` when the utterance holds no *real* clock time.
     *
     * Impossible times are rejected rather than passed on, because the caller reacts to
     * `null` by asking for the time again:
     *
     * - minutes outside 0–59 ("alarm at 12:99" is not a time),
     * - hours outside 0–23,
     * - 12-hour phrasing with an impossible hour ("13 pm", "0 am").
     *
     * The 12-hour → 24-hour conversion itself belongs to [AlarmExecutor.parseTime], which
     * understands the am/pm suffix this method preserves.
     */
    fun extractAlarmTime(text: String): String? {
        var processedText = text
        timeMap.forEach { (word, number) ->
            processedText = processedText.replace(Regex("\\b${Regex.escape(word)}\\b"), number)
        }

        val match = Regex("\\b([0-9]{1,2})(?:[: ]([0-9]{2}))?\\s*(am|pm)?\\b").find(processedText)
            ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        if (hour !in 0..23) return null
        val minutes = match.groupValues[2].ifEmpty { "00" }
        if ((minutes.toIntOrNull() ?: return null) !in 0..59) return null
        val amPm = match.groupValues[3]
        if (amPm.isNotEmpty() && hour !in 1..12) return null
        return "${hour.toString().padStart(2, '0')}:$minutes${if (amPm.isNotEmpty()) " $amPm" else ""}"
    }

    /**
     * Best-effort file name for the (intentionally unexecuted) delete intent.
     *
     * "delete my notes file" -> "notes", "remove file named report" -> "report",
     * "delete the download" -> "download".
     */
    fun guessFileName(text: String): String {
        val words = text.split(' ').filter(String::isNotBlank)

        for (index in words.indices) {
            if (words[index] !in fileTypeWords || index == 0) continue
            val candidate = words[index - 1]
            if (candidate !in articles && candidate !in deleteVerbs) return candidate
        }

        return words.lastOrNull { it !in articles && it !in deleteVerbs } ?: "that file"
    }

    private fun String.containsWholeWord(word: String): Boolean =
        Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(this)
}
