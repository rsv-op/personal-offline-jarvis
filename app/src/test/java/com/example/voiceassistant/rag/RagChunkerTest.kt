package com.example.voiceassistant.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chunking and tokenisation.
 *
 * These are the rules that decide what the model is allowed to see, so they are pinned
 * precisely: page boundaries, part numbering, the stop-word list, and the shared-stem rule
 * that makes "deploy" find "deployment".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RagChunkerTest {

    @Test
    fun `a short document is a single page`() {
        val chunks = RagChunker.chunk("notes.md", "Buy oat milk and call the dentist.")
        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].pageNumber)
        assertEquals(1, chunks[0].partIndex)
        assertEquals("notes.md", chunks[0].sourcePath)
    }

    @Test
    fun `long text is split into numbered pages without losing characters`() {
        val paragraph = "The battery budget is four hours of continuous listening. "
        val text = paragraph.repeat(80) // ~4 400 characters
        val chunks = RagChunker.chunk("plan.md", text, charsPerPage = 900)

        assertTrue("expected several pages, got ${chunks.size}", chunks.size >= 4)
        assertEquals((1..chunks.size).toList(), chunks.map { it.pageNumber })
        // Every page carries real text and none is empty.
        chunks.forEach { chunk ->
            assertTrue(chunk.content.isNotBlank())
            assertTrue(chunk.content.length <= 900)
        }
        // The split must not drop words: the concatenation still contains the phrase.
        assertTrue(chunks.joinToString(" ") { it.content }.contains("battery budget"))
    }

    @Test
    fun `a form feed starts a new page so PDF page numbers survive`() {
        val page1 = "Page one text about the microphone."
        val page2 = "Page two text about the speaker."
        val page3 = "Page three text about the model."
        val chunks = RagChunker.chunk("manual.pdf", "$page1\u000C$page2\u000C$page3")

        assertEquals(3, chunks.size)
        assertEquals(listOf(1, 2, 3), chunks.map { it.pageNumber })
        assertTrue(chunks[1].content.contains("speaker"))
        assertFalse(chunks[0].content.contains("Page two"))
    }

    @Test
    fun `a page longer than the limit becomes numbered parts of the same page`() {
        // One form-feed page holding 3 000 characters: three parts, all on page 1.
        val text = "sensor ".repeat(500)
        val chunks = RagChunker.chunk("log.txt", text, charsPerPage = 900)

        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.pageNumber == 1 })
        assertEquals((1..chunks.size).toList(), chunks.map { it.partIndex })
        assertEquals("log.txt p.1", chunks[0].label)
        assertEquals("log.txt p.1.2", chunks[1].label)
    }

    @Test
    fun `blank and whitespace-only documents produce no chunks`() {
        assertEquals(0, RagChunker.chunk("empty.txt", "   \n\n \t ").size)
        assertEquals(0, RagChunker.chunk("nothing.md", "").size)
    }

    @Test
    fun `the extension filter accepts documents and refuses everything else`() {
        assertTrue(RagChunker.isSupported("notes.md"))
        assertTrue(RagChunker.isSupported("budget.csv"))
        assertTrue(RagChunker.isSupported("Memory.kt"))
        assertTrue(RagChunker.isSupported("manual.pdf"))
        assertTrue(RagChunker.isSupported("notes.zip"))

        assertFalse(RagChunker.isSupported("model.gguf"))
        assertFalse(RagChunker.isSupported("holiday.mp4"))
        assertFalse(RagChunker.isSupported(".hidden.md"))
        assertFalse(RagChunker.isSupported("archive.zip/__MACOSX/notes.md"))
        // A provider that reports no extension falls back to the MIME type.
        assertTrue(RagChunker.isSupported("README", "text/plain"))
        assertFalse(RagChunker.isSupported("blob", "application/octet-stream"))
    }

    @Test
    fun `tokenisation drops stop words and keeps numbers`() {
        val tokens = RagChunker.tokenize("What is the context size of the model, 2048?")
        assertFalse("stop words must not rank: $tokens", tokens.contains("the"))
        assertTrue(tokens.contains("context"))
        assertTrue(tokens.contains("2048"))
        assertTrue(tokens.contains("model"))
    }

    @Test
    fun `shared stems match but short words do not`() {
        // "deploy" is the stem of "deployment": five characters, so it counts.
        assertTrue(RagChunker.tokensMatch("deploy", "deployment"))
        assertTrue(RagChunker.tokensMatch("notes", "notes"))
        // Too short to be a stem: an article must not match every word beginning with it.
        assertFalse(RagChunker.tokensMatch("art", "article"))
        assertFalse(RagChunker.tokensMatch("cat", "catalog"))
        assertFalse(RagChunker.tokensMatch("here", "heretic"))
    }

    @Test
    fun `a document that is one unbroken run is still bounded`() {
        val chunks = RagChunker.chunk("dense.txt", "x".repeat(2_500), charsPerPage = 900)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.content.length <= 900 })
    }

    @Test
    fun `chunk labels are stable and unique`() {
        val chunks = RagChunker.chunk(
            "docs/notes.md",
            "alpha ".repeat(400) + "\u000C" + "beta ".repeat(400),
            charsPerPage = 200,
        )
        val labels = chunks.map { it.label }
        assertNotNull(labels)
        assertEquals("labels must not repeat: $labels", labels.size, labels.toSet().size)
        assertTrue(chunks.all { it.sourcePath == "docs/notes.md" })
    }
}
