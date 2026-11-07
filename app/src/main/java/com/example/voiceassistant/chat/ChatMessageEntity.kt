package com.example.voiceassistant.chat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of the chat transcript.
 *
 * The transcript lives in Room (with WAL journalling) rather than in the
 * encrypted vault: transcripts are a convenience, not a secret. The vault keeps
 * the things that genuinely need protecting — taught phrases and the user's
 * stored preferences — under an AES-GCM key held in the device's secure
 * element.
 *
 * A row with [role] = [ROLE_USER] is a message the user sent. A row with
 * [role] = [ROLE_ASSISTANT] is the assistant's reply. A reply is written as a
 * *placeholder* first and then updated in place, so a process death in the
 * middle of a generation leaves a visible "interrupted" marker instead of
 * silently losing the turn.
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** [ROLE_USER] or [ROLE_ASSISTANT]. */
    @ColumnInfo(name = "role")
    val role: String,

    /** The text of the message. For a pending reply this is empty until it completes. */
    @ColumnInfo(name = "content")
    val content: String,

    /** Epoch milliseconds when the row was created. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /**
     * True while the assistant's reply is still being generated.
     *
     * A reply that is still pending when the app starts was interrupted by a
     * process death, and is marked as such on the next launch.
     */
    @ColumnInfo(name = "pending")
    val pending: Boolean = false
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** The text shown for a reply that was cut short by the app being killed. */
        const val INTERRUPTED_TEXT = "The reply was interrupted."
    }
}
