package com.whisperboard.bubble

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers persistence of the bubble visibility mode and bubble position.
 *
 * - Default visibility is [BubbleVisibilityMode.AlwaysVisible] per the brief.
 * - Setting a mode persists across reads.
 * - Position defaults to "unset" and round-trips through the store.
 * - Unknown stored visibility values fall back to the default — guards the
 *   repository against forward/backward incompatibility if the enum changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleSettingsRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var job: Job
    private lateinit var repository: BubbleSettingsRepository
    private val storeCounter = AtomicInteger(0)

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("bubble-prefs-test").toFile()
        job = SupervisorJob()
        scope = CoroutineScope(UnconfinedTestDispatcher() + job)
        repository = BubbleSettingsRepository(testStore())
    }

    @After
    fun tearDown() {
        job.cancel()
        scope.cancel()
        tempDir.deleteRecursively()
    }

    /**
     * Builds a fresh in-memory-backed DataStore for each call. Each call uses
     * a distinct file under the test temp directory so tests that build a
     * second repository (e.g. the new edge / cooldown / dismissedAt tests)
     * don't collide with the [Before]-built one.
     */
    private fun testStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = {
            File(tempDir, "bubble_prefs_${storeCounter.incrementAndGet()}.preferences_pb")
        },
    )

    @Test
    fun `default visibility is AlwaysVisible per the brief`() = runTest {
        assertEquals(BubbleVisibilityMode.AlwaysVisible, repository.visibilityMode.first())
        assertEquals(
            BubbleVisibilityMode.AlwaysVisible,
            BubbleSettingsRepository.DEFAULT_VISIBILITY,
        )
    }

    @Test
    fun `setVisibilityMode persists across reads`() = runTest {
        repository.setVisibilityMode(BubbleVisibilityMode.SummonedOnly)
        assertEquals(BubbleVisibilityMode.SummonedOnly, repository.visibilityMode.first())

        repository.setVisibilityMode(BubbleVisibilityMode.Disabled)
        assertEquals(BubbleVisibilityMode.Disabled, repository.visibilityMode.first())
    }

    @Test
    fun `default position is unset`() = runTest {
        val position = repository.position.first()
        assertTrue(
            "Position must report `isUnset` when nothing has been persisted, got $position",
            position.isUnset,
        )
    }

    @Test
    fun `setPosition round-trips`() = runTest {
        repository.setPosition(120, 480)
        val stored = repository.position.first()
        assertEquals(120, stored.x)
        assertEquals(480, stored.y)
        assertFalse(stored.isUnset)
    }

    @Test
    fun `setPosition can store zero without it counting as unset`() = runTest {
        // Regression check: (0, 0) is a valid position, not the sentinel.
        repository.setPosition(0, 0)
        val stored = repository.position.first()
        assertEquals(0, stored.x)
        assertEquals(0, stored.y)
        assertFalse(
            "(0,0) must be treated as a real position, not unset",
            stored.isUnset,
        )
    }

    // --- Standalone-use counter (gates the in-place insertion nudge) ---

    @Test
    fun `default standaloneUseCount is zero`() = runTest {
        assertEquals(0, repository.standaloneUseCount.first())
    }

    @Test
    fun `incrementStandaloneUseCount accumulates across calls`() = runTest {
        repository.incrementStandaloneUseCount()
        repository.incrementStandaloneUseCount()
        repository.incrementStandaloneUseCount()
        assertEquals(3, repository.standaloneUseCount.first())
    }

    // --- Accessibility-nudge dismissal flag ---

    @Test
    fun `default accessibilityNudgeDismissed is false`() = runTest {
        assertFalse(repository.accessibilityNudgeDismissed.first())
    }

    @Test
    fun `setAccessibilityNudgeDismissed persists across reads`() = runTest {
        repository.setAccessibilityNudgeDismissed(true)
        assertTrue(repository.accessibilityNudgeDismissed.first())

        // The flag is a dumb getter/setter — the brief's "never re-show
        // after dismissal" rule lives in the consumer (the nudge gate),
        // not in the repository.
        repository.setAccessibilityNudgeDismissed(false)
        assertFalse(repository.accessibilityNudgeDismissed.first())
    }

    @Test
    fun `edge defaults to RIGHT when unset`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        assertEquals(Edge.RIGHT, repo.edge.first())
    }

    @Test
    fun `setEdge persists and reads back`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        repo.setEdge(Edge.LEFT)
        assertEquals(Edge.LEFT, repo.edge.first())
    }

    @Test
    fun `cooldownMs defaults to 5 minutes`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        assertEquals(5L * 60_000L, repo.cooldownMs.first())
    }

    @Test
    fun `setCooldownMs persists and reads back`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        repo.setCooldownMs(15L * 60_000L)
        assertEquals(15L * 60_000L, repo.cooldownMs.first())
    }

    @Test
    fun `dismissedAt defaults to null`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        assertEquals(null, repo.dismissedAt.first())
    }

    @Test
    fun `setDismissedAt persists and clearDismissedAt removes`() = runTest {
        val repo = BubbleSettingsRepository(testStore())
        repo.setDismissedAt(123_456L)
        assertEquals(123_456L, repo.dismissedAt.first())
        repo.clearDismissedAt()
        assertEquals(null, repo.dismissedAt.first())
    }
}
