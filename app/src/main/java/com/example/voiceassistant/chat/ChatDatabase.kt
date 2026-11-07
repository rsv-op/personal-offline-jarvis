package com.example.voiceassistant.chat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ChatMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        private const val DATABASE_NAME = "jarvis-chat.db"

        @Volatile
        private var instance: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        /**
         * The database is opened with WAL journalling.
         *
         * WAL matters here because the transcript is written while a
         * generation is running: with the default rollback journal, a reader
         * (the chat UI) blocks the writer (the generator) for the duration of
         * the transaction. In WAL mode readers never block writers and vice
         * versa, so the UI can observe a half-finished reply without
         * stalling the model.
         */
        private fun build(context: Context): ChatDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                DATABASE_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration()
                .build()
    }
}
