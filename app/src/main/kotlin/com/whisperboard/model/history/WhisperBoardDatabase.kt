package com.whisperboard.model.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for the dictation history. Single-table for now; future
 * tables (e.g. usage telemetry) would join here.
 *
 * Migrations: this is the first persisted shape, so v1 is the only version.
 * Future schema changes use `fallbackToDestructiveMigration()` per the
 * issue brief — history is recoverable by re-dictating, so a migration is
 * not worth its cost in v1.
 */
@Database(
    entities = [DictationEntry::class],
    version = 1,
    exportSchema = false,
)
abstract class WhisperBoardDatabase : RoomDatabase() {
    abstract fun dictationHistoryDao(): DictationHistoryDao

    companion object {
        private const val DATABASE_NAME = "whisper_board.db"

        @Volatile
        private var instance: WhisperBoardDatabase? = null

        fun getInstance(context: Context): WhisperBoardDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }
        }

        private fun build(context: Context): WhisperBoardDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                WhisperBoardDatabase::class.java,
                DATABASE_NAME,
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
