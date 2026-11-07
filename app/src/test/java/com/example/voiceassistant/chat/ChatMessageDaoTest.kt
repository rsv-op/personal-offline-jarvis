package com.example.voiceassistant.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the chat transcript's crash-consistency behaviour.
 *
 * The two properties that matter:
 *
 *  1. A turn is written as the user's message AND a pending reply placeholder
 *     in one transaction, so the transcript is never half a turn.
 *  2. A reply that is still pending when the app next starts was interrupted
 *     by a process death, and is marked as such.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatMessageDaoTest {

    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatMessageDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ChatDatabase::class.java
        )
            // WAL is what the production database uses; in-memory databases
            // default to TRUNCATE, so it is set explicitly to exercise the
            // same journal mode.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()
        dao = database.chatMessageDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `beginTurn writes the user message and a pending placeholder together`() = runTest {
        val replyId = dao.beginTurn("what time is it?", 1_000L)

        val rows = dao.recent(10)
        assertEquals(2, rows.size)

        val user = rows.first { it.role == ChatMessageEntity.ROLE_USER }
        assertEquals("what time is it?", user.content)
        assertFalse(user.pending)

        val reply = rows.first { it.role == ChatMessageEntity.ROLE_ASSISTANT }
        assertEquals(replyId, reply.id)
        assertEquals("", reply.content)
        assertTrue("the reply placeholder must start pending", reply.pending)
    }

    @Test
    fun `completing a reply fills in the text and clears the pending flag`() = runTest {
        val replyId = dao.beginTurn("hello", 1_000L)
        dao.completeReply(replyId, "Hello there.")

        val reply = dao.recent(10).first { it.id == replyId }
        assertEquals("Hello there.", reply.content)
        assertFalse(reply.pending)
    }

    @Test
    fun `pending replies are marked interrupted on the next launch`() = runTest {
        val replyId = dao.beginTurn("hello", 1_000L)
        // The app is killed here: the reply is still pending.

        val marked = dao.markInterruptedReplies(ChatMessageEntity.INTERRUPTED_TEXT)

        assertEquals(1, marked)
        val reply = dao.recent(10).first { it.id == replyId }
        assertEquals(ChatMessageEntity.INTERRUPTED_TEXT, reply.content)
        assertFalse(reply.pending)
    }

    @Test
    fun `marking interrupted replies leaves completed turns alone`() = runTest {
        val replyId = dao.beginTurn("hello", 1_000L)
        dao.completeReply(replyId, "Hello there.")
        val secondReplyId = dao.beginTurn("and again", 2_000L)

        val marked = dao.markInterruptedReplies(ChatMessageEntity.INTERRUPTED_TEXT)

        assertEquals(1, marked)
        val completed = dao.recent(10).first { it.id == replyId }
        assertEquals("Hello there.", completed.content)

        val interrupted = dao.recent(10).first { it.id == secondReplyId }
        assertEquals(ChatMessageEntity.INTERRUPTED_TEXT, interrupted.content)
    }

    @Test
    fun `the transcript is observed oldest first`() = runTest {
        dao.beginTurn("first", 1_000L)
        dao.beginTurn("second", 2_000L)

        val rows = dao.observeAll().first()
        val userMessages = rows.filter { it.role == ChatMessageEntity.ROLE_USER }
        assertEquals(listOf("first", "second"), userMessages.map { it.content })
    }

    @Test
    fun `clearing removes every row`() = runTest {
        dao.beginTurn("first", 1_000L)
        dao.beginTurn("second", 2_000L)

        dao.clear()

        assertTrue(dao.observeAll().first().isEmpty())
    }
}
