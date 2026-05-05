package com.whisperboard.model.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the deep [DictationHistoryRepository]. Per the slice 6b brief we
 * use Room's in-memory builder rather than mocking the Dao — the storage
 * layer is "deep" precisely because retention enforcement happens in SQL,
 * and a mock would let bugs hide there.
 *
 * The contract under test:
 *
 * - [DictationHistoryRepository.record] inserts when retention is enabled.
 * - When retention is [HistoryRetention.Off], `record` returns `null` and
 *   nothing is persisted.
 * - The DAO's [DictationHistoryDao.recent] returns rows in newest-first
 *   order.
 * - Retention enforcement runs on every insert: the database never
 *   exceeds the chosen limit.
 * - `delete(id)` and `deleteAll()` work as expected.
 *
 * [Robolectric] is required because Room's in-memory builder needs a
 * `Context`. The test JVM stays headless — no emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DictationHistoryRepositoryTest {

    private lateinit var database: WhisperBoardDatabase
    private lateinit var dao: DictationHistoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, WhisperBoardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dictationHistoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repository(retention: HistoryRetention) =
        DictationHistoryRepository(dao = dao, retentionProvider = { retention })

    private fun entry(
        polished: String = "Polished",
        raw: String = "raw",
        timestamp: Long = System.currentTimeMillis(),
        languages: String = "",
        targetApp: String? = null,
    ) = DictationEntry(
        polishedTranscript = polished,
        rawTranscript = raw,
        timestampMs = timestamp,
        detectedLanguages = languages,
        targetAppName = targetApp,
    )

    @Test
    fun `record inserts when retention is enabled`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository(HistoryRetention.OneHundred)
        val id = repo.record(entry(polished = "Hello, world."))
        assertNotNull("record must return an id when retention is on", id)
        assertEquals(1, dao.count())
    }

    @Test
    fun `record returns null and persists nothing when retention is off`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repository(HistoryRetention.Off)
            val id = repo.record(entry(polished = "should not persist"))
            assertNull("record must return null when retention is off", id)
            assertEquals(0, dao.count())
        }

    @Test
    fun `recent returns entries newest-first`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository(HistoryRetention.OneHundred)
        repo.record(entry(polished = "first", timestamp = 1_000L))
        repo.record(entry(polished = "second", timestamp = 2_000L))
        repo.record(entry(polished = "third", timestamp = 3_000L))

        val recent = repo.recent(limit = 50).first()
        assertEquals(listOf("third", "second", "first"), recent.map { it.polishedTranscript })
    }

    @Test
    fun `retention is enforced on insert`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository(HistoryRetention.Twenty5)
        // Insert 30 entries with strictly increasing timestamps so
        // ordering is deterministic. The repository's record path prunes
        // after each insert; final count must be 25.
        for (i in 1..30) {
            repo.record(entry(polished = "msg $i", timestamp = i.toLong() * 1_000))
        }
        assertEquals(25, dao.count())
        val survivors = dao.all().first().map { it.polishedTranscript }
        assertEquals(
            "Newest 25 must survive — entries 1..5 should be pruned",
            (30 downTo 6).map { "msg $it" },
            survivors,
        )
    }

    @Test
    fun `delete by id removes a single entry`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository(HistoryRetention.OneHundred)
        val a = repo.record(entry(polished = "a", timestamp = 1_000L))!!
        val b = repo.record(entry(polished = "b", timestamp = 2_000L))!!
        repo.delete(a)
        val survivors = repo.all().first()
        assertEquals(1, survivors.size)
        assertEquals(b, survivors.single().id)
    }

    @Test
    fun `deleteAll empties the table`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository(HistoryRetention.OneHundred)
        repeat(5) { i -> repo.record(entry(polished = "msg $i", timestamp = i.toLong())) }
        assertEquals(5, dao.count())
        repo.deleteAll()
        assertEquals(0, dao.count())
    }

    @Test
    fun `recent honours the limit even when more rows exist`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repository(HistoryRetention.OneHundred)
            for (i in 1..10) {
                repo.record(entry(polished = "msg $i", timestamp = i.toLong()))
            }
            val recent = repo.recent(limit = 3).first()
            assertEquals(3, recent.size)
            assertEquals(
                "Limit must take the newest 3",
                listOf("msg 10", "msg 9", "msg 8"),
                recent.map { it.polishedTranscript },
            )
        }

    @Test
    fun `target app name and languages round-trip through Room`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repository(HistoryRetention.OneHundred)
            repo.record(
                entry(
                    polished = "café au lait",
                    raw = "cafe au lait",
                    timestamp = 1_000L,
                    languages = "en,fr",
                    targetApp = "com.example.notes",
                ),
            )
            val out = repo.all().first().single()
            assertEquals("com.example.notes", out.targetAppName)
            assertEquals(listOf("en", "fr"), out.languageCodes)
            assertEquals("cafe au lait", out.rawTranscript)
            assertEquals("café au lait", out.polishedTranscript)
        }

    @Test
    fun `retention provider is read on every record call`() =
        runTest(UnconfinedTestDispatcher()) {
            // A retention that flips after the first call simulates the
            // user toggling the picker mid-session. The repository must
            // honour the *current* value on every record, not a snapshot
            // taken at construction time.
            var retention: HistoryRetention = HistoryRetention.OneHundred
            val repo = DictationHistoryRepository(
                dao = dao,
                retentionProvider = { retention },
            )
            assertNotNull(repo.record(entry(polished = "first")))
            retention = HistoryRetention.Off
            assertNull(
                "After flipping retention to off, record must no-op",
                repo.record(entry(polished = "second")),
            )
            assertEquals("Only the first insert survives", 1, dao.count())
        }

    @Test
    fun `count reflects current row count`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository(HistoryRetention.OneHundred)
        assertEquals(0, repo.count())
        repo.record(entry())
        repo.record(entry(timestamp = 2_000L))
        assertEquals(2, repo.count())
    }

    @Test
    fun `recent returns empty list when repository is empty`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = repository(HistoryRetention.OneHundred)
            assertTrue(repo.recent(limit = 50).first().isEmpty())
        }
}
