package com.example.voiceassistant.rag

/**
 * Splits documents into tokenised pages and decides which files are worth indexing.
 *
 * Everything here is pure Kotlin: no Android types, no I/O. That is deliberate — the
 * chunking rules are the part of the RAG engine that decides what the model is allowed to
 * see, so they are covered by plain JVM unit tests (`RagChunkerTest`) instead of only by
 * an on-device click-through.
 */
object RagChunker {

    /**
     * Target size of one page. ~900 characters is roughly 230 English tokens: small enough
     * that three excerpts plus the question still leave most of a 2 048-token llama.cpp
     * context for the answer, and large enough that a page carries a whole argument rather
     * than half a sentence.
     */
    const val DEFAULT_CHARS_PER_PAGE = 900

    /** Pages are never split below this, even if a single sentence is longer. */
    private const val MIN_SPLIT_CHARS = 200

    /** Shortest shared stem that counts as an inflection ("deploy"/"deployment"). */
    private const val MIN_STEM_LENGTH = 5

    /**
     * Function words that carry no retrieval signal. Kept deliberately short: aggressive
     * stop-word lists hurt short queries ("what does the alarm do") more than they help.
     */
    private val STOP_WORDS = setOf(
        "the", "and", "for", "that", "this", "with", "from", "what", "where", "when",
        "which", "who", "whom", "whose", "how", "why", "are", "was", "were", "been",
        "is", "be", "do", "does", "did", "have", "has", "had", "can", "could", "would",
        "should", "will", "shall", "may", "might", "must", "about", "into", "onto",
        "your", "yours", "you", "our", "ours", "their", "theirs", "there", "here",
        "any", "all", "some", "such", "then", "than", "them", "they", "its", "it",
        "of", "to", "in", "on", "at", "by", "as", "an", "or", "if", "so", "not", "no",
        "but", "also", "very", "just", "only", "more", "most", "much", "many", "me",
        "my", "mine", "we", "us", "he", "she", "his", "her", "him", "i", "a", "s",
        "tell", "please", "explain", "describe", "say", "know",
    )

    /**
     * Extensions the SAF importer accepts.
     *
     * The list is closed on purpose: `HereticCore`/`FileAssessor` only grades formats the
     * app has an integration path for, and an unbounded set (a multi-gigabyte video, a
     * `.gguf` model picked by mistake) would burn the whole import budget on text that
     * cannot be read.
     */
    val SUPPORTED_EXTENSIONS = setOf(
        "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "log", "ini", "conf",
        "yaml", "yml", "toml", "xml", "html", "htm", "kt", "kts", "java", "py", "js",
        "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cc", "go", "rs", "rb", "php",
        "sql", "sh", "gradle", "properties", "pdf", "zip", "rst", "tex", "srt", "vtt",
    )

    /** Archives are expanded in place; their entries are filtered with the same rules. */
    val ARCHIVE_EXTENSIONS = setOf("zip")

    /** Files larger than this are skipped: a 50 MB text dump is not a knowledge base. */
    const val MAX_DOCUMENT_BYTES = 8L * 1024L * 1024L

    /** One archive is bounded so a zip bomb cannot exhaust storage or memory. */
    const val MAX_ARCHIVE_ENTRIES = 512
    const val MAX_ARCHIVE_UNCOMPRESSED_BYTES = 48L * 1024L * 1024L

    /**
     * True when a file with this name/MIME pair should be read into the index.
     *
     * @param name display name, possibly a relative path inside an archive.
     * @param mimeType MIME type reported by the SAF provider, or `""` when unknown
     *   (archive entries have no provider).
     */
    fun isSupported(name: String, mimeType: String = ""): Boolean {
        val leaf = leafName(name)
        if (leaf.isEmpty() || leaf.startsWith(".")) return false
        // Googles's own archive output is full of __MACOSX/._entry resource forks.
        if (name.contains("__MACOSX/")) return false
        val ext = extensionOf(leaf)
        if (ext.isNotEmpty()) return ext in SUPPORTED_EXTENSIONS
        // No extension: fall back to the provider's MIME type.
        return mimeType.startsWith("text/") || mimeType == "application/json"
    }

    fun isArchive(name: String): Boolean = extensionOf(leafName(name)) in ARCHIVE_EXTENSIONS

    fun leafName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').trim()

    fun extensionOf(fileName: String): String =
        if (fileName.contains('.')) fileName.substringAfterLast('.').lowercase() else ""

    /** Removes every directory component, for display and for archive-entry paths. */
    fun relativePathOf(path: String): String =
        path.replace('\\', '/').trimStart('/').removePrefix("./")

    /**
     * Splits [text] into pages of about [charsPerPage] characters.
     *
     * Splitting rules, in order:
     *
     *  1. An explicit form feed (`\u000C`) always starts a new page — that is how the text
     *     extracted from a PDF keeps the original page boundaries, so "page 7" in the query
     *     means page 7 of the document, not the seventh 900-character block.
     *  2. A page longer than [charsPerPage] is cut on the last paragraph, sentence, or word
     *     break before the limit, whichever is closest to the limit; a single unbroken run
     *     of characters is cut at the limit.
     *  3. Parts of one source page share the page number and are distinguished by
     *     [RagChunk.partIndex], so "notes.md page 2" still resolves.
     */
    fun chunk(
        sourcePath: String,
        text: String,
        charsPerPage: Int = DEFAULT_CHARS_PER_PAGE,
    ): List<RagChunk> {
        val limit = charsPerPage.coerceAtLeast(MIN_SPLIT_CHARS)
        val cleaned = normaliseWhitespace(text)
        if (cleaned.isEmpty()) return emptyList()

        val path = relativePathOf(sourcePath)
        val documentPages = cleaned.split('\u000C')
        val chunks = ArrayList<RagChunk>(documentPages.size.coerceAtMost(64))

        var pageNumber = 0
        for (rawPage in documentPages) {
            val page = rawPage.trim()
            if (page.isEmpty()) continue
            pageNumber++
            val parts = splitPage(page, limit)
            parts.forEachIndexed { index, part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@forEachIndexed
                val effectivePage = if (documentPages.size == 1 && parts.size > 1) {
                    pageNumber + index
                } else {
                    pageNumber
                }
                val effectivePart = if (documentPages.size == 1 && parts.size > 1) 1 else index + 1
                chunks += RagChunk(
                    sourcePath = path,
                    pageNumber = effectivePage,
                    partIndex = effectivePart,
                    content = trimmed,
                    tokens = tokenize("$path page $effectivePage $trimmed"),
                )
            }
        }
        return chunks
    }

    /** The term set used for ranking. See the class comment for why the path is included. */
    fun tokenize(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val out = HashSet<String>(64)
        val builder = StringBuilder(24)
        for (index in 0..text.length) {
            val c = if (index == text.length) ' ' else text[index]
            if (c.isLetterOrDigit()) {
                builder.append(c.lowercaseChar())
                continue
            }
            if (builder.isEmpty()) continue
            val token = builder.toString()
            builder.setLength(0)
            when {
                token.length < 2 -> Unit
                token in STOP_WORDS -> Unit
                // Pure numbers are kept: they are page numbers, model sizes, ports.
                token.all { it.isDigit() } -> out += token
                else -> out += token
            }
        }
        return out
    }

    /**
     * True when [queryToken] and [chunkToken] should count as the same term.
     *
     * Exact matches always count. Otherwise a shared stem of five or more characters counts
     * ("deploy" / "deployment", "transcribe" / "transcription"), which recovers most of the
     * benefit of a stemmer without a stemmer's cost or its false positives ("art" / "arts"
     * stay separate because they are shorter than the threshold).
     */
    fun tokensMatch(queryToken: String, chunkToken: String): Boolean {
        if (queryToken == chunkToken) return true
        val shorter = minOf(queryToken.length, chunkToken.length)
        if (shorter < MIN_STEM_LENGTH) return false
        return queryToken.startsWith(chunkToken) || chunkToken.startsWith(queryToken)
    }

    private fun splitPage(page: String, limit: Int): List<String> {
        if (page.length <= limit) return listOf(page)
        val parts = ArrayList<String>(page.length / limit + 1)
        var start = 0
        while (start < page.length) {
            var end = minOf(start + limit, page.length)
            if (end < page.length) {
                end = bestBreak(page, start, end)
            }
            val part = page.substring(start, end).trim()
            if (part.isNotEmpty()) parts += part
            start = end
            while (start < page.length && page[start].isWhitespace()) start++
        }
        return parts
    }

    /** Chooses the nearest natural break at or before [hardEnd]. */
    private fun bestBreak(text: String, start: Int, hardEnd: Int): Int {
        // Only look back a limited distance; a shorter excerpt is better than a wrong one.
        val windowStart = maxOf(start + MIN_SPLIT_CHARS, hardEnd - 300)
        for (i in hardEnd downTo windowStart) {
            if (text[i - 1] == '\n') return i
        }
        for (i in hardEnd downTo windowStart) {
            if (text[i - 1] in ".!?;") return i
        }
        for (i in hardEnd downTo windowStart) {
            if (text[i - 1].isWhitespace()) return i
        }
        return hardEnd
    }

    /** Collapses runs of spaces/tabs but keeps paragraph breaks and form feeds. */
    private fun normaliseWhitespace(text: String): String {
        val noNul = text.replace('\u0000', ' ')
        val sb = StringBuilder(noNul.length)
        var lastWasSpace = false
        var lastWasNewline = 0
        for (c in noNul) {
            when {
                c == '\u000C' -> {
                    sb.append(c)
                    lastWasSpace = true
                    lastWasNewline = 0
                }
                c == '\r' -> Unit
                c == '\n' -> {
                    lastWasNewline++
                    if (lastWasNewline <= 2) sb.append('\n')
                    lastWasSpace = true
                }
                c == ' ' || c == '\t' -> {
                    if (!lastWasSpace) sb.append(' ')
                    lastWasSpace = true
                }
                else -> {
                    sb.append(c)
                    lastWasSpace = false
                    lastWasNewline = 0
                }
            }
        }
        return sb.toString().trim()
    }
}
