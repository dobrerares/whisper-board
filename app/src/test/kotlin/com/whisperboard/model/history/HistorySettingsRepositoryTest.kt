package com.whisperboard.model.history

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Privacy/History settings — same DataStore-with-temp-file pattern as
 * `BehaviorSettingsRepositoryTest`. Verifies the three knobs persist
 * independently and that the documented defaults are applied when no value
 * has been written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistorySettingsRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreJob: Job
    private lateinit var repository: HistorySettingsRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("history-prefs-test").toFile()
        dataStoreJob = SupervisorJob()
        dataStoreScope = CoroutineScope(UnconfinedTestDispatcher() + dataStoreJob)
        val store = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempDir, "history_prefs.preferences_pb") },
        )
        repository = HistorySettingsRepository(store)
    }

    @After
    fun tearDown() {
        dataStoreJob.cancel()
        dataStoreScope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `default retention is 100 entries`() = runTest {
        assertEquals(HistoryRetention.OneHundred, repository.retention.first())
        assertEquals(100, HistoryRetention.OneHundred.limit)
    }

    @Test
    fun `default sendTranscriptsToRemoteLlm is on`() = runTest {
        assertTrue(repository.sendTranscriptsToRemoteLlm.first())
        assertTrue(HistorySettingsRepository.DEFAULT_SEND_TRANSCRIPTS_TO_REMOTE_LLM)
    }

    @Test
    fun `default logDiagnosticsEnabled is off`() = runTest {
        assertFalse(repository.logDiagnosticsEnabled.first())
        assertFalse(HistorySettingsRepository.DEFAULT_LOG_DIAGNOSTICS)
    }

    @Test
    fun `setRetention persists across reads`() = runTest {
        repository.setRetention(HistoryRetention.Off)
        assertEquals(HistoryRetention.Off, repository.retention.first())

        repository.setRetention(HistoryRetention.FiveHundred)
        assertEquals(HistoryRetention.FiveHundred, repository.retention.first())

        repository.setRetention(HistoryRetention.Twenty5)
        assertEquals(HistoryRetention.Twenty5, repository.retention.first())
    }

    @Test
    fun `setSendTranscriptsToRemoteLlm persists across reads`() = runTest {
        repository.setSendTranscriptsToRemoteLlm(false)
        assertFalse(repository.sendTranscriptsToRemoteLlm.first())

        repository.setSendTranscriptsToRemoteLlm(true)
        assertTrue(repository.sendTranscriptsToRemoteLlm.first())
    }

    @Test
    fun `setLogDiagnosticsEnabled persists across reads`() = runTest {
        repository.setLogDiagnosticsEnabled(true)
        assertTrue(repository.logDiagnosticsEnabled.first())

        repository.setLogDiagnosticsEnabled(false)
        assertFalse(repository.logDiagnosticsEnabled.first())
    }

    @Test
    fun `the three knobs are independent`() = runTest {
        repository.setRetention(HistoryRetention.Twenty5)
        repository.setSendTranscriptsToRemoteLlm(false)
        // logDiagnostics must remain at its default OFF.
        assertEquals(HistoryRetention.Twenty5, repository.retention.first())
        assertFalse(repository.sendTranscriptsToRemoteLlm.first())
        assertFalse(repository.logDiagnosticsEnabled.first())

        repository.setLogDiagnosticsEnabled(true)
        // The other two must keep their previously-set values.
        assertEquals(HistoryRetention.Twenty5, repository.retention.first())
        assertFalse(repository.sendTranscriptsToRemoteLlm.first())
        assertTrue(repository.logDiagnosticsEnabled.first())
    }

    @Test
    fun `HistoryRetention isEnabled flag matches the off sentinel`() {
        assertFalse(HistoryRetention.Off.isEnabled)
        assertTrue(HistoryRetention.Twenty5.isEnabled)
        assertTrue(HistoryRetention.OneHundred.isEnabled)
        assertTrue(HistoryRetention.FiveHundred.isEnabled)
    }

    @Test
    fun `HistoryRetention fromLimit round-trips known values`() {
        assertEquals(HistoryRetention.Off, HistoryRetention.fromLimit(0))
        assertEquals(HistoryRetention.Twenty5, HistoryRetention.fromLimit(25))
        assertEquals(HistoryRetention.OneHundred, HistoryRetention.fromLimit(100))
        assertEquals(HistoryRetention.FiveHundred, HistoryRetention.fromLimit(500))
        // Unknown ints fall back to the default rather than throwing.
        assertEquals(HistoryRetention.OneHundred, HistoryRetention.fromLimit(-1))
        assertEquals(HistoryRetention.OneHundred, HistoryRetention.fromLimit(7))
    }
}
