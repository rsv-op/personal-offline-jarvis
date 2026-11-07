package com.example.voiceassistant.chat

import com.example.voiceassistant.generation.LocalTextGenerator
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the chat transcript and drives a turn from end to end.
 *
 * A turn is:
 *  1. write the user's message AND a placeholder reply in one transaction;
 *  2. generate the reply;
 *  3. update the placeholder with the finished text.
 *
 * If the app is killed during step 2, the placeholder is still pending. The
 * next launch calls [recoverInterruptedReplies], which turns it into a
 * visible "interrupted" marker rather than leaving a blank bubble.
 */
@Singleton
class ChatHistoryRepository @Inject constructor(
    private val dao: ChatMessageDao,
    private val textGenerator: LocalTextGenerator
) {
    /** The whole transcript, oldest first. */
    fun observeMessages(): Flow<List<ChatMessageEntity>> = dao.observeAll()

    /**
     * Marks replies that were still pending when the app last died.
     *
     * @return how many replies were marked.
     */
    suspend fun recoverInterruptedReplies(): Int =
        dao.markInterruptedReplies(ChatMessageEntity.INTERRUPTED_TEXT)

    /**
     * Runs one conversational turn and records it in the transcript.
     *
     * The returned string is the reply, which is also written to the
     * transcript. If generation throws, the placeholder is updated with the
     * error so the transcript never keeps a dangling pending row.
     */
    suspend fun runTurn(userText: String): String {
        val replyId = dao.beginTurn(userText, System.currentTimeMillis())
        return try {
            val reply = textGenerator.generateReply(userText)
            dao.completeReply(replyId, reply)
            reply
        } catch (error: Exception) {
            val message = error.message ?: "The reply could not be generated."
            dao.completeReply(replyId, message)
            throw error
        }
    }

    /** Removes a single message. */
    suspend fun deleteMessage(id: Long) = dao.deleteById(id)

    /** Clears the whole transcript. */
    suspend fun clear() = dao.clear()
}
