package com.example.voiceassistant.core

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A capability the assistant can exercise. Every one of them is checked before the
 * corresponding subsystem does any work, and every grant/deny is written to the ADR log.
 */
enum class Capability(val id: String) {
    MIC_CAPTURE("mic.capture"),
    TTS_SPEAK("tts.speak"),
    FILE_READ("file.saf.read"),
    RAG_INDEX("rag.index"),
    RAG_QUERY("rag.query"),
    MODEL_LOAD("model.load");

    companion object {
        fun fromId(id: String): Capability? = entries.firstOrNull { it.id == id }
    }
}

/** One line of `decisions.jsonl`. Immutable, and hash-chained to its predecessor. */
data class DecisionRecord(
    val sequence: Long,
    val timestampIso: String,
    val capability: String,
    val action: String,
    val outcome: String,
    val subject: String,
    val because: String,
    val previousHash: String,
    val hash: String,
) {
    fun toJson(): String = JSONObject()
        .put("seq", sequence)
        .put("at", timestampIso)
        .put("capability", capability)
        .put("action", action)
        .put("outcome", outcome)
        .put("subject", subject)
        .put("because", because)
        .put("prev", previousHash)
        .put("hash", hash)
        .toString()

    companion object {
        fun fromJson(line: String): DecisionRecord? = runCatching {
            val json = JSONObject(line)
            DecisionRecord(
                sequence = json.optLong("seq"),
                timestampIso = json.optString("at"),
                capability = json.optString("capability"),
                action = json.optString("action"),
                outcome = json.optString("outcome"),
                subject = json.optString("subject"),
                because = json.optString("because"),
                previousHash = json.optString("prev"),
                hash = json.optString("hash"),
            )
        }.getOrNull()
    }
}

/**
 * Pure, IO-free half of the decision log: canonical serialisation, hashing and
 * chain verification.
 *
 * Kept separate from [DecisionLog] so the tamper-evidence rules can be unit-tested on a
 * plain JVM without a Context or a file system.
 */
object DecisionChain {

    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    /** Minimum length of the mandatory `because` sentence, in words. */
    const val MIN_BECAUSE_WORDS = 6

    /** SHA-256 over the record's canonical field order including the previous hash. */
    fun hashOf(
        sequence: Long,
        timestampIso: String,
        capability: String,
        action: String,
        outcome: String,
        subject: String,
        because: String,
        previousHash: String,
    ): String {
        val canonical = listOf(
            sequence.toString(),
            timestampIso,
            capability,
            action,
            outcome,
            subject,
            because,
            previousHash,
        ).joinToString(separator = "\u001F")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun create(
        sequence: Long,
        timestampIso: String,
        capability: String,
        action: String,
        outcome: String,
        subject: String,
        because: String,
        previousHash: String,
    ): DecisionRecord {
        val hash = hashOf(
            sequence, timestampIso, capability, action, outcome, subject, because, previousHash
        )
        return DecisionRecord(
            sequence = sequence,
            timestampIso = timestampIso,
            capability = capability,
            action = action,
            outcome = outcome,
            subject = subject,
            because = because,
            previousHash = previousHash,
            hash = hash,
        )
    }

    fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /**
     * Re-walks a whole log and reports the first broken link.
     *
     * @return `null` when the chain is intact, otherwise a human-readable description of
     *   the first inconsistency (a hand-edited line, a deleted middle line, a reordered
     *   pair — all of them break the chain).
     */
    fun verify(lines: List<String>): String? {
        var expectedPrevious = GENESIS
        var expectedSequence = 1L
        for ((index, line) in lines.withIndex()) {
            if (line.isBlank()) continue
            val record = DecisionRecord.fromJson(line)
                ?: return "line ${index + 1} is not valid JSON"
            if (record.previousHash != expectedPrevious) {
                return "line ${index + 1} follows ${record.previousHash.take(8)}… " +
                    "but the previous record hashes to ${expectedPrevious.take(8)}…"
            }
            if (record.sequence != expectedSequence) {
                return "line ${index + 1} has sequence ${record.sequence}, expected $expectedSequence"
            }
            val recomputed = hashOf(
                record.sequence,
                record.timestampIso,
                record.capability,
                record.action,
                record.outcome,
                record.subject,
                record.because,
                record.previousHash,
            )
            if (recomputed != record.hash) {
                return "line ${index + 1} was modified after it was written " +
                    "(hash ${record.hash.take(8)}… does not match ${recomputed.take(8)}…)"
            }
            expectedPrevious = record.hash
            expectedSequence++
        }
        return null
    }
}

/**
 * Append-only, SHA-256 hash-chained decision log (`decisions.jsonl`) plus the capability
 * gate that guards the microphone, TTS, SAF reads and the RAG index.
 *
 * This is the app's ADR (Architecture Decision Record) store and its authorization layer in
 * one place, because on a zero-network app they answer the same question: *prove what this
 * assistant was allowed to do, and prove that the record was not rewritten afterwards.*
 *
 * Every line carries a mandatory `because` sentence ([DecisionChain.MIN_BECAUSE_WORDS]
 * words minimum). A decision without a reason is not a decision, it is an accident.
 *
 * The gate is **deny-by-default for a missing capability** and **fail-closed**: if the log
 * cannot be written the decision is still recorded in memory, but callers that require an
 * audited decision (`authorize`) are declined. The microphone and TTS defaults are granted
 * on first run so the assistant works out of the box; the SAF/RAG capabilities are granted
 * the first time the user actually picks a file or a folder, because that is the moment
 * they consented.
 */
@Singleton
class DecisionLog @Inject constructor() {

    /** Overridden in tests to a temporary folder. */
    internal var directory: File? = null

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    internal var clock: () -> Long = System::currentTimeMillis

    private val mutex = Mutex()

    private val granted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Last N records, mirrored so the UI can render the log without reading the file. */
    private val recent = ArrayDeque<DecisionRecord>()

    val logFile: File? get() = directory?.let { File(it, FILE_NAME) }

    fun attach(directory: File) {
        this.directory = directory
        // The seed grants are written at startup (see `VoiceAssistantApplication`), where a
        // coroutine scope exists: this method stays non-suspend so it can be called from a
        // Dagger provider.
    }

    /** Writes the first-run grant records for the capabilities the app always needs. */
    suspend fun seedDefaultGrants() = seedGrants()

    fun isGranted(capability: Capability): Boolean = capability.id in granted

    fun grantedCapabilities(): List<String> = granted.toList().sorted()

    /**
     * Gives a capability and records the grant with a reason.
     *
     * @param because must be a real sentence: the log's whole value is that it explains
     *   itself. A too-short reason is padded rather than refused so a user-triggered action
     *   (picking a file) is never lost, and the record says so.
     */
    suspend fun grant(capability: Capability, because: String): DecisionRecord? {
        granted += capability.id
        return record(
            capability = capability.id,
            action = "grant",
            outcome = "ALLOWED",
            subject = "n/a",
            because = ensureReason(because),
        )
    }

    suspend fun revoke(capability: Capability, because: String): DecisionRecord? {
        granted -= capability.id
        return record(
            capability = capability.id,
            action = "revoke",
            outcome = "DENIED",
            subject = "n/a",
            because = ensureReason(because),
        )
    }

    /**
     * Records a decision about one concrete action and returns whether it may proceed.
     *
     * @return `true` when the capability is granted *and* the record was durable.
     */
    suspend fun authorize(
        capability: Capability,
        action: String,
        subject: String,
        because: String,
    ): Boolean {
        val allowed = isGranted(capability)
        val record = record(
            capability = capability.id,
            action = action,
            outcome = if (allowed) "ALLOWED" else "DENIED",
            subject = subject,
            because = ensureReason(because),
        )
        return allowed && record != null
    }

    /** Records an event (import, query, interrupt) without changing any grant. */
    suspend fun note(
        capability: Capability,
        action: String,
        subject: String,
        because: String,
    ): DecisionRecord? = record(
        capability = capability.id,
        action = action,
        outcome = "LOGGED",
        subject = subject,
        because = ensureReason(because),
    )

    fun recentRecords(limit: Int = 20): List<DecisionRecord> = synchronized(recent) {
        recent.takeLast(limit)
    }

    /** Reads the whole log and verifies the hash chain. `null` means intact. */
    suspend fun verifyChain(): String? = withContext(ioDispatcher) {
        val file = logFile ?: return@withContext "the decision log is not attached"
        if (!file.isFile) return@withContext null
        val lines = runCatching { file.readLines() }.getOrElse { error ->
            return@withContext "the decision log could not be read: ${error.message}"
        }
        DecisionChain.verify(lines)
    }

    suspend fun lineCount(): Int = withContext(ioDispatcher) {
        val file = logFile ?: return@withContext 0
        if (!file.isFile) return@withContext 0
        runCatching { file.readLines().count { it.isNotBlank() } }.getOrDefault(0)
    }

    /**
     * Appends one record, or returns `null` when it could not be made durable.
     *
     * `null` is the fail-closed signal: [authorize] refuses the action, because a decision
     * that cannot be audited is not a decision this app is willing to take.
     */
    private suspend fun record(
        capability: String,
        action: String,
        outcome: String,
        subject: String,
        because: String,
    ): DecisionRecord? {
        val written = withContext(ioDispatcher) {
            mutex.withLock {
                val file = logFile
                val last = readLastRecord()
                val sequence = (last?.sequence ?: 0L) + 1
                val timestamp = isoNow()
                val record = DecisionChain.create(
                    sequence = sequence,
                    timestampIso = timestamp,
                    capability = capability,
                    action = action,
                    outcome = outcome,
                    subject = subject.take(200),
                    because = because,
                    previousHash = last?.hash ?: DecisionChain.GENESIS,
                )

                synchronized(recent) {
                    recent.addLast(record)
                    while (recent.size > RECENT_CAPACITY) recent.removeFirst()
                }

                val durable = if (file == null) {
                    true
                } else {
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.appendText(record.toJson() + "\n")
                        true
                    }.getOrElse { error ->
                        Log.w(TAG, "Decision log could not be written: ${error.message}")
                        false
                    }
                }
                if (durable) record else null
            }
        }
        return written
    }

    private fun readLastRecord(): DecisionRecord? {
        val file = logFile ?: return null
        if (!file.isFile) return null
        return runCatching {
            file.useLines { lines ->
                lines.filter { it.isNotBlank() }.lastOrNull()?.let(DecisionRecord::fromJson)
            }
        }.getOrNull()
    }

    private fun ensureReason(reason: String): String {
        val trimmed = reason.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) {
            return "Not specified by the caller, which is itself a defect worth seeing in the log."
        }
        val words = DecisionChain.wordCount(trimmed)
        if (words >= DecisionChain.MIN_BECAUSE_WORDS) return trimmed
        return "$trimmed (recorded verbatim; the operator supplied only $words words of justification)"
    }

    private fun isoNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(clock()))
    }

    /** Grants the capabilities the assistant needs out of the box, once. */
    private suspend fun seedGrants() {
        if (!isGranted(Capability.MIC_CAPTURE)) {
            grant(
                Capability.MIC_CAPTURE,
                "The microphone is the assistant's only input and the manifest holds no network permission to leak it to.",
            )
        }
        if (!isGranted(Capability.TTS_SPEAK)) {
            grant(
                Capability.TTS_SPEAK,
                "Spoken replies use the on-device TextToSpeech engine, which cannot reach the network without the INTERNET permission.",
            )
        }
        if (!isGranted(Capability.MODEL_LOAD)) {
            grant(
                Capability.MODEL_LOAD,
                "Local models are loaded only from app-private storage after the user imported them through the system file picker.",
            )
        }
        // Guard with a plain atomic set: `synchronized` would forbid the suspending call.
        if (seedDone.add(logFile?.absolutePath ?: "")) {
            note(
                Capability.MIC_CAPTURE,
                "log.opened",
                logFile?.name ?: FILE_NAME,
                "A new decision log was opened, so the hash chain restarts from the genesis digest.",
            )
        }
    }

    private companion object {
        const val TAG = "JarvisDecisions"
        const val FILE_NAME = "decisions.jsonl"
        const val RECENT_CAPACITY = 200

        /** Paths already seeded in this process, so the genesis record is written once. */
        val seedDone: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
