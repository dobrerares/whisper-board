package com.whisperboard.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers the auto-insert / auto-copy toggles. The contract:
 *
 * - Defaults are ON for both toggles (preserves the "speak anywhere, get
 *   clean text" pitch — see CONTEXT.md and the slice 6a brief).
 * - Each toggle persists independently across reads.
 * - Toggles are independent — flipping one must not affect the other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BehaviorSettingsRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreJob: Job
    private lateinit var repository: BehaviorSettingsRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("behavior-prefs-test").toFile()
        // DataStore needs a long-lived scope. We give it a SupervisorJob we
        // own so we can cancel it in @After, otherwise runTest's structured
        // concurrency would treat the DataStore's collector as an
        // uncompleted child and fail the test.
        dataStoreJob = SupervisorJob()
        dataStoreScope = CoroutineScope(UnconfinedTestDispatcher() + dataStoreJob)
        val store = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempDir, "behavior_prefs.preferences_pb") },
        )
        repository = BehaviorSettingsRepository(store)
    }

    @After
    fun tearDown() {
        dataStoreJob.cancel()
        dataStoreScope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `auto-insert default is true`() = runTest {
        assertEquals(
            BehaviorSettingsRepository.DEFAULT_AUTO_INSERT,
            repository.autoInsertEnabled.first(),
        )
        assertTrue(
            "DEFAULT_AUTO_INSERT must be true per the slice 6a brief",
            BehaviorSettingsRepository.DEFAULT_AUTO_INSERT,
        )
    }

    @Test
    fun `auto-copy default is true`() = runTest {
        assertEquals(
            BehaviorSettingsRepository.DEFAULT_AUTO_COPY,
            repository.autoCopyEnabled.first(),
        )
        assertTrue(
            "DEFAULT_AUTO_COPY must be true per the slice 6a brief",
            BehaviorSettingsRepository.DEFAULT_AUTO_COPY,
        )
    }

    @Test
    fun `setAutoInsertEnabled persists across reads`() = runTest {
        repository.setAutoInsertEnabled(false)
        assertEquals(false, repository.autoInsertEnabled.first())

        repository.setAutoInsertEnabled(true)
        assertEquals(true, repository.autoInsertEnabled.first())
    }

    @Test
    fun `setAutoCopyEnabled persists across reads`() = runTest {
        repository.setAutoCopyEnabled(false)
        assertEquals(false, repository.autoCopyEnabled.first())

        repository.setAutoCopyEnabled(true)
        assertEquals(true, repository.autoCopyEnabled.first())
    }

    @Test
    fun `auto-insert and auto-copy are independent`() = runTest {
        repository.setAutoInsertEnabled(false)
        // Flipping auto-insert must not flip auto-copy.
        assertEquals(false, repository.autoInsertEnabled.first())
        assertEquals(true, repository.autoCopyEnabled.first())

        repository.setAutoCopyEnabled(false)
        assertEquals(false, repository.autoInsertEnabled.first())
        assertEquals(false, repository.autoCopyEnabled.first())

        repository.setAutoInsertEnabled(true)
        assertEquals(true, repository.autoInsertEnabled.first())
        assertEquals(false, repository.autoCopyEnabled.first())
    }
}
