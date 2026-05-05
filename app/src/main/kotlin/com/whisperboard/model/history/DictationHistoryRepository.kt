package com.whisperboard.model.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Deep wrapper around [DictationHistoryDao] that owns retention enforcement.
 *
 * The Dao knows how to insert, query, prune, count, delete. The repository
 * is the only thing that decides *whether* to insert at all (retention may be
 * [HistoryRetention.Off]) and *how many* to keep (pruning after every
 * insert).
 *
 * Single-method per concern intentionally — the surface stays small so the
 * post-processing pipeline's persistence call is one line at the call site.
 *
 * The `retentionProvider` is a suspending source-of-truth callback rather
 * than a static value, so flipping the retention picker in Settings affects
 * the *next* utterance without re-injecting the repository. This mirrors
 * the `autoInsertEnabledProvider` shape on `TranscriptDelivery`.
 */
class DictationHistoryRepository(
    private val dao: DictationHistoryDao,
    private val retentionProvider: suspend () -> HistoryRetention,
) {

    /**
     * Persist [entry] iff retention is enabled. Returns the new entry's id
     * when written, or `null` when retention is [HistoryRetention.Off]. The
     * caller can use the return value to log "persisted" telemetry without
     * re-reading the retention flow.
     *
     * After insert, prunes the table down to the retention limit so the
     * database never exceeds the user's chosen ceiling.
     */
    suspend fun record(entry: DictationEntry): Long? {
        val retention = retentionProvider()
        if (!retention.isEnabled) return null
        val id = dao.insert(entry)
        dao.pruneToRetentionLimit(retention.limit)
        return id
    }

    /**
     * Newest-first stream of recent entries, capped at [limit]. The
     * underlying query returns at most [limit] rows; if fewer rows exist,
     * fewer are returned.
     */
    fun recent(limit: Int): Flow<List<DictationEntry>> = dao.recent(limit)

    /**
     * Newest-first stream of *all* entries — used by the Settings → History
     * page, which intentionally does not cap. The retention setting still
     * caps the database size, so the unbounded stream is bounded by the
     * pruning that happens at insert time.
     */
    fun all(): Flow<List<DictationEntry>> = dao.all()

    /**
     * Stream of recent entries that switches to an empty list whenever the
     * user disables retention. Used by the IME's transcript area, which
     * needs to fall back to the single-utterance display on the off path.
     *
     * Marked [@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)]
     * because [flatMapLatest] is currently flagged. The transformation
     * itself is the documented pattern for "switch streams when X changes".
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun recentWhenEnabled(
        retentionFlow: Flow<HistoryRetention>,
        limit: Int,
    ): Flow<List<DictationEntry>> =
        retentionFlow.flatMapLatest { retention ->
            if (retention.isEnabled) dao.recent(limit) else flowOf(emptyList())
        }

    /** Delete a single entry by id. */
    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    /** Wipe the entire history. */
    suspend fun deleteAll() {
        dao.deleteAll()
    }

    /** How many entries the database currently holds — for debug surfaces only. */
    suspend fun count(): Int = dao.count()
}
