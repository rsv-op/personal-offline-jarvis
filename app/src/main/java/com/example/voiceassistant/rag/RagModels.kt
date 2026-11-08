package com.example.voiceassistant.rag

/**
 * One indexed excerpt of a document.
 *
 * A document is split into *pages* of roughly [RagChunker.DEFAULT_CHARS_PER_PAGE]
 * characters, and a page that is still longer than that (a PDF page holding a dense
 * table, a single-paragraph `README`) is split into numbered parts. The pair
 * ([sourcePath], [pageNumber], [partIndex]) is the chunk's identity, which is what makes
 * "notes.md page 2" an exact-match target rather than a fuzzy search.
 *
 * [tokens] is the lower-cased, stop-word-filtered term set used for ranking. It is
 * deliberately derived from `"<path> <page> <text>"` so a query that names the file
 * ("the deployment notes") matches the chunk even when the file name never appears in
 * its body.
 */
data class RagChunk(
    val sourcePath: String,
    val pageNumber: Int,
    val partIndex: Int,
    val content: String,
    val tokens: Set<String>,
) {
    /** Human-readable address of this excerpt, shown in prompts and in the UI. */
    val label: String
        get() = if (partIndex <= 1) {
            "$sourcePath p.$pageNumber"
        } else {
            "$sourcePath p.$pageNumber.$partIndex"
        }

    val length: Int get() = content.length
}

/** Per-file roll-up used by the UI ("42 excerpts across 3 files"). */
data class RagSourceSummary(
    val sourcePath: String,
    val pages: Int,
    val chunks: Int,
    val characters: Int,
)

/** Everything the UI needs to describe the index without touching it. */
data class RagIndexSummary(
    val chunkCount: Int,
    val sources: List<RagSourceSummary>,
) {
    val isEmpty: Boolean get() = chunkCount == 0

    fun describe(): String = if (isEmpty) {
        "No documents indexed"
    } else {
        "$chunkCount excerpt(s) from ${sources.size} file(s)"
    }

    companion object {
        val EMPTY = RagIndexSummary(chunkCount = 0, sources = emptyList())
    }
}

/** A ranked excerpt together with the score that put it there. */
data class ScoredChunk(
    val chunk: RagChunk,
    val score: Int,
    val matchedTokens: Int,
) {
    val label: String get() = chunk.label
}

/**
 * The context that gets injected into the model prompt.
 *
 * [text] is the pre-formatted `[source p.N]: …` block; keeping it pre-formatted means the
 * prompt composer cannot accidentally drift from what the UI showed the user.
 */
data class RagContext(
    val chunks: List<ScoredChunk>,
    val text: String,
) {
    val isEmpty: Boolean get() = chunks.isEmpty()
    val sourceLabels: List<String> get() = chunks.map { it.label }
    val totalCharacters: Int get() = chunks.sumOf { it.chunk.length }
}

/** Result of one ingestion run, surfaced to the user and written to the ingest audit log. */
data class RagIngestReport(
    val filesIndexed: Int,
    val chunksAdded: Int,
    val skipped: List<String>,
    val totalChunks: Int,
    val sources: List<String>,
) {
    fun describe(): String {
        if (filesIndexed == 0) {
            val reason = skipped.firstOrNull() ?: "no readable text was found in that selection"
            return "Nothing indexed — $reason"
        }
        val base = "Indexed $chunksAdded excerpt(s) from $filesIndexed file(s) " +
            "($totalChunks excerpt(s) in total)"
        return if (skipped.isEmpty()) base else "$base; skipped ${skipped.size}: ${skipped.first()}"
    }

    companion object {
        val EMPTY = RagIngestReport(
            filesIndexed = 0,
            chunksAdded = 0,
            skipped = emptyList(),
            totalChunks = 0,
            sources = emptyList(),
        )
    }
}
