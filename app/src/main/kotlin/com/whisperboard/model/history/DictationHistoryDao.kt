package com.whisperboard.model.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Storage-level operations for the dictation history. Kept narrow on purpose
 * — the deep [DictationHistoryRepository] wraps this Dao and is what the rest
 * of the app talks to. The Dao itself only knows how to read, insert,
 * delete, count, and prune.
 *
 * Ordering: queries return newest-first (descending [DictationEntry.timestampMs])
 * so the IME's history scroll can render directly without a sort step.
 *
 * Pruning: [pruneToRetentionLimit] keeps the [limit] newest entries and
 * deletes the rest. Used by the repository on every insert when retention is
 * a positive value. Implemented as a single SQL statement so the database
 * does the work atomically.
 */
@Dao
interface DictationHistoryDao {

    @Insert
    suspend fun insert(entry: DictationEntry): Long

    @Query(
        "SELECT * FROM dictation_entries ORDER BY timestampMs DESC LIMIT :limit"
    )
    fun recent(limit: Int): Flow<List<DictationEntry>>

    @Query(
        "SELECT * FROM dictation_entries ORDER BY timestampMs DESC"
    )
    fun all(): Flow<List<DictationEntry>>

    @Query("DELETE FROM dictation_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM dictation_entries")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM dictation_entries")
    suspend fun count(): Int

    /**
     * Keep the [limit] newest entries; delete the rest. The subquery picks
     * the surviving ids by descending timestamp; the outer DELETE removes
     * everything else.
     */
    @Query(
        """
        DELETE FROM dictation_entries
        WHERE id NOT IN (
            SELECT id FROM dictation_entries
            ORDER BY timestampMs DESC
            LIMIT :limit
        )
        """
    )
    suspend fun pruneToRetentionLimit(limit: Int)
}
