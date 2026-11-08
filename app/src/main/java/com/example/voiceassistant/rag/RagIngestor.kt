package com.example.voiceassistant.rag

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

/**
 * Reads documents out of the Storage Access Framework and puts them in the [RagIndex].
 *
 * Three entry points, matching the three pickers in `MainActivity`:
 *
 *  * [ingestDocument] — one file from `ACTION_OPEN_DOCUMENT` (`.txt`, `.md`, `.json`,
 *    `.csv`, `.kt`, `.pdf`, `.zip`, …);
 *  * [ingestTree] — a whole directory from `ACTION_OPEN_DOCUMENT_TREE`, walked
 *    breadth-first so a deep tree cannot recurse the stack away, with a document budget;
 *  * [ingestVideo…] — no: video, audio and unknown binaries are refused by
 *    [RagChunker.isSupported] before a single byte is read.
 *
 * Nothing here writes to shared storage. The only bytes that leave the SAF stream are the
 * text that goes into the in-memory index; the URI is never copied into app storage, which
 * is why a re-import can never leave a stale copy behind.
 *
 * Every run is bounded:
 *
 *  * [MAX_DOCUMENT_BYTES] per file (a 300 MB log file is not a knowledge base),
 *  * [RagChunker.MAX_ARCHIVE_ENTRIES] / [RagChunker.MAX_ARCHIVE_UNCOMPRESSED_BYTES] per
 *    archive (zip bombs),
 *  * [MAX_FILES_PER_TREE] per folder import,
 *  * [RagIndex.capacity] overall, enforced by the index itself.
 *
 * ## Durability
 *
 * When a [RagStore] is supplied (which Hilt always does — see `AssistantDiModule`), every
 * mutation is mirrored to a single JSON snapshot in `filesDir/knowledge/index.json` and
 * [restore] reloads it on the next cold start. Without the store the class behaves exactly
 * as it always has: a pure in-memory index, which is what the unit tests use.
 */
class RagIngestor(
    @ApplicationContext private val context: Context,
    private val index: RagIndex,
    private val store: RagStore? = null,
) {

    /** Indexes one document (a `.zip` is expanded and each supported member is indexed). */
    suspend fun ingestDocument(
        uri: Uri,
        displayName: String? = null,
        prefix: String = "",
        onProgress: (String) -> Unit = {},
    ): RagIngestReport = withContext(Dispatchers.IO) {
        val name = displayName?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri) ?: "document"
        val path = if (prefix.isBlank()) name else "$prefix/$name"
        val skipped = mutableListOf<String>()

        if (!RagChunker.isSupported(name, queryMimeType(uri))) {
            return@withContext RagIngestReport(
                filesIndexed = 0,
                chunksAdded = 0,
                skipped = listOf("$name is not a text document the index can read"),
                totalChunks = index.size,
                sources = index.sourcePaths(),
            )
        }

        onProgress("Reading $name…")
        val bytes = readBytes(uri) ?: return@withContext RagIngestReport(
            filesIndexed = 0,
            chunksAdded = 0,
            skipped = listOf("$name could not be opened for reading"),
            totalChunks = index.size,
            sources = index.sourcePaths(),
        )

        val added = indexAll(path, name, bytes, skipped, onProgress)
        persistNow()
        RagIngestReport(
            filesIndexed = if (added > 0) 1 else 0,
            chunksAdded = added,
            skipped = skipped,
            totalChunks = index.size,
            sources = index.sourcePaths(),
        )
    }

    /**
     * Indexes every supported document under a folder tree, breadth-first.
     *
     * The tree is walked with `DocumentsContract` rather than `DocumentFile`, because the
     * latter opens a new cursor per child and turns a 2 000-file folder into 2 000 IPC
     * round trips.
     */
    suspend fun ingestTree(
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
    ): RagIngestReport = withContext(Dispatchers.IO) {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext RagIngestReport.EMPTY.copy(
                skipped = listOf("that folder could not be opened"),
            )
        val rootName = runCatching { DocumentsContract.getDocumentId(treeUri) }
            .getOrNull()
            ?.substringAfterLast(':')
            ?.ifBlank { null }
            ?: "folder"

        val skipped = mutableListOf<String>()
        var filesIndexed = 0
        var chunksAdded = 0
        val queue = ArrayDeque<Pair<String, String>>()
        queue.addLast(rootDocId to "")

        while (queue.isNotEmpty() && filesIndexed < MAX_FILES_PER_TREE) {
            val (documentId, prefix) = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val children = runCatching {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val childId = cursor.getString(0) ?: continue
                            val childName = cursor.getString(1) ?: childId
                            val mime = cursor.getString(2).orEmpty()
                            val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                            add(Triple(childId, childName, mime) to size)
                        }
                    }
                } ?: emptyList()
            }.getOrElse { error ->
                skipped += "could not list ${prefix.ifBlank { rootName }}: ${error.message}"
                emptyList()
            }

            for ((child, size) in children) {
                if (filesIndexed >= MAX_FILES_PER_TREE) break
                val (childId, childName, mime) = child
                val relative = if (prefix.isBlank()) childName else "$prefix/$childName"

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    queue.addLast(childId to relative)
                    continue
                }
                if (!RagChunker.isSupported(childName, mime)) continue
                if (size > RagChunker.MAX_DOCUMENT_BYTES) {
                    skipped += "$relative is larger than ${RagChunker.MAX_DOCUMENT_BYTES / 1024 / 1024} MB"
                    continue
                }

                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                onProgress("Indexing ${relative.take(60)}…")
                val bytes = readBytes(fileUri)
                if (bytes == null) {
                    skipped += "$relative could not be read"
                    continue
                }
                val before = index.size
                indexAll(relative, childName, bytes, skipped, onProgress)
                if (index.size > before) filesIndexed++
                chunksAdded += index.size - before
            }
        }

        if (filesIndexed >= MAX_FILES_PER_TREE) {
            skipped += "stopped after $MAX_FILES_PER_TREE files in one folder"
        }
        if (filesIndexed > 0) persistNow()

        RagIngestReport(
            filesIndexed = filesIndexed,
            chunksAdded = chunksAdded,
            skipped = skipped,
            totalChunks = index.size,
            sources = index.sourcePaths(),
        )
    }

    /**
     * Removes one source from the index and schedules the snapshot rewrite.
     *
     * Persistence is fire-and-forget on [scope] (`viewModelScope` in the app): the
     * in-memory removal has already happened, and a failed save only means the snapshot
     * still lists a source a later restore would bring back — which [forget] can remove
     * again. Blocking the caller on storage I/O would be the worse trade.
     */
    fun forget(sourcePath: String, scope: kotlinx.coroutines.CoroutineScope? = null): Boolean {
        val removed = index.remove(sourcePath)
        if (removed) persistAsync(scope)
        return removed
    }

    fun clear(scope: kotlinx.coroutines.CoroutineScope? = null) {
        index.clear()
        val sink = store ?: return
        if (scope == null) {
            // No scope supplied (a synchronous caller such as a test): the snapshot is one
            // small JSON document, so deleting it inline is cheap and deterministic.
            clearSnapshotBlocking(sink)
        } else {
            scope.launch { sink.clear() }
        }
    }

    /**
     * Reloads the saved snapshot into the index and returns the resulting summary.
     *
     * Called once from `AssistantViewModel.init`. Restoring is a no-op when the index
     * already holds excerpts, so a configuration change (which keeps the view model) does
     * not double-load. A corrupt snapshot yields an empty index rather than an exception:
     * the knowledge base is a convenience, not something that may block startup.
     */
    suspend fun restore(): RagIndexSummary {
        if (index.size == 0) {
            val saved = store?.load().orEmpty()
            if (saved.isNotEmpty()) {
                saved.groupBy { it.sourcePath }.forEach { (path, chunks) ->
                    index.put(path, chunks)
                }
            }
        }
        return index.summary()
    }

    /** Absolute path of the snapshot file, or a human-readable note when persistence is off. */
    fun persistencePath(): String = store?.path() ?: "in-memory only"

    /** Size of the snapshot on disk in bytes (0 when nothing has been saved yet). */
    fun persistenceSizeBytes(): Long = store?.sizeBytes() ?: 0L

    /**
     * Writes the current index to the snapshot from a caller that is already on the IO
     * dispatcher (both ingestion entry points are). One small synchronous write per
     * import beats one coroutine per import, and it means the report the UI sees was
     * produced from a state that is already durable.
     */
    private fun persistNow() {
        val sink = store ?: return
        if (index.size == 0) {
            clearSnapshotBlocking(sink)
            return
        }
        val snapshot = index.snapshot()
        kotlinx.coroutines.runBlocking { runCatching { sink.save(snapshot) } }
    }

    /** Same write, scheduled on a caller-supplied scope instead of blocking. */
    private fun persistAsync(scope: kotlinx.coroutines.CoroutineScope?) {
        val sink = store ?: return
        val snapshot = index.snapshot()
        if (scope == null) {
            kotlinx.coroutines.runBlocking { runCatching { sink.save(snapshot) } }
        } else {
            scope.launch {
                if (snapshot.isEmpty()) sink.clear() else sink.save(snapshot)
            }
        }
    }

    private fun clearSnapshotBlocking(sink: RagStore) {
        kotlinx.coroutines.runBlocking { runCatching { sink.clear() } }
    }

    // --------------------------------------------------------------- internals

    /**
     * Turns one document's bytes into indexed excerpts.
     *
     * A `.zip` is expanded here rather than in [ingestDocument] so the same code path
     * serves a picked archive and an archive nested in a picked folder.
     */
    private fun indexAll(
        path: String,
        name: String,
        bytes: ByteArray,
        skipped: MutableList<String>,
        onProgress: (String) -> Unit,
    ): Int {
        if (RagChunker.isArchive(name)) {
            val members = TextExtractors.zip(bytes)
            if (members.isEmpty()) {
                skipped += "$path contained no readable documents"
                return 0
            }
            var added = 0
            for ((memberName, text) in members) {
                if (text.isBlank()) continue
                onProgress("Indexing ${memberName.take(60)}…")
                added += index.put("$path/$memberName", RagChunker.chunk("$path/$memberName", text))
            }
            if (added == 0) skipped += "$path contained no readable text"
            return added
        }

        val text = TextExtractors.extractText(name, bytes)
        if (text.isBlank()) {
            skipped += "$name has no extractable text layer"
            return 0
        }
        return index.put(path, RagChunker.chunk(path, text))
    }

    /** Reads a document, refusing anything over the per-file budget before buffering it. */
    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(16 * 1024)
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > RagChunker.MAX_DOCUMENT_BYTES) {
                    throw IllegalArgumentException(
                        "the document is larger than ${RagChunker.MAX_DOCUMENT_BYTES / 1024 / 1024} MB"
                    )
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun queryMimeType(uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()

    /** Everything indexed right now, for the knowledge-base panel. */
    fun indexSummary(): RagIndexSummary = index.summary()

    /**
     * Ranks the best excerpts for [query] and formats them as prompt context.
     * Returns an empty context (never `null`) so callers can compose unconditionally.
     */
    fun buildContext(query: RagQuery): RagContext {
        if (index.size == 0) return RagContext(emptyList(), "")
        return index.buildContext(query)
    }

    /** True when the index already holds excerpts from [path]. */
    fun isIndexed(path: String): Boolean = index.contains(path)

    /** A short, user-facing inventory of what is indexed. */
    fun describe(): String = index.summary().describe()

    companion object {
        /** One folder import stops here, so a picked `Downloads/` cannot pin the CPU. */
        const val MAX_FILES_PER_TREE = 200
    }
}

/** Convenience so the UI can render "notes.md — 4 pages, 6 excerpts". */
fun RagSourceSummary.describe(): String =
    "$sourcePath — $pages page(s), $chunks excerpt(s)"

/** Human-readable size for a document, used in the import log line. */
internal fun File.megabytes(): String = "%.1f MB".format(length() / (1024.0 * 1024.0))
