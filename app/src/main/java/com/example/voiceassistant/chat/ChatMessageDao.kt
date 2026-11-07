package com.example.voiceassistant.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    /** The whole transcript, oldest first. */
    @Query("SELECT * FROM chat_messages ORDER BY id ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    /** The transcript, newest first, limited to [limit] rows. */
    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ChatMessageEntity>

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Update
    suspend fun update(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM chat_messages")
    suspend fun clear()

    /**
     * Writes the user's message and the placeholder for the assistant's reply
     * in ONE transaction.
     *
     * This matters for crash consistency. If the two inserts were separate,
     * a process death between them would leave a user message with no reply
     * row at all, and the next reply would have nothing to attach itself to.
     * With a single transaction the transcript is always either "nothing" or
     * "the user's message plus a pending reply".
     */
    @Transaction
    suspend fun beginTurn(
        userText: String,
        createdAt: Long
    ): Long {
        val userId = insert(
            ChatMessageEntity(
                role = ChatMessageEntity.ROLE_USER,
                content = userText,
                createdAt = createdAt,
                pending = false
            )
        )
        val replyId = insert(
            ChatMessageEntity(
                role = ChatMessageEntity.ROLE_ASSISTANT,
                content = "",
                createdAt = createdAt,
                pending = true
            )
        )
        return replyId
    }

    /** Completes a pending reply with its final text. */
    @Query("UPDATE chat_messages SET content = :content, pending = 0 WHERE id = :id")
    suspend fun completeReply(id: Long, content: String)

    /**
     * Marks every still-pending reply as interrupted.
     *
     * Called once when the app starts. A reply that is still pending at that
     * point can only have been cut short by the process being killed, because
     * generation and the app share a lifetime.
     */
    @Query(
        "UPDATE chat_messages SET content = :text, pending = 0 " +
            "WHERE pending = 1 AND content = ''"
    )
    suspend fun markInterruptedReplies(text: String): Int
}
