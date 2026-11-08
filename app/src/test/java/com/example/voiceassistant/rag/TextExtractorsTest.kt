package com.example.voiceassistant.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Extraction: plain text, HTML, PDF content streams, `/ToUnicode` CMaps and `.zip` archives.
 *
 * The PDF cases build a real, if minimal, PDF in memory — an uncompressed one and a
 * Flate-compressed one — because "does it read the text layer" is the one question that
 * decides whether indexing a PDF is useful or merely plausible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextExtractorsTest {

    @Test
    fun `plain text decodes as UTF-8 and falls back to Latin-1 for legacy files`() {
        val utf8 = "Héllo — naïve café".toByteArray(Charsets.UTF_8)
        assertEquals("Héllo — naïve café", TextExtractors.decodeText(utf8))

        val latin1 = "Grüße aus München".toByteArray(Charsets.ISO_8859_1)
        assertTrue(TextExtractors.decodeText(latin1).contains("München"))
    }

    @Test
    fun `html loses its tags and script bodies but keeps the prose`() {
        val html = """
            <html><head><style>body { color: red }</style>
            <script>var secret = "not text";</script></head>
            <body><h1>Battery plan</h1><p>Four hours of listening.</p>
            <p>Then&nbsp;charge&nbsp;it. &amp; retry.</p></body></html>
        """.trimIndent()
        val text = TextExtractors.stripMarkup(TextExtractors.decodeEntities(html))

        assertTrue(text.contains("Battery plan"))
        assertTrue(text.contains("Four hours of listening."))
        assertTrue(text.contains("& retry"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("<p>"))
        assertFalse(text.contains("color: red"))
    }

    @Test
    fun `an uncompressed PDF yields its text with one page per content stream`() {
        val pdf = buildPdf(
            pageOne = "BT /F1 12 Tf 72 712 Td (Battery budget is four hours) Tj ET",
            pageTwo = "BT /F1 12 Tf 72 712 Td [(Speaker) -30 (decision)] TJ ET",
            compress = false,
        )
        val text = TextExtractors.pdf(pdf)

        assertTrue("got: $text", text.contains("Battery budget is four hours"))
        assertTrue("got: $text", text.contains("Speaker decision"))
        assertEquals("two text pages expected: $text", 1, text.count { it == '\u000C' })
    }

    @Test
    fun `a Flate-compressed PDF is inflated before its text is read`() {
        val content = "BT /F1 12 Tf 14 TL 72 712 Td (Compressed page text) Tj T* (second line) Tj ET"
        val pdf = buildPdf(pageOne = content, pageTwo = null, compress = true)
        val text = TextExtractors.pdf(pdf)

        assertTrue("got: $text", text.contains("Compressed page text"))
        assertTrue("got: $text", text.contains("second line"))
    }

    @Test
    fun `PDF escapes and octal codes are decoded`() {
        val content = "BT (Line one \\(with parens\\)) Tj T* (Line two) Tj ET"
        val text = TextExtractors.readTextOperators(content)
        assertTrue(text.contains("Line one (with parens)"))
        assertTrue(text.contains("Line two"))
    }

    @Test
    fun `a ToUnicode CMap decodes control-byte CID text`() {
        // Two glyph codes, 0x03 and 0x04, mapped to "A" and "B" by the CMap.
        val cmap = """
            /CIDInit /ProcSet findresource begin
            1 beginbfchar
            <0003> <0041>
            endbfchar
            1 beginbfrange
            <0004> <0004> <0042>
            endbfrange
            end
        """.trimIndent().toByteArray(Charsets.ISO_8859_1)

        val pdf = buildPdf(
            pageOne = "BT (\u0003\u0004) Tj ET",
            pageTwo = null,
            compress = false,
            extraObject = cmap,
        )
        val text = TextExtractors.pdf(pdf)
        assertTrue("the CMap should have produced AB, got: $text", text.contains("AB"))
    }

    @Test
    fun `a PDF with no text layer yields nothing rather than binary noise`() {
        val pdf = buildPdf(
            pageOne = "\u0001\u0002\u0003\u0004binary nonsense \u0005\u0006",
            pageTwo = null,
            compress = false,
        )
        val text = TextExtractors.pdf(pdf)
        assertFalse(text.contains("endstream"))
    }

    @Test
    fun `zip expansion keeps supported members and refuses traversal paths`() {
        val bytes = buildZip(
            "notes.md" to "# Notes\nBattery budget is four hours.",
            "code/sample.kt" to "fun main() { println(\"hi\") }",
            "image.png" to "not really a png",
            "../escape.md" to "must be normalised, not trusted",
            "__MACOSX/._notes.md" to "resource fork",
        )

        val members = TextExtractors.zip(bytes)
        val names = members.map { it.first }

        assertTrue(names.contains("notes.md"))
        assertTrue(names.contains("code/sample.kt"))
        assertTrue(names.none { it.startsWith("..") })
        assertTrue(names.none { it.contains("__MACOSX") })
        assertTrue(names.none { it.endsWith(".png") })
        assertEquals(
            "# Notes\nBattery budget is four hours.",
            members.first { it.first == "notes.md" }.second.trim(),
        )
    }

    @Test
    fun `zip expansion stops at its entry budget`() {
        val build = ByteArrayOutputStream()
        ZipOutputStream(build).use { zip ->
            repeat(20) { index ->
                zip.putNextEntry(ZipEntry("doc$index.md"))
                zip.write("text $index".toByteArray())
                zip.closeEntry()
            }
        }
        val members = TextExtractors.zip(build.toByteArray(), maxEntries = 5)
        assertTrue(members.size <= 6) // five documents plus the limit marker
        assertTrue(members.any { it.first == "<archive-limit>" })
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Builds a minimal but valid PDF.
     *
     * It has no cross-reference table on purpose: the extractor scans for `stream`/`endstream`
     * pairs and reads the text operators, which is what real-world PDFs need anyway.
     */
    private fun buildPdf(
        pageOne: String,
        pageTwo: String?,
        compress: Boolean,
        extraObject: ByteArray? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        if (extraObject != null) {
            out.write("3 0 obj\n".toByteArray())
            out.write(extraObject)
            out.write("\nendobj\n".toByteArray())
        }
        val pages = listOfNotNull(pageOne, pageTwo)
        pages.forEachIndexed { index, content ->
            val body = if (compress) deflate(content.toByteArray(Charsets.ISO_8859_1)) else content.toByteArray(Charsets.ISO_8859_1)
            val filter = if (compress) "/Filter /FlateDecode " else ""
            out.write("4 $index obj\n<< $filter/Length ${body.size} >>\nstream\n".toByteArray())
            out.write(body)
            out.write("\nendstream\nendobj\n".toByteArray())
        }
        out.write("%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (!deflater.finished()) {
            val read = deflater.deflate(buffer)
            out.write(buffer, 0, read)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
