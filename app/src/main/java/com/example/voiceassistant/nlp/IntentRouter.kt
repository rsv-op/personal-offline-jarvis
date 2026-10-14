package com.example.voiceassistant.nlp

data class Evaluation(
    val intent: AppIntent,
    val confidence: Float,
    val sensitivity: IntentSensitivity
)

class IntentRouter {
    private val flashlightPattern = Regex(".*(?:flashlight|torch).*")
    private val alarmPattern = Regex(".*(?:alarm|wake me up).*")

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
            return Evaluation(
                intent = AppIntent.ToggleFlashlight(isOn = !text.contains("off")),
                confidence = 1.0f,
                sensitivity = IntentSensitivity.LOW
            )
        }

        if (text.matches(alarmPattern)) {
            val extractedTime = extractTime(text)
            return Evaluation(
                intent = AppIntent.SetAlarm(extractedTime),
                confidence = if (extractedTime == null) 0.4f else 1.0f,
                sensitivity = IntentSensitivity.LOW
            )
        }

        return Evaluation(AppIntent.UnknownIntent, 0.0f, IntentSensitivity.LOW)
    }

    private fun extractTime(text: String): String? {
        var processedText = text
        timeMap.forEach { (word, number) ->
            processedText = processedText.replace(Regex("\\b${Regex.escape(word)}\\b"), number)
        }

        val match = Regex("\\b([0-9]{1,2})(?:[: ]([0-9]{2}))?\\s*(am|pm)?\\b").find(processedText)
            ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        if (hour !in 0..23) return null
        val minutes = match.groupValues[2].ifEmpty { "00" }
        val amPm = match.groupValues[3]
        return "${hour.toString().padStart(2, '0')}:$minutes${if (amPm.isNotEmpty()) " $amPm" else ""}"
    }
}
