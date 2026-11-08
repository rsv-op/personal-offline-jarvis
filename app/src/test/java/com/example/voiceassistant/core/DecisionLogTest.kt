package com.example.voiceassistant.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The authorization gate and the hash-chained ADR log.
 *
 * The tamper test is the point of the whole file: editing one character of one line must be
 * detectable by re-walking the chain, otherwise the log is decoration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DecisionLogTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var directory: File
    private lateinit var log: DecisionLog

    @Before
    fun setUp() {
        directory = temporaryFolder.newFolder("decisions")
        log = DecisionLog().apply {
            this.directory = directory
            ioDispatcher = UnconfinedTestDispatcher()
        }
    }

    @Test
    fun `the default grants are written once and recorded`() = runTest {
        log.seedDefaultGrants()

        assertTrue(log.isGranted(Capability.MIC_CAPTURE))
        assertTrue(log.isGranted(Capability.TTS_SPEAK))
        assertTrue(log.isGranted(Capability.MODEL_LOAD))
        // Reading documents is *not* granted until the user picks one.
        assertFalse(log.isGranted(Capability.FILE_READ))
        assertFalse(log.isGranted(Capability.RAG_INDEX))

        val file = log.logFile
        assertNotNull("the log file must be attached", file)
        assertTrue(file!!.isFile)
        assertTrue(file.readLines().all { it.isNotBlank() })
    }

    @Test
    fun `an ungranted capability is denied and the denial is logged`() = runTest {
        val allowed = log.authorize(
            capability = Capability.RAG_INDEX,
            action = "import.document",
            subject = "notes.md",
            because = "The user has not picked a document yet, so there is nothing to authorize.",
        )
        assertFalse(allowed)

        val record = log.recentRecords().last()
        assertEquals("DENIED", record.outcome)
        assertEquals(Capability.RAG_INDEX.id, record.capability)
    }

    @Test
    fun `granting a capability lets the next action through`() = runTest {
        log.grant(
            Capability.RAG_INDEX,
            "The user picked a folder in the system file picker, which is explicit consent to read it.",
        )
        val allowed = log.authorize(
            Capability.RAG_INDEX,
            "import.tree",
            "Downloads/notes",
            "The folder was chosen deliberately and the import runs entirely on device storage.",
        )
        assertTrue(allowed)

        log.revoke(
            Capability.RAG_INDEX,
            "The user cleared the knowledge base and asked for the documents to be forgotten.",
        )
        assertFalse(log.isGranted(Capability.RAG_INDEX))
    }

    @Test
    fun `every record carries a mandatory reason`() = runTest {
        val record = log.note(
            Capability.MIC_CAPTURE,
            "handsfree.start",
            "continuous",
            "too short",
        )
        assertNotNull(record)
        assertTrue(record!!.because.length > "too short".length)
        assertTrue(record.because.contains("justification"))

        val uncaused = log.note(Capability.MIC_CAPTURE, "wake", "heretic", "")
        assertTrue(uncaused!!.because.contains("defect"))
    }

    @Test
    fun `the chain verifies when untouched`() = runTest {
        log.seedDefaultGrants()
        repeat(4) { index ->
            log.note(
                Capability.RAG_QUERY,
                "retrieve",
                "query $index",
                "A question was answered from documents already stored on this phone.",
            )
        }
        assertNull(log.verifyChain())
        // Three seeded grants plus the "log opened" note, then four retrievals.
        assertEquals(8L, log.lineCount())
    }

    @Test
    fun `editing one character of one line breaks the chain`() = runTest {
        log.seedDefaultGrants()
        log.note(
            Capability.RAG_QUERY,
            "retrieve",
            "battery",
            "A question was answered from documents already stored on this phone.",
        )
        val file = log.logFile!!
        val lines = file.readLines().toMutableList()
        // Change the outcome of the middle record without recomputing its hash.
        val target = lines.lastIndex / 2
        lines[target] = lines[target].replace("\"LOGGED\"", "\"DENIED\"")
        file.writeText(lines.joinToString("\n", postfix = "\n"))

        val verdict = log.verifyChain()
        assertNotNull(verdict)
        assertTrue("verdict was: $verdict", verdict!!.contains("modified"))
    }

    @Test
    fun `deleting a record from the middle breaks the chain`() = runTest {
        log.seedDefaultGrants()
        repeat(3) {
            log.note(
                Capability.TTS_SPEAK,
                "speak",
                "reply",
                "Spoken replies use the on-device engine, which has no network path at all.",
            )
        }
        val file = log.logFile!!
        val lines = file.readLines()
        file.writeText(lines.filterIndexed { index, _ -> index != 2 }.joinToString("\n", postfix = "\n"))

        val verdict = log.verifyChain()
        assertNotNull(verdict)
    }

    @Test
    fun `the pure chain helper rejects a reordered pair`() {
        val first = DecisionChain.create(
            sequence = 1,
            timestampIso = "2026-01-01T00:00:00Z",
            capability = "mic.capture",
            action = "grant",
            outcome = "ALLOWED",
            subject = "n/a",
            because = "The microphone is the only input and the app has no network permission.",
            previousHash = DecisionChain.GENESIS,
        )
        val second = DecisionChain.create(
            sequence = 2,
            timestampIso = "2026-01-01T00:00:01Z",
            capability = "mic.capture",
            action = "wake",
            outcome = "LOGGED",
            subject = "heretic",
            because = "A wake word opened the command window after the microphone had been granted.",
            previousHash = first.hash,
        )

        assertNull(DecisionChain.verify(listOf(first.toJson(), second.toJson())))
        assertNotNull(DecisionChain.verify(listOf(second.toJson(), first.toJson())))
        assertEquals(DecisionChain.GENESIS.length, 64)
        assertEquals(64, first.hash.length)
        assertTrue(DecisionChain.wordCount("one two three") == 3)
    }

    @Test
    fun `an unwritable log fails closed`() = runTest {
        val blocked = File(temporaryFolder.root, "blocked-parent").apply { createNewFile() }
        val closed = DecisionLog().apply {
            directory = File(blocked, "cannot-create")
            ioDispatcher = UnconfinedTestDispatcher()
        }
        closed.grant(
            Capability.FILE_READ,
            "This grant cannot be persisted, so it must not be treated as authorized.",
        )
        val allowed = closed.authorize(
            Capability.FILE_READ,
            "import.document",
            "notes.md",
            "The log is unwritable, and an unauditable read is exactly what this gate exists to prevent.",
        )
        assertFalse(allowed)
    }
}
