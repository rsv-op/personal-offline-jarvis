package com.example.voiceassistant.rag

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device persistence for the retrieval index.
 *
 * ## Why this exists
 *
 * The index itself is deliberately in memory: it is bounded, lock-guarded and rebuilt in
 * milliseconds, so it never needed a database. What it *did* need is to survive a process
 * death — without that, every cold start silently threw away every document the user had
 * imported, and the "you have to re-import your notes after each restart" bug made the
 * whole feature feel broken. This store fixes that with the smallest thing that can work:
 * a single JSON snapshot in the app's private files directory.
 *
 * ## Format
 *
 * ```json
 * {
 *   "version": 1,
 *   "savedAt": 1731100000000,
 *   "sources": [
 *     { "path": "notes.md",
 *       "chunks": [ { "page": 1, "part": 1, "content": "…" } ] }
 *   ]
 * }
 * ```
 *
 * Tokens are **not** written to disk. They are derived from `"<path> <page> <content>"`
 * when the snapshot is loaded, which keeps the file ~40 % smaller and means a future change
 * to the tokeniser automatically applies to documents imported before it — the ranking
 * rules live in one place ([RagChunker.tokenize]) instead of being frozen into an old file.
 *
 * ## Guarantees
 *
 *  * **Local only.** The file is in `filesDir`, which is private to the app and excluded
 *    from cloud backup by `android:allowBackup="false"`. Nothing here can reach the network.
 *  * **Atomic.** Writes go to a sibling `.tmp` file and are `renameTo`'d into place, so a
 *    crash mid-save leaves the previous snapshot intact rather than a truncated one.
 *  * **Non-fatal.** Every failure is reported through [Log.w] and swallowed by the callers
 *    ([RagIngestor]); an unwritable index degrades to the in-memory behaviour the app had
 *    before, it never takes the assistant down.
 */
@Singleton
class RagStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** `filesDir/knowledge/index.json` — never on shared storage, never in the cache. */
    private val snapshotFile: File
        get() = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    private val writeInFlight = AtomicBoolean(false)

    /** Absolute path of the snapshot, for the diagnostics panel. */
    fun path(): String = snapshotFile.absolutePath

    /** Size of the saved snapshot in bytes, or 0 when nothing has been saved yet. */
    fun sizeBytes(): Long = snapshotFile.takeIf { it.isFile }?.length() ?: 0L

    /**
     * Writes [chunks] to disk, replacing the previous snapshot.
     *
     * Chunks that share a source path are grouped so the file keeps the index's own
     * file-then-page ordering, which makes the snapshot readable by a human debugging it.
     */
    suspend fun save(chunks: List<RagChunk>): Boolean = withContext(Dispatchers.IO) {
        if (!writeInFlight.compareAndSet(false, true)) return@withContext false
        try {
            val root = JSONObject()
            root.put("version", FORMAT_VERSION)
            root.put("savedAt", System.currentTimeMillis())

            val bySourced = chunks.groupBy { it.sourcePath }
            val sources = JSONArray()
            for ((sourcePath, group) in bySourced) {
                val source = JSONObject()
                source.put("path", sourcePath)
                val entries = JSONArray()
                for (chunk in group.sortedWith(compareBy({ it.pageNumber }, { it.partIndex }))) {
                    val entry = JSONObject()
                    entry.put("page", chunk.pageNumber)
                    entry.put("part", chunk.partIndex)
                    entry.put("content", chunk.content)
                    entries.put(entry)
                }
                source.put("chunks", entries)
                sources.put(source)
            }
            root.put("sources", sources)

            val target = snapshotFile
            val parent = target.parentFile ?: return@withContext false
            if (!parent.isDirectory && !parent.mkdirs()) return@withContext false

            val staging = File(parent, "$FILE_NAME.tmp")
            staging.writeText(root.toString())
            if (target.isFile && !target.delete()) {
                staging.delete()
                return@withContext false
            }
            val moved = staging.renameTo(target)
            if (!moved) staging.delete()
            moved
        } catch (error: Exception) {
            Log.w(TAG, "could not save the knowledge snapshot", error)
            false
        } finally {
            writeInFlight.set(false)
        }
    }

    /**
     * Reads the saved snapshot back.
     *
     * A corrupt or newer-format file is treated as "no documents": returning half a
     * knowledge base silently would be worse than returning none, because the user would
     * have no way to tell which of their notes the assistant can still see.
     */
    suspend fun load(): List<RagChunk> = withContext(Dispatchers.IO) {
        val file = snapshotFile
        if (!file.isFile) return@withContext emptyList()
        try {
            val root = JSONObject(file.readText())
            val version = root.optInt("version", 0)
            if (version != FORMAT_VERSION) {
                Log.w(TAG, "ignoring knowledge snapshot with version $version")
                return@withContext emptyList()
            }
            val sources = root.optJSONArray("sources") ?: return@withContext emptyList()
            val restored = ArrayList<RagChunk>()
            for (s in 0 until sources.length()) {
                val source = sources.optJSONObject(s) ?: continue
                val sourcePath = source.optString("path").takeIf { it.isNotBlank() } ?: continue
                val entries = source.optJSONArray("chunks") ?: continue
                for (c in 0 until entries.length()) {
                    val entry = entries.optJSONObject(c) ?: continue
                    val content = entry.optString("content")
                    if (content.isBlank()) continue
                    val page = entry.optInt("page", 1).coerceAtLeast(1)
                    val part = entry.optInt("part", 1).coerceAtLeast(1)
                    restored += RagChunk(
                        sourcePath = sourcePath,
                        pageNumber = page,
                        partIndex = part,
                        content = content,
                        // Rebuilt, never trusted from disk: the ranking rules stay in
                        // RagChunker so a tokeniser change applies to old imports too.
                        tokens = RagChunker.tokenize("$sourcePath page $page $content"),
                    )
                }
            }
            restored
        } catch (error: Exception) {
            Log.w(TAG, "could not read the knowledge snapshot", error)
            emptyList()
        }
    }

    /** Deletes the snapshot. Called when the user clears the knowledge base. */
    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        val file = snapshotFile
        try {
            !file.isFile || file.delete()
        } catch (error: Exception) {
            Log.w(TAG, "could not delete the knowledge snapshot", error)
            false
        }
    }

    private companion object {
        const val TAG = "RagStore"
        const val DIRECTORY = "knowledge"
        const val FILE_NAME = "index.json"

        /** Bump when the JSON shape changes; older snapshots are then ignored. */
        const val FORMAT_VERSION = 1
    }
}
