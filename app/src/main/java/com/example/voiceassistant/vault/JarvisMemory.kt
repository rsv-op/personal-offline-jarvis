package com.example.voiceassistant.vault

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything JARVIS remembers about you, kept in the encrypted [SecureVault]:
 *
 *  * **taught phrases** — utterances you paired with an action, which the intent brain
 *    consults before the neural network (this is the "learns directly from your voice" part);
 *  * **preferences** — small key/value facts such as your wake word or name;
 *  * **action log** — a bounded, append-only record of what the assistant actually did,
 *    so device-changing actions stay auditable.
 */
@Singleton
class JarvisMemory @Inject constructor(
    private val vault: SecureVault
) {
    /**
     * Wall clock, overridable so unit tests can assert on stored timestamps.
     * (Kept as a property rather than a constructor parameter: Dagger injects only the
     * vault, and a second constructor parameter with a default value would not be
     * resolvable at compile time.)
     */
    internal var clock: () -> Long = System::currentTimeMillis

    /**
     * Dispatcher for vault IO. Injectable so tests can drive memory reads/writes with a
     * test dispatcher (virtual time) instead of real threads.
     */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    data class TaughtExample(val phrase: String, val label: String, val taughtAt: Long)

    data class ActionRecord(
        val label: String,
        val utterance: String,
        val outcome: String,
        val at: Long
    )

    data class Summary(
        val taughtExamples: Int,
        val preferences: Int,
        val loggedActions: Int,
        val backend: String
    )

    private val mutex = Mutex()

    suspend fun summary(): Summary = withContext(ioDispatcher) {
        mutex.withLock {
            val document = load()
            Summary(
                taughtExamples = document.optJSONArray(KEY_EXAMPLES)?.length() ?: 0,
                preferences = document.optJSONObject(KEY_FACTS)?.length() ?: 0,
                loggedActions = document.optJSONArray(KEY_ACTIONS)?.length() ?: 0,
                backend = vault.backend
            )
        }
    }

    suspend fun examples(): List<TaughtExample> = withContext(ioDispatcher) {
        mutex.withLock { readExamples(load()) }
    }

    /** Stores (or replaces) a phrase→label pair. Returns the stored example. */
    suspend fun teach(phrase: String, label: String): TaughtExample = withContext(ioDispatcher) {
        val clean = phrase.trim()
        require(clean.isNotEmpty()) { "A taught phrase cannot be empty" }
        require(label.isNotBlank()) { "A taught phrase needs an action" }
        mutex.withLock {
            val document = load()
            val example = TaughtExample(clean, label.uppercase(), clock())
            val remaining = readExamples(document).filterNot {
                it.phrase.equals(clean, ignoreCase = true)
            }
            writeExamples(document, remaining + example)
            persist(document)
            example
        }
    }

    suspend fun forget(phrase: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            val document = load()
            val examples = readExamples(document)
            val remaining = examples.filterNot { it.phrase.equals(phrase.trim(), ignoreCase = true) }
            if (remaining.size == examples.size) {
                false
            } else {
                writeExamples(document, remaining)
                persist(document)
                true
            }
        }
    }

    suspend fun forgetAll(): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            val document = load()
            writeExamples(document, emptyList())
            persist(document)
        }
    }

    suspend fun remember(key: String, value: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val document = load()
            val facts = document.optJSONObject(KEY_FACTS) ?: JSONObject()
            facts.put(key, value)
            document.put(KEY_FACTS, facts)
            persist(document)
        }
    }

    suspend fun recall(key: String): String? = withContext(ioDispatcher) {
        mutex.withLock { load().optJSONObject(KEY_FACTS)?.optString(key)?.takeIf(String::isNotEmpty) }
    }

    suspend fun preferences(): Map<String, String> = withContext(ioDispatcher) {
        mutex.withLock {
            val facts = load().optJSONObject(KEY_FACTS) ?: return@withLock emptyMap()
            facts.keys().asSequence().associateWith { key -> facts.optString(key) }
        }
    }

    /** Records what the assistant did. The log is capped so it cannot grow forever. */
    suspend fun logAction(label: String, utterance: String, outcome: String) =
        withContext(ioDispatcher) {
            mutex.withLock {
                val document = load()
                val existing = document.optJSONArray(KEY_ACTIONS) ?: JSONArray()
                val updated = JSONArray()
                updated.put(
                    JSONObject()
                        .put("label", label)
                        .put("utterance", utterance)
                        .put("outcome", outcome)
                        .put("at", clock())
                )
                for (index in 0 until minOf(existing.length(), MAX_LOGGED_ACTIONS - 1)) {
                    updated.put(existing.getJSONObject(index))
                }
                document.put(KEY_ACTIONS, updated)
                persist(document)
            }
        }

    suspend fun recentActions(limit: Int = 10): List<ActionRecord> = withContext(ioDispatcher) {
        mutex.withLock {
            val actions = load().optJSONArray(KEY_ACTIONS) ?: return@withLock emptyList()
            (0 until minOf(limit, actions.length())).map { index ->
                val entry = actions.getJSONObject(index)
                ActionRecord(
                    label = entry.optString("label"),
                    utterance = entry.optString("utterance"),
                    outcome = entry.optString("outcome"),
                    at = entry.optLong("at")
                )
            }
        }
    }

    private fun load(): JSONObject = vault.read(DOCUMENT) ?: JSONObject()

    private fun persist(document: JSONObject) {
        vault.write(DOCUMENT, document)
    }

    private fun readExamples(document: JSONObject): List<TaughtExample> {
        val array = document.optJSONArray(KEY_EXAMPLES) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val phrase = entry.optString("phrase")
            val label = entry.optString("label")
            if (phrase.isBlank() || label.isBlank()) {
                null
            } else {
                TaughtExample(phrase, label, entry.optLong("taughtAt"))
            }
        }
    }

    private fun writeExamples(document: JSONObject, examples: List<TaughtExample>) {
        val array = JSONArray()
        for (example in examples) {
            array.put(
                JSONObject()
                    .put("phrase", example.phrase)
                    .put("label", example.label)
                    .put("taughtAt", example.taughtAt)
            )
        }
        document.put(KEY_EXAMPLES, array)
    }

    companion object {
        const val DOCUMENT = "jarvis-memory"
        const val MAX_LOGGED_ACTIONS = 50

        private const val KEY_EXAMPLES = "taughtExamples"
        private const val KEY_FACTS = "preferences"
        private const val KEY_ACTIONS = "actionLog"
    }
}
