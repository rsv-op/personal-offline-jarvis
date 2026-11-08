package com.example.voiceassistant.rag

/**
 * Turns a spoken or typed request into structured retrieval parameters.
 *
 * The interesting cases are the *target filters*, because they are what make a local
 * knowledge base feel exact rather than approximate:
 *
 * | said                                   | target file      | page |
 * | -------------------------------------- | ---------------- | ---- |
 * | `notes.md page 2`                      | `notes.md`       | 2    |
 * | `from the deployment doc, page 3`      | `deployment`     | 3    |
 * | `what does the manifesto say`          | `manifesto`      | —    |
 * | `say that again`                       | — (no filter)    | —    |
 *
 * "page N" is only treated as a page filter when a page word is present, so the model
 * sizes in a note ("context 2048") are not mistaken for page 2048.
 */
data class RagQuery(
    val question: String,
    val fileFilter: String?,
    val pageNumber: Int?,
    val rawQuery: String,
) {
    val hasTarget: Boolean get() = fileFilter != null || pageNumber != null
    val isEmpty: Boolean get() = question.isBlank()

    fun describeTarget(): String = when {
        fileFilter != null && pageNumber != null -> "$fileFilter, page $pageNumber"
        pageNumber != null -> "page $pageNumber"
        fileFilter != null -> fileFilter
        else -> ""
    }

    companion object {
        private val PAGE_REGEX = Regex("""\bpage\s*#?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)

        private val FILE_HINT_REGEX = Regex(
            """\b(?:in|from|inside|within|of|about)\s+(?:the\s+|my\s+|this\s+|that\s+)?""" +
                """([\p{L}\p{N}_\-. ]{2,60}?)""" +
                """\s+(?:file|document|doc|notes?|page|pdf|paper|article|manifesto|book|report)\b""",
            RegexOption.IGNORE_CASE,
        )

        private val EXPLICIT_FILE_REGEX = Regex(
            """([\p{L}\p{N}_\-. ]{1,60}?\.(?:txt|md|markdown|pdf|json|csv|kt|java|py|xml|html|log|yml|yaml|tsv))""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Files that people say by name but never write with an extension. The right-hand
         * side is what the index lookup will look for inside the source path.
         */
        private val NAMED_DOCUMENTS = listOf(
            "manifesto" to "manifesto",
            "readme" to "readme",
            "changelog" to "changelog",
            "license" to "license",
            "licence" to "license",
            "the guide" to "guide",
            "the manual" to "manual",
            "the roadmap" to "roadmap",
        )

        private val LEADING_REFERENCE = Regex(
            """^(?:in|from|inside|within|according\s+to|check|look\s+in|read)\s+""" +
                """(?:the\s+|my\s+|this\s+|that\s+)?""",
            RegexOption.IGNORE_CASE,
        )

        private val TRAILING_INSTRUCTION = Regex(
            """\s*(?:please|thanks|thank\s+you)\s*[.!?]*\s*$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Some things the assistant can be asked that are *not* document questions. They
         * are routed before retrieval so a note containing the word "battery" cannot
         * hijack "how much battery is left".
         */
        private val NON_RETRIEVAL = Regex(
            """^(?:turn|switch|set|start|cancel|stop|delete|forget|open|close|teach|call)\b""",
            RegexOption.IGNORE_CASE,
        )

        fun parse(raw: String): RagQuery {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return RagQuery("", null, null, raw)
            val question = TRAILING_INSTRUCTION.replace(trimmed, "").trim()

            val page = PAGE_REGEX.find(question)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val explicitFile = EXPLICIT_FILE_REGEX.find(question)
                ?.groupValues?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.length >= 3 }

            val hintedFile = FILE_HINT_REGEX.find(question)
                ?.groupValues?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.length >= 3 }

            val namedDocument = NAMED_DOCUMENTS.firstOrNull { (spoken, _) ->
                question.lowercase().contains(spoken)
            }?.second

            return RagQuery(
                question = question,
                fileFilter = explicitFile ?: hintedFile ?: namedDocument,
                pageNumber = page,
                rawQuery = raw,
            )
        }

        /**
         * True when the request is an instruction for the device rather than a question
         * about the user's documents. Such a request must never drag document excerpts into
         * a confirmation prompt.
         */
        fun looksLikeDeviceCommand(raw: String): Boolean =
            NON_RETRIEVAL.containsMatchIn(LEADING_REFERENCE.replace(raw.trim(), ""))
    }
}
