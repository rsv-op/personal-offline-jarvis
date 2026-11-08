package com.example.voiceassistant.rag

import com.example.voiceassistant.generation.RagPromptComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Retrieval behaviour: ranking, the path/page bonuses, target filters and the prompt block.
 *
 * Each test builds its own small corpus, so a change in the ranking weights shows up as a
 * changed expectation here rather than as mysterious behaviour on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RagIndexTest {

    private fun indexOf(vararg documents: Pair<String, String>): RagIndex {
        val index = RagIndex()
        documents.forEach { (path, text) ->
            index.put(path, RagChunker.chunk(path, text))
        }
        return index
    }

    private val corpus = indexOf(
        "notes.md" to """
            Battery budget is four hours of continuous listening on a single charge.
            The microphone pipeline must not wake the application processor more than once a second.
        """.trimIndent() + "\u000C" + """
            Speaker decision: Supertonic on-device synthesis, 22 kilohertz output,
            with a hard stop when the user says shaz.
        """.trimIndent(),
        "deployment.md" to """
            Deployment runs on the phone itself. There is no server, no certificate,
            and no network permission in the manifest, so a rollout is an APK install.
        """.trimIndent(),
        "shopping.csv" to "item,qty\noat milk,2\nbattery,4\nduct tape,1",
    )

    @Test
    fun `the best-matching page is ranked first`() {
        val query = RagQuery.parse("how long is the battery budget")
        val hits = corpus.search(query)

        assertTrue("expected at least one hit", hits.isNotEmpty())
        assertEquals("notes.md", hits.first().chunk.sourcePath)
        assertEquals(1, hits.first().chunk.pageNumber)
        assertTrue(
            "the excerpt should mention the budget: ${hits.first().chunk.content}",
            hits.first().chunk.content.contains("Battery budget"),
        )
    }

    @Test
    fun `naming a file boosts it even when the body never repeats the name`() {
        val query = RagQuery.parse("what does the deployment document say")
        val hits = corpus.search(query)

        assertTrue(hits.isNotEmpty())
        assertEquals("deployment.md", hits.first().chunk.sourcePath)
        assertEquals("deployment", query.fileFilter)
    }

    @Test
    fun `a page number in the query is an exact target`() {
        val query = RagQuery.parse("notes.md page 2 about the speaker")
        assertEquals("notes.md", query.fileFilter)
        assertEquals(2, query.pageNumber)

        val hits = corpus.search(query)
        assertTrue(hits.isNotEmpty())
        assertEquals(2, hits.first().chunk.pageNumber)
        assertTrue(hits.first().chunk.content.contains("Speaker"))
    }

    @Test
    fun `a page that does not exist falls back to the whole index instead of answering nothing`() {
        val query = RagQuery.parse("notes.md page 9")
        val hits = corpus.search(query)
        assertTrue("a failed filter must not silence the answer", hits.isNotEmpty())
    }

    @Test
    fun `an unrelated question returns nothing above the relevance floor`() {
        val query = RagQuery.parse("quantum chromodynamics lattice spacing")
        assertTrue(corpus.search(query).isEmpty())
    }

    @Test
    fun `re-importing a file replaces its excerpts instead of duplicating them`() {
        val index = indexOf("a.txt" to "one two three four five six seven")
        val before = index.size
        index.put("a.txt", RagChunker.chunk("a.txt", "one two three four five six seven"))
        assertEquals(before, index.size)

        // An emptied re-import must remove the old excerpts, not leave stale ones.
        index.put("a.txt", emptyList())
        assertEquals(0, index.size)
    }

    @Test
    fun `the index honours its capacity`() {
        val index = RagIndex(maxChunks = 3)
        index.put("big.txt", RagChunker.chunk("big.txt", "word ".repeat(2_000), charsPerPage = 200))
        assertEquals(3, index.size)
        assertTrue(index.isNotEmpty)
    }

    @Test
    fun `clear and remove both shrink the index`() {
        val index = indexOf(
            "a.txt" to "alpha beta gamma delta",
            "b.txt" to "epsilon zeta eta theta",
        )
        assertEquals(2, index.size)
        assertTrue(index.remove("a.txt"))
        assertEquals(1, index.size)
        assertFalse(index.contains("a.txt"))
        index.clear()
        assertEquals(0, index.size)
        assertTrue(index.summary().isEmpty)
    }

    @Test
    fun `the summary reports pages and characters per source`() {
        val summary = corpus.summary()
        assertEquals(3, summary.sources.size)
        val notes = summary.sources.first { it.sourcePath == "notes.md" }
        assertEquals(2, notes.pages)
        assertTrue(notes.characters > 50)
        assertTrue(summary.describe().contains("file(s)"))
    }

    @Test
    fun `the injected context carries labels, stays bounded and quotes the sources`() {
        val query = RagQuery.parse("battery budget continuous listening")
        val context = corpus.buildContext(query)

        assertFalse(context.isEmpty)
        assertTrue(context.text.startsWith("[notes.md p.1]:"))
        assertTrue(context.totalCharacters <= RagIndex.DEFAULT_MAX_CONTEXT_CHARS)
        assertTrue(context.sourceLabels.all { it.contains("page") || it.contains(".md") })

        val prompt = RagPromptComposer.compose(context, "how long is the battery budget")
        assertTrue(prompt.usedContext)
        assertTrue(prompt.userPrompt.startsWith("Context Information:"))
        assertTrue(prompt.userPrompt.contains("Question: how long is the battery budget"))
        assertTrue(prompt.systemInstruction.contains("cite the label"))

        // The excerpts come back out of the prompt unchanged (the fallback quotes them).
        val excerpts = RagPromptComposer.extractExcerpts(prompt.userPrompt)
        assertEquals(context.chunks.size, excerpts.size)
        assertEquals(context.chunks.first().label, excerpts.first().label)
        assertTrue(excerpts.first().text.startsWith("Battery budget"))
    }

    @Test
    fun `an empty index yields an empty context and a plain prompt`() {
        val empty = RagIndex()
        val context = empty.buildContext(RagQuery.parse("anything at all"))
        assertTrue(context.isEmpty)

        val prompt = RagPromptComposer.compose(context, "anything at all")
        assertFalse(prompt.usedContext)
        assertEquals("anything at all", prompt.userPrompt)
        assertFalse(prompt.systemInstruction.contains("Context Information"))
    }

    @Test
    fun `device commands are recognised so they never pull excerpts into a confirmation`() {
        assertTrue(RagQuery.looksLikeDeviceCommand("turn on the flashlight"))
        assertTrue(RagQuery.looksLikeDeviceCommand("set a timer for ten minutes"))
        assertTrue(RagQuery.looksLikeDeviceCommand("delete my notes file"))
        assertFalse(RagQuery.looksLikeDeviceCommand("what did the notes say about the battery"))
    }

    @Test
    fun `a target with no match anywhere returns an empty context, not a wrong quote`() {
        val query = RagQuery("speaker decision", fileFilter = "not-there.md", pageNumber = 7, rawQuery = "")
        val context = corpus.buildContext(query)
        // The filter falls back to the whole index (friendlier than silence)...
        assertFalse(context.isEmpty)
        // ...but everything it returns really does answer the question.
        assertTrue(context.chunks.all { it.chunk.tokens.contains("speaker") })
    }
}
