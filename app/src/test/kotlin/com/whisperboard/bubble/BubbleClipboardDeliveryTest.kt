package com.whisperboard.bubble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the bubble's auto-copy decision. The contract:
 *
 * - Auto-copy ON (default): finished transcripts are emitted on
 *   [BubbleClipboardDelivery.autoCopyRequests] AND staged in
 *   [BubbleClipboardDelivery.transcribedText] for display. Auto-copy is in
 *   addition to display, not in lieu of it.
 * - Auto-copy OFF: finished transcripts are staged for display only — no
 *   clipboard event fires.
 * - Empty input is a no-op past clearing any staged preview.
 * - Each call to [BubbleClipboardDelivery.deliver] reads the current value
 *   of the provider so toggling the setting affects the next utterance.
 *
 * Mirrors the test shape of `TranscriptDeliveryTest` so the two
 * surface-side delivery paths can be reasoned about side by side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleClipboardDeliveryTest {

    @Test
    fun `auto-copy on emits to autoCopyRequests and also stages for display`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleClipboardDelivery(autoCopyEnabledProvider = { true })

            val received = async { delivery.autoCopyRequests.first() }

            val emitted = delivery.deliver("Polished words.")

            assertTrue("deliver() must report it emitted when auto-copy is on", emitted)
            assertEquals("Polished words.", received.await())
            // Staging stays populated so the result view can render the text.
            assertEquals("Polished words.", delivery.transcribedText.value)
        }

    @Test
    fun `auto-copy off stages the transcript and does not emit`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleClipboardDelivery(autoCopyEnabledProvider = { false })

            val collected = async {
                withTimeoutOrNull(timeMillis = 50) { delivery.autoCopyRequests.first() }
            }

            val emitted = delivery.deliver("Manual copy please.")

            assertFalse("deliver() must report no emission when auto-copy is off", emitted)
            assertEquals("Manual copy please.", delivery.transcribedText.value)
            assertNull(
                "auto-copy off must not emit; got ${collected.await()}",
                collected.await(),
            )
        }

    @Test
    fun `empty input is a no-op and clears any staged transcript`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleClipboardDelivery(autoCopyEnabledProvider = { true })

            val first = async { delivery.autoCopyRequests.first() }
            delivery.deliver("staged")
            assertEquals("staged", delivery.transcribedText.value)
            assertEquals("staged", first.await())

            val emitted = delivery.deliver("")
            assertFalse(emitted)
            assertEquals("", delivery.transcribedText.value)
        }

    @Test
    fun `provider is read on each deliver call`() =
        runTest(UnconfinedTestDispatcher()) {
            var enabled = true
            val delivery = BubbleClipboardDelivery(autoCopyEnabledProvider = { enabled })

            val firstEvent = async { delivery.autoCopyRequests.first() }
            delivery.deliver("first")
            assertEquals("first", firstEvent.await())

            // Toggle off; next utterance must stage but not emit.
            enabled = false
            val emitted = delivery.deliver("second")
            assertFalse(emitted)
            assertEquals("second", delivery.transcribedText.value)
        }

    @Test
    fun `clearStaged empties the preview without changing auto-copy provider state`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleClipboardDelivery(autoCopyEnabledProvider = { false })
            delivery.deliver("staged for display")
            assertEquals("staged for display", delivery.transcribedText.value)

            delivery.clearStaged()
            assertEquals("", delivery.transcribedText.value)

            // Next deliver still respects the off provider.
            delivery.deliver("another")
            assertEquals("another", delivery.transcribedText.value)
        }

    @Test
    fun `auto-copy on emits each delivered transcript in order`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleClipboardDelivery(autoCopyEnabledProvider = { true })

            val collected = async { delivery.autoCopyRequests.take(3).toList() }

            delivery.deliver("alpha")
            delivery.deliver("beta")
            delivery.deliver("gamma")

            assertEquals(listOf("alpha", "beta", "gamma"), collected.await())
            assertEquals(
                "Last staged value reflects the most recent transcript",
                "gamma",
                delivery.transcribedText.value,
            )
        }
}
