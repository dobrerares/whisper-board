package com.whisperboard.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the auto-insert vs preview-then-commit decision. The contract:
 *
 * - Auto-insert ON (default): finished transcripts are emitted on
 *   [TranscriptDelivery.autoInsertRequests] and the staged preview is cleared.
 * - Auto-insert OFF: finished transcripts are staged in
 *   [TranscriptDelivery.transcribedText] and no auto-insert event fires.
 * - Empty input is a no-op past clearing any staged preview.
 * - Each call to [TranscriptDelivery.deliver] reads the current value of the
 *   provider, so toggling auto-insert in Settings affects the *next*
 *   utterance.
 *
 * Tests use [UnconfinedTestDispatcher] so subscribers attach to the
 * `MutableSharedFlow` synchronously inside `async {}` — without that, the
 * flow's no-replay semantics would race with the test's `deliver()` call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptDeliveryTest {

    @Test
    fun `auto-insert on emits to autoInsertRequests and clears the staged preview`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = TranscriptDelivery(autoInsertEnabledProvider = { true })

            // The unconfined dispatcher runs the async body up to the first
            // suspension point eagerly, so `first()` is subscribed before we
            // call `deliver()` on the next line.
            val received = async { delivery.autoInsertRequests.first() }

            delivery.deliver("First, I went to the store.")

            assertEquals("First, I went to the store.", received.await())
            assertEquals("", delivery.transcribedText.value)
        }

    @Test
    fun `auto-insert off stages the transcript and does not emit`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = TranscriptDelivery(autoInsertEnabledProvider = { false })

            // Race a one-shot collector against deliver(). With auto-insert
            // off, nothing should arrive within the test scheduler's window.
            val collected = async {
                withTimeoutOrNull(timeMillis = 50) { delivery.autoInsertRequests.first() }
            }

            delivery.deliver("Preview this please.")

            assertEquals("Preview this please.", delivery.transcribedText.value)
            assertNull(
                "auto-insert off must not emit; got ${collected.await()}",
                collected.await(),
            )
        }

    @Test
    fun `empty input is a no-op and clears any staged preview`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = TranscriptDelivery(autoInsertEnabledProvider = { false })

            delivery.deliver("staged")
            assertEquals("staged", delivery.transcribedText.value)

            delivery.deliver("")
            assertEquals("", delivery.transcribedText.value)
        }

    @Test
    fun `provider is read on each deliver call`() =
        runTest(UnconfinedTestDispatcher()) {
            var enabled = true
            val delivery = TranscriptDelivery(autoInsertEnabledProvider = { enabled })

            // First utterance: auto-insert on — collect the first emission so
            // it is not dropped, and assert nothing is staged.
            val firstEvent = async { delivery.autoInsertRequests.first() }
            delivery.deliver("first")
            assertEquals("first", firstEvent.await())
            assertEquals("", delivery.transcribedText.value)

            // Toggle off; next utterance must stage instead of emitting.
            enabled = false
            delivery.deliver("second")
            assertEquals("second", delivery.transcribedText.value)
        }

    @Test
    fun `clearStaged empties the preview without affecting auto-insert state`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = TranscriptDelivery(autoInsertEnabledProvider = { false })
            delivery.deliver("staged for review")
            assertEquals("staged for review", delivery.transcribedText.value)

            delivery.clearStaged()
            assertEquals("", delivery.transcribedText.value)

            // After clearing, the next deliver still respects the off provider.
            delivery.deliver("another")
            assertEquals("another", delivery.transcribedText.value)
        }

    @Test
    fun `auto-insert on emits each delivered transcript in order`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = TranscriptDelivery(autoInsertEnabledProvider = { true })

            val collected = async {
                delivery.autoInsertRequests.take(3).toList()
            }

            delivery.deliver("alpha")
            delivery.deliver("beta")
            delivery.deliver("gamma")

            assertEquals(listOf("alpha", "beta", "gamma"), collected.await())
            assertTrue(
                "auto-insert path must keep transcribedText empty; got '${delivery.transcribedText.value}'",
                delivery.transcribedText.value.isEmpty(),
            )
        }

    @Test
    fun `target app name is captured at delivery time`() =
        runTest(UnconfinedTestDispatcher()) {
            // The provider returns the "current" focused-field package
            // name. Per the slice 6b brief, the persistence layer reads
            // the snapshot taken at delivery time so a focus change
            // after delivery doesn't relabel the entry.
            var currentApp: String? = "com.example.notes"
            val delivery = TranscriptDelivery(
                autoInsertEnabledProvider = { true },
                targetAppNameProvider = { currentApp },
            )

            val received = async { delivery.autoInsertRequests.first() }
            delivery.deliver("first")
            received.await()
            assertEquals("com.example.notes", delivery.lastTargetAppName())

            // Focus moves before persistence reads the snapshot — but
            // persistence reads `lastTargetAppName()`, which captured the
            // earlier value. The next deliver call will refresh it.
            currentApp = "com.example.calendar"
            assertEquals(
                "Snapshot must persist between deliveries",
                "com.example.notes",
                delivery.lastTargetAppName(),
            )

            val secondReceived = async { delivery.autoInsertRequests.first() }
            delivery.deliver("second")
            secondReceived.await()
            assertEquals("com.example.calendar", delivery.lastTargetAppName())
        }

    @Test
    fun `target app name is null when no provider is wired`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = TranscriptDelivery(autoInsertEnabledProvider = { true })
            val received = async { delivery.autoInsertRequests.first() }
            delivery.deliver("x")
            received.await()
            assertNull(delivery.lastTargetAppName())
        }
}
