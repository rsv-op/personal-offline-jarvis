package com.example.voiceassistant.rag

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Minimal, dependency-free text extraction for the formats the importer accepts.
 *
 * There is no PDF library in this APK on purpose: a PDF renderer is 10–20 MB and needs an
 * `INTERNET`-free native toolchain build, while the text layer of most documents is plain
 * Flate-compressed content streams. This extractor:
 *
 *  * parses the cross-reference-free way — it scans for `stream`/`endstream` pairs and
 *    inflates them with `java.util.zip.Inflater` (already in the platform);
 *  * reads the `BT … ET` text objects inside each stream and decodes the `Tj`, `TJ` and
 *    `'` / `"` operators, including the PDF string escapes and `\ooo` octal;
 *  * honours the page boundary by emitting a form feed between streams that contain text,
 *    which is exactly what [RagChunker] uses as a hard page break;
 *  * decompresses the Unicode CMaps (`/ToUnicode`) that Type0/CID fonts use, so text from
 *    modern PDFs is not turned into `\u0000\u0001\u0002` garbage;
 *  * falls back to a printable-character sweep when a stream cannot be inflated, so a
 *    scanned-but-OCRed PDF still contributes its text layer.
 *
 * It is honest about its limits: a PDF with no text layer at all (a pure scan) yields
 * nothing, and the importer says so instead of indexing binary noise.
 */
object TextExtractors {

    /** Characters kept when nothing else works; see [pdf]. */
    private const val MIN_PDF_TEXT_RUN = 24

    /**
     * Extracts text from [bytes] according to [fileName]'s extension.
     *
     * [onPageBreak] is called when a hard page boundary is known (PDF), returning the
     * characters to emit at that boundary — `\u000C` inside a string is not portable to
     * every toolchain, so the caller passes it explicitly.
     */
    fun extractText(fileName: String, bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when (RagChunker.extensionOf(RagChunker.leafName(fileName))) {
            "pdf" -> pdf(bytes)
            "html", "htm", "xml" -> stripMarkup(decodeEntities(decodeText(bytes)))
            "json", "jsonl" -> decodeText(bytes)
            else -> decodeText(bytes)
        }
    }

    /**
     * Decodes bytes that are *probably* UTF-8 but may be Latin-1.
     *
     * A strict UTF-8 round trip is used as the test: real UTF-8 text decodes identically,
     * whereas Latin-1 text (a Windows-1252 export) turns into replacement characters.
     */
    fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        val replacementRatio = utf8.count { it == '\uFFFD' }.toDouble() / utf8.length.coerceAtLeast(1)
        val text = if (replacementRatio > 0.002) String(bytes, Charsets.ISO_8859_1) else utf8
        return text.replace("\u0000", " ")
    }

    // ------------------------------------------------------------------ markup

    private val SCRIPT_OR_STYLE = Regex(
        """<(script|style|noscript)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_TAG = Regex(
        """</?(?:p|div|br|li|tr|h[1-6]|section|article|header|footer|pre|blockquote|table|ul|ol|dl|dt|dd)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val ANY_TAG = Regex("""<[^>]{1,400}>""")
    private val ENTITIES = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'",
        "&nbsp;" to " ", "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
        "&rsquo;" to "’", "&lsquo;" to "‘", "&ldquo;" to "“", "&rdquo;" to "”",
    )
    private val NUMERIC_ENTITY = Regex("""&#(\d{1,6});""")

    /** Reads an HTML/XML document as text, dropping tags and script bodies. */
    fun stripMarkup(raw: String): String {
        var text = SCRIPT_OR_STYLE.replace(raw, " ")
        text = COMMENT.replace(text, " ")
        text = BLOCK_TAG.replace(text, "\n")
        text = ANY_TAG.replace(text, " ")
        text = decodeEntities(text)
        return text
            .replace(Regex("[ \\t\\u00A0]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun decodeEntities(raw: String): String {
        var text = raw
        for ((entity, value) in ENTITIES) text = text.replace(entity, value)
        text = NUMERIC_ENTITY.replace(text) { match ->
            match.groupValues[1].toIntOrNull()
                ?.takeIf { it in 32..0x10FFFF }
                ?.let { String(Character.toChars(it)) }
                ?: " "
        }
        return text
    }

    // --------------------------------------------------------------------- PDF

    /** Extracts the text layer of a PDF, one form feed per content stream that had text. */
    fun pdf(bytes: ByteArray): String {
        val pdf = String(bytes, Charsets.ISO_8859_1)
        val charMap = pdfStringDecoder(pdf)
        val pages = ArrayList<String>(16)

        var cursor = 0
        while (true) {
            val streamIndex = pdf.indexOf("stream", cursor)
            if (streamIndex < 0) break
            var contentStart = streamIndex + "stream".length
            if (contentStart < pdf.length && pdf[contentStart] == '\r') contentStart++
            if (contentStart < pdf.length && pdf[contentStart] == '\n') contentStart++
            val endIndex = pdf.indexOf("endstream", contentStart)
            if (endIndex < 0 || endIndex <= contentStart) {
                cursor = contentStart
                continue
            }
            // The dictionary that describes this stream lives just before it.
            val dictStart = maxOf(0, streamIndex - 2_048)
            val dictionary = pdf.substring(dictStart, streamIndex)
            cursor = endIndex + "endstream".length

            // A `/ToUnicode` CMap is a text stream full of `(...)` strings, but none of
            // them is page content — they are the *mapping* from glyph codes to Unicode.
            // Treating one as a page both invents text and hides a genuinely empty page,
            // so it is skipped like a font or an xref stream.
            if (isUnicodeCMap(dictionary) || UNICODE_CMAP_HEADER.containsMatchIn(dictionary)) continue

            // A /Length that matches the remaining bytes means a photograph, a font or an
            // xref stream, not page content: skip those without inflating them.
            val declaredLength = Regex("/Length\\s+(\\d{1,9})").find(dictionary)
                ?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (declaredLength != null && declaredLength > 4L * 1024L * 1024L) continue
            if (DCT_FILTER.containsMatchIn(dictionary)) continue

            val raw = bytes.copyOfRange(contentStart, endIndex)
            if (raw.size > MAX_PDF_STREAM_BYTES) continue
            val inflated = inflate(raw)
            if (inflated.isEmpty()) continue
            val body = String(inflated, Charsets.ISO_8859_1)
            val text = readTextOperators(body, charMap)
            if (text.isNotBlank()) pages += text
        }

        if (pages.isNotEmpty()) return pages.joinToString("\u000C")

        // No stream produced text. Sweeping the raw bytes is the last resort for a PDF
        // whose content is stored uncompressed and unlabelled — but it also happily
        // returns `endstream`, `obj` and binary noise, which is worse than nothing: the
        // index would quote garbage back at the user. So the sweep runs only when the file
        // does not look like a PDF at all; a real PDF with no text layer is a scanned
        // document and correctly yields "".
        if (looksLikePdf(pdf)) return ""
        val swept = sweepPrintable(bytes)
        return if (swept.length >= MIN_PDF_TEXT_RUN) swept else ""
    }

    private val DCT_FILTER = Regex("""/Filter\s*(?:\[\s*)?/(DCTDecode|JPXDecode|CCITTFaxDecode|JBIG2Decode)""")

    /**
     * The header every `/ToUnicode` CMap stream starts with.
     *
     * The dictionary `/Name` is only a convention — producers routinely inline the CMap
     * and name the object something else — so the header text is the reliable signal.
     */
    private val UNICODE_CMAP_HEADER = Regex("/CIDInit|beginbfchar|beginbfrange")

    private fun isUnicodeCMap(dictionary: String): Boolean =
        dictionary.contains("/ToUnicode") || dictionary.contains("/CMapName")

    /** True when [pdf] is a PDF with at least one `obj … endobj` body. */
    private fun looksLikePdf(pdf: String): Boolean =
        pdf.startsWith("%PDF") || pdf.contains(" endobj") || pdf.contains("%%EOF")
    private val MAX_PDF_STREAM_BYTES = 16 * 1024 * 1024

    /** Inflates one stream body when it is Flate-compressed; returns it unchanged otherwise. */
    private fun inflate(body: ByteArray): ByteArray {
        if (body.size < 2 || body[0] != 0x78.toByte()) return body
        return try {
            val inflater = Inflater()
            inflater.setInput(body)
            val out = ByteArrayOutputStream(minOf(body.size * 4, 8 * 1024 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, read)
                }
            }
            inflater.end()
            out.toByteArray()
        } catch (_: Exception) {
            body
        }
    }

    /**
     * Reads the text-showing operators of one decompressed content stream.
     *
     * `Tj`, `TJ`, `'` and `"` are the only operators that put glyphs on the page:
     *
     * ```
     * BT /F1 12 Tf 72 712 Td (Hello) Tj 0 -14 Td [(wor) -20 (ld)] TJ ET
     * ```
     *
     * `TJ` arrays carry kerning numbers; a value below -100 is how producers encode a word
     * gap in CID fonts, so it becomes a space. Everything outside `BT`/`ET`, and helper
     * dictionaries such as `/ToUnicode` CMaps (which are full of `beginbfchar` text
     * operators that are not page text at all), are ignored.
     */
    fun readTextOperators(content: String, charMap: Map<Int, String> = emptyMap()): String {
        val text = content.toCharArray()
        val out = StringBuilder(content.length / 4)

        // `/ToUnicode` CMaps are full of `(...)` strings that are NOT page text: the
        // beginbfchar/beginbfrange pairs are the only place they use them.
        val isCmap = content.contains("beginbfchar") || content.contains("beginbfrange")

        var index = 0
        var sawTextBox = false
        while (index < text.size) {
            val nextBt = findKeyword(text, "BT", index)
            if (nextBt < 0) break
            sawTextBox = true
            val nextEt = findKeyword(text, "ET", nextBt + 2)
            // An unbalanced `BT` (a truncated stream, or an empty page whose `BT` sits at
            // the very end) still marks where the text object starts; keep reading to the
            // end of the stream instead of dropping the page.
            val end = if (nextEt < 0) text.size else nextEt
            appendShowingStrings(text, nextBt + 2, end, charMap, out)
            index = end + 2
        }

        if (!sawTextBox || out.isBlank()) {
            // No BT/ET wrapper (or a CMap document): scan every string, keeping only those
            // that are followed by a showing operator. Resource names are not followed by
            // one, so they drop out.
            out.setLength(0)
            if (!isCmap) appendShowingStrings(text, 0, text.size, charMap, out)
        }
        return cleanPdfText(out.toString())
    }

    /** Reads every `(string) Tj|TJ|'|"` between [from] and [to]. */
    private fun appendShowingStrings(
        text: CharArray,
        from: Int,
        to: Int,
        charMap: Map<Int, String>,
        out: StringBuilder,
    ) {
        var i = from
        while (i < to) {
            val opener = indexOf(text, i, '(')
            if (opener < 0 || opener >= to) return
            val parsed = readPdfString(text, opener) ?: break
            val value = parsed.first
            val next = parsed.second
            when (nextShowingOperator(text, next, to)) {
                "Tj", "TJ" -> out.append(decodePdfString(value, charMap)).append(' ')
                "'", "\"" -> out.append('\n').append(decodePdfString(value, charMap)).append(' ')
                else -> Unit
            }
            i = next
        }
    }

    /** Returns "Tj", "TJ", "'" or "\"" when a showing operator follows the string. */
    private fun nextShowingOperator(text: CharArray, from: Int, to: Int): String? {
        var i = from
        var sawArrayEnd = false
        while (i < to) {
            val c = text[i]
            when {
                c == ']' -> { sawArrayEnd = true; i++ }
                c.isWhitespace() -> i++
                c == '[' -> i++
                c == '(' -> {
                    val nested = readPdfString(text, i) ?: return null
                    i = nested.second
                }
                c == '<' -> {
                    val close = indexOf(text, i, '>')
                    if (close < 0 || close >= to) return null
                    i = close + 1
                }
                else -> {
                    val start = i
                    while (i < to && !text[i].isWhitespace() && text[i] !in ARRAY_PUNCTUATION) i++
                    if (i == start) { i++; continue }
                    val token = String(text, start, i - start)
                    when {
                        token == "Tj" || token == "TJ" || token == "'" || token == "\"" -> return token
                        token.toDoubleOrNull() != null -> Unit // kerning inside a TJ array
                        else -> return if (sawArrayEnd) "TJ" else null
                    }
                }
            }
        }
        return if (sawArrayEnd) "TJ" else null
    }

    private val ARRAY_PUNCTUATION = charArrayOf('[', ']', '(', ')', '<', '>')

    private fun indexOf(text: CharArray, from: Int, target: Char): Int {
        var i = from.coerceAtLeast(0)
        while (i < text.size) {
            if (text[i] == target) return i
            i++
        }
        return -1
    }

    /** Finds a two-letter PDF keyword (`BT`, `ET`) at a word boundary. */
    private fun findKeyword(text: CharArray, keyword: String, from: Int): Int {
        var i = from.coerceAtLeast(0)
        while (i + keyword.length <= text.size) {
            var match = true
            for (j in keyword.indices) {
                if (text[i + j] != keyword[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                val before = if (i == 0) ' ' else text[i - 1]
                val after = if (i + keyword.length >= text.size) ' ' else text[i + keyword.length]
                // "BT" must not match inside "OBBT" or the middle of a hex string.
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return i
            }
            i++
        }
        return -1
    }

    /** Applies the `/ToUnicode` CMap when one was found; byte codes map 1:1 otherwise. */
    private fun decodePdfString(value: String, charMap: Map<Int, String>): String {
        if (charMap.isEmpty()) return value
        val out = StringBuilder(value.length)
        for (c in value) {
            out.append(charMap[c.code] ?: c)
        }
        return out.toString()
    }

    /** Reads a PDF literal string starting at [openParen]; returns the value and the next index. */
    private fun readPdfString(content: CharArray, openParen: Int): Pair<String, Int>? {
        if (openParen >= content.size || content[openParen] != '(') return null
        val out = StringBuilder()
        var depth = 1
        var i = openParen + 1
        while (i < content.size) {
            val c = content[i]
            when {
                c == '\\' -> {
                    if (i + 1 >= content.size) return out.toString() to i + 1
                    when (val escape = content[i + 1]) {
                        'n' -> { out.append('\n'); i += 2 }
                        'r' -> { out.append(' '); i += 2 }
                        't' -> { out.append(' '); i += 2 }
                        'b', 'f' -> i += 2
                        '(', ')', '\\' -> { out.append(escape); i += 2 }
                        '\n' -> i += 2
                        else -> {
                            val octal = if (i + 3 < content.size) {
                                String(content, i + 1, 3)
                            } else {
                                ""
                            }
                            val code = if (octal.length == 3 && octal.all { it in '0'..'7' }) {
                                octal.toIntOrNull(8)
                            } else {
                                null
                            }
                            if (code != null && code in 0..0xFF) {
                                out.append(code.toChar())
                                i += 4
                            } else {
                                out.append(escape)
                                i += 2
                            }
                        }
                    }
                }
                c == '(' -> { depth++; out.append(c); i++ }
                c == ')' -> {
                    depth--
                    i++
                    if (depth == 0) return out.toString() to i
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString() to i
    }

    private fun cleanPdfText(raw: String): String = raw
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000E-\\u001F]"), " ")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    /**
     * Decodes the `/ToUnicode` CMaps of a PDF so byte codes map to real characters.
     * Without this every Type0/CID-font document reads as control characters.
     */
    private fun pdfStringDecoder(pdf: String): Map<Int, String> {
        val map = HashMap<Int, String>(512)
        val cmapBlocks = Regex("""beginbfchar([\\s\\S]*?)endbfchar""").findAll(pdf)
        // The source code is 2-4 hex digits (1-2 bytes in a CID font); the destination
        // may be a multi-character Unicode string, so it is the wider group.
        val pair = Regex("""<([0-9A-Fa-f]{2,4})>\\s*<([0-9A-Fa-f]{2,10})>""")
        for (block in cmapBlocks) {
            for (match in pair.findAll(block.groupValues[1])) {
                val code = match.groupValues[1].toIntOrNull(16) ?: continue
                val unicode = hexToChars(match.groupValues[2])
                if (unicode.isNotEmpty()) map[code] = unicode
            }
        }
        val cmapRanges = Regex("""beginbfrange([\\s\\S]*?)endbfrange""").findAll(pdf)
        val range = Regex(
            """<([0-9A-Fa-f]{2,4})>\\s*<([0-9A-Fa-f]{2,4})>\\s*<([0-9A-Fa-f]{2,8})>"""
        )
        for (block in cmapRanges) {
            for (match in range.findAll(block.groupValues[1])) {
                val start = match.groupValues[1].toIntOrNull(16) ?: continue
                val end = match.groupValues[2].toIntOrNull(16) ?: continue
                if (end < start || end - start > 1_024) continue
                val base = match.groupValues[3].toIntOrNull(16) ?: continue
                for (code in start..end) {
                    val value = base + (code - start)
                    if (value in 32..0x10FFFF) map[code] = String(Character.toChars(value))
                }
            }
        }
        return map
    }

    private fun hexToChars(hex: String): String {
        if (hex.length < 4) return ""
        val builder = StringBuilder(hex.length / 4)
        var i = 0
        while (i + 4 <= hex.length) {
            val code = hex.substring(i, i + 4).toIntOrNull(16) ?: return builder.toString()
            builder.append(code.toChar())
            i += 4
        }
        return builder.toString()
    }

    /** Last-resort printable sweep, used when no content stream could be parsed. */
    private fun sweepPrintable(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size / 8)
        var run = 0
        for (byte in bytes) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in ' '..'~' || c == '\n') {
                builder.append(c)
                run++
            } else {
                if (run > 3) builder.append(' ')
                run = 0
            }
        }
        return cleanPdfText(builder.toString())
    }

    /**
     * Expands a `.zip` payload into `(relativePath, text)` pairs.
     *
     * Guard rails, all checked before anything is decompressed: an entry budget, an
     * uncompressed-size budget, and the same extension filter as the file picker. Entry
     * paths are reduced to a relative form so a `../` member (zip-slip) cannot escape the
     * archive's own namespace.
     */
    fun zip(
        bytes: ByteArray,
        maxEntries: Int = RagChunker.MAX_ARCHIVE_ENTRIES,
        maxUncompressedBytes: Long = RagChunker.MAX_ARCHIVE_UNCOMPRESSED_BYTES,
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var entries = 0
        var uncompressed = 0L
        ZipInputStream(bytes.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > maxEntries) {
                    out += "<archive-limit>" to "Archive skipped after $maxEntries entries."
                    break
                }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = entry.name.replace('\\', '/')
                val safeName = name.split('/').filter { it != ".." && it != "." && it.isNotEmpty() }
                    .joinToString("/")
                if (safeName.isEmpty() || !RagChunker.isSupported(safeName)) {
                    zip.closeEntry()
                    continue
                }
                val content = try {
                    zip.readBytes()
                } catch (_: Exception) {
                    zip.closeEntry()
                    continue
                }
                uncompressed += content.size
                if (uncompressed > maxUncompressedBytes) {
                    out += "<archive-limit>" to "Archive truncated at $maxUncompressedBytes bytes."
                    break
                }
                zip.closeEntry()
                out += safeName to extractText(safeName, content)
            }
        }
        return out
    }
}
