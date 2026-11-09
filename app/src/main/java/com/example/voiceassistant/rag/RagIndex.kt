package com.example.voiceassistant.rag

import java.util.concurrent.atomic.AtomicInteger

/**
 * The in-memory retrieval index.
 *
 * Design notes, because the trade-offs are deliberate:
 *
 *  * **Lexical, not vector.** A local RAG store on a phone with no network cannot download
 *    an embedding model, and shipping one would add tens of megabytes to an APK that has to
 *    stay installable. Token overlap with a shared-stem rule and a path bonus is what a
 *    900-character-chunk index needs; it is also explainable, which matters when the
 *    assistant is quoting the user's own notes back at them.
 *  * **Bounded.** The index holds at most [maxChunks] excerpts and stops accepting new ones
 *    rather than growing until the app is killed. Re-importing the same file replaces its
 *    excerpts instead of duplicating them.
 *  * **Thread-safe.** Ingestion runs on an IO dispatcher while a query can be answered on
 *    another thread; every public method synchronises on the same monitor.
 */
class RagIndex(maxChunks: Int = DEFAULT_MAX_CHUNKS) {

    /** One entry per source file, plus its chunks. */
    private data class SourceEntry(
        val sourcePath: String,
        val chunks: List<RagChunk>,
    )

    private val lock = Any()

    /** Insertion-ordered so the file list in the UI is stable. */
    private val sources = LinkedHashMap<String, SourceEntry>()

    private val liveChunkCount = AtomicInteger(0)

    /** Total excerpts the index will hold. */
    /**
     * The hard ceiling on stored excerpts.
     *
     * The floor is 1, not 16: a caller asking for a three-chunk index (a test, or a device
     * with almost no memory) must get three. A silent floor *above* the request is a bound
     * that does not bind, which is worse than no bound at all because it looks like one.
     */
    val capacity: Int = maxChunks.coerceAtLeast(1)

    val size: Int get() = liveChunkCount.get()

    val isNotEmpty: Boolean get() = size > 0

    /**
     * Adds (or replaces) one document.
     *
     * @return how many excerpts are now stored for this file, which is 0 when the document
     *   had no readable text — in that case any previous version of the file is removed, so
     *   a re-import of an emptied file does not leave stale excerpts behind.
     */
    fun put(sourcePath: String, chunks: List<RagChunk>): Int = synchronized(lock) {
        val key = RagChunker.relativePathOf(sourcePath)
        val previous = sources.remove(key)
        if (previous != null) liveChunkCount.addAndGet(-previous.chunks.size)

        if (chunks.isEmpty()) return@synchronized 0

        val room = capacity - liveChunkCount.get()
        if (room <= 0) return@synchronized 0
        val accepted = if (chunks.size <= room) chunks else chunks.subList(0, room)

        sources[key] = SourceEntry(key, accepted.toList())
        liveChunkCount.addAndGet(accepted.size)
        accepted.size
    }

    fun remove(sourcePath: String): Boolean = synchronized(lock) {
        val key = RagChunker.relativePathOf(sourcePath)
        val removed = sources.remove(key) ?: return@synchronized false
        liveChunkCount.addAndGet(-removed.chunks.size)
        true
    }

    fun clear() {
        synchronized(lock) {
            sources.clear()
            liveChunkCount.set(0)
        }
    }

    /** True when this exact source path is already indexed. */
    fun contains(sourcePath: String): Boolean = synchronized(lock) {
        sources.containsKey(RagChunker.relativePathOf(sourcePath))
    }

    fun sourcePaths(): List<String> = synchronized(lock) { sources.keys.toList() }

    fun summary(): RagIndexSummary = synchronized(lock) {
        if (sources.isEmpty()) return@synchronized RagIndexSummary.EMPTY
        val list = sources.values.map { entry ->
            RagSourceSummary(
                sourcePath = entry.sourcePath,
                pages = entry.chunks.maxOfOrNull { it.pageNumber } ?: 0,
                chunks = entry.chunks.size,
                characters = entry.chunks.sumOf { it.length },
            )
        }
        RagIndexSummary(chunkCount = liveChunkCount.get(), sources = list)
    }

    /** A copy of every excerpt; used by [search] and by the tests. */
    fun snapshot(): List<RagChunk> = synchronized(lock) {
        val out = ArrayList<RagChunk>(liveChunkCount.get())
        sources.values.forEach { out += it.chunks }
        out
    }

    /**
     * Ranks the excerpts against [query] and returns the best [maxChunks] that clear the
     * relevance floor.
     *
     * Scoring, per excerpt:
     *
     *  * `+1` for every query term it contains exactly, `+0.5`-worth for an inflection the
     *    shared-stem rule accepts (counted as a hit but in a second pass so exact matches
     *    always win ties);
     *  * `+3` when the query names the file it came from (this is what makes
     *    "the deployment doc" reliable even when the body never says "deployment");
     *  * `+5` when the query names its page and this is that page.
     *
     * A filter that matches nothing falls back to the whole index instead of answering
     * "I know nothing", which is the friendlier failure for a small personal library.
     */
    fun search(
        query: RagQuery,
        maxChunks: Int = DEFAULT_MAX_CHUNKS_PER_ANSWER,
        minScore: Int = DEFAULT_MIN_SCORE,
    ): List<ScoredChunk> {
        val all = snapshot()
        if (all.isEmpty()) return emptyList()

        val candidates = filter(all, query).ifEmpty { all }

        val queryTokens = RagChunker.tokenize(query.question)
        val fileHint = query.fileFilter?.lowercase()?.let { RagChunker.leafName(it) }
        val pageWanted = query.pageNumber

        val scored = ArrayList<ScoredChunk>(candidates.size)
        for (chunk in candidates) {
            val pathLower = chunk.sourcePath.lowercase()

            var exact = 0
            var stemmed = 0
            for (token in queryTokens) {
                when {
                    token in chunk.tokens -> exact++
                    chunk.tokens.any { RagChunker.tokensMatch(token, it) } -> stemmed++
                }
            }

            val pathBonus = if (
                fileHint != null && fileHint.isNotEmpty() && pathLower.contains(fileHint)
            ) {
                PATH_BONUS
            } else {
                // A query term that appears in the path is still evidence.
                if (queryTokens.any { pathLower.contains(it) }) PATH_TOKEN_BONUS else 0
            }

            val pageBonus = if (pageWanted != null && chunk.pageNumber == pageWanted) PAGE_BONUS else 0

            val score = exact * EXACT_WEIGHT +
                stemmed * STEMMED_WEIGHT +
                pathBonus +
                pageBonus

            if (score >= minScore || (pageBonus > 0 && pageWanted != null)) {
                scored += ScoredChunk(chunk = chunk, score = score, matchedTokens = exact + stemmed)
            }
        }

        return scored
            .sortedWith(
                compareByDescending<ScoredChunk> { it.score }
                    .thenByDescending { it.matchedTokens }
                    .thenBy { it.chunk.sourcePath }
                    .thenBy { it.chunk.pageNumber }
                    .thenBy { it.chunk.partIndex }
            )
            .take(maxChunks)
    }

    /** Retrieval restricted to the target file/page, when the query named one. */
    private fun filter(chunks: List<RagChunk>, query: RagQuery): List<RagChunk> {
        val fileHint = query.fileFilter?.lowercase()?.trim()
        val page = query.pageNumber
        if (fileHint.isNullOrEmpty() && page == null) return chunks

        return chunks.filter { chunk ->
            val pathLower = chunk.sourcePath.lowercase()
            val matchesFile = fileHint.isNullOrEmpty() ||
                pathLower.contains(fileHint) ||
                pathLower.contains(RagChunker.leafName(fileHint))
            val matchesPage = page == null || chunk.pageNumber == page
            matchesFile && matchesPage
        }
    }

    /**
     * Formats the ranked excerpts for injection into a prompt.
     *
     * The total is capped at [maxCharacters] so the llama.cpp context (2 048 tokens in this
     * app) is not consumed by the excerpts alone. Whole excerpts are kept, never sliced
     * mid-sentence, because a truncated quote is worse than one fewer quote.
     */
    fun buildContext(
        query: RagQuery,
        maxChunks: Int = DEFAULT_MAX_CHUNKS_PER_ANSWER,
        maxCharacters: Int = DEFAULT_MAX_CONTEXT_CHARS,
        perChunkCharacters: Int = DEFAULT_PER_CHUNK_CHARS,
    ): RagContext {
        val hits = search(query, maxChunks = maxChunks)
        if (hits.isEmpty()) return RagContext(emptyList(), "")

        val kept = ArrayList<ScoredChunk>(hits.size)
        var used = 0
        for (hit in hits) {
            val excerpt = clip(hit.chunk.content, perChunkCharacters)
            val cost = excerpt.length + hit.label.length + 8
            if (kept.isNotEmpty() && used + cost > maxCharacters) break
            kept += hit
            used += cost
        }
        if (kept.isEmpty()) return RagContext(emptyList(), "")

        val text = kept.joinToString(separator = "\n\n") { hit ->
            "[${hit.label}]:\n${clip(hit.chunk.content, perChunkCharacters)}"
        }
        return RagContext(chunks = kept, text = text)
    }

    private fun clip(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val cut = text.substring(0, limit)
        val boundary = cut.lastIndexOfAny(charArrayOf('\n', '.', ' '))
        val trimmed = if (boundary > limit / 2) cut.substring(0, boundary) else cut
        return trimmed.trimEnd() + "…"
    }

    companion object {
        /** 400 excerpts is ~360 000 characters: a few hundred pages of notes. */
        const val DEFAULT_MAX_CHUNKS = 400

        const val DEFAULT_MAX_CHUNKS_PER_ANSWER = 3

        /** Below this a hit is noise; one exact rare word is worth 2. */
        const val DEFAULT_MIN_SCORE = 2

        const val DEFAULT_MAX_CONTEXT_CHARS = 2_400
        const val DEFAULT_PER_CHUNK_CHARS = 700

        private const val EXACT_WEIGHT = 2
        private const val STEMMED_WEIGHT = 1
        private const val PATH_BONUS = 3
        private const val PATH_TOKEN_BONUS = 1
        private const val PAGE_BONUS = 5
    }
}
