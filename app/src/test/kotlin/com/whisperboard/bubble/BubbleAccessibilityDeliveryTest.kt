package com.whisperboard.bubble

import com.whisperboard.accessibility.FallbackReason
import com.whisperboard.accessibility.InsertMethod
import com.whisperboard.accessibility.WriteResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the bubble's in-place insertion delivery routing. The contract:
 *
 * - Inserted [WriteResult] -> [DeliveryOutcome.Inserted], the text is
 *   staged on [BubbleAccessibilityDelivery.insertedText] and an event
 *   fires on [BubbleAccessibilityDelivery.insertions].
 * - FellBackToClipboard [WriteResult] -> [DeliveryOutcome.FellBack], the
 *   reason is exposed on [BubbleAccessibilityDelivery.fallbacks] so the
 *   caller can pick a reason-specific toast and route through the
 *   standalone-mode auto-copy delivery. Inserted-text staging stays empty.
 * - Provider returning false (accessibility revoked between transcription
 *   start and delivery) collapses any result into FellBack —
 *   defensive-programming check for the toggle race.
 * - Empty input is treated as FellBack(NoEditableTarget) without staging
 *   or emitting events; mirrors the clipboard delivery's empty-input shape.
 *
 * Per ADR-0001 and the brief, accessibility is a *pure upgrade*:
 * delivery never silently swallows a transcript — fallback always returns
 * a routable outcome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleAccessibilityDeliveryTest {

    @Test
    fun `Inserted result stages text and emits insertion event`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleAccessibilityDelivery(
                accessibilityEnabledProvider = { true },
            )

            val received = async { delivery.insertions.first() }

            val outcome = delivery.deliver(
                "Polished words.",
                WriteResult.Inserted(InsertMethod.SetText),
            )

            assertEquals(
                DeliveryOutcome.Inserted(InsertMethod.SetText),
                outcome,
            )
            assertEquals(
                InsertedEvent("Polished words.", InsertMethod.SetText),
                received.await(),
            )
            assertEquals("Polished words.", delivery.insertedText.value)
        }

    @Test
    fun `FellBackToClipboard result returns FellBack and emits the reason`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleAccessibilityDelivery(
                accessibilityEnabledProvider = { true },
            )

            val received = async { delivery.fallbacks.first() }

            val outcome = delivery.deliver(
                "Polished words.",
                WriteResult.FellBackToClipboard(FallbackReason.NoEditableTarget),
            )

            assertEquals(
                DeliveryOutcome.FellBack(FallbackReason.NoEditableTarget),
                outcome,
            )
            assertEquals(FallbackReason.NoEditableTarget, received.await())
            assertEquals(
                "Inserted-text staging stays empty when the writer fell back — " +
                    "the standalone-mode result UI takes over",
                "",
                delivery.insertedText.value,
            )
        }

    @Test
    fun `accessibility provider returning false yields FellBack regardless of result`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleAccessibilityDelivery(
                accessibilityEnabledProvider = { false },
            )

            val outcome = delivery.deliver(
                "Polished words.",
                WriteResult.Inserted(InsertMethod.SetText),
            )

            assertTrue(
                "Provider toggling off between transcription start and delivery " +
                    "must collapse any incoming result into a fallback",
                outcome is DeliveryOutcome.FellBack,
            )
            assertEquals("", delivery.insertedText.value)
        }

    @Test
    fun `empty input returns FellBack without staging or emitting`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleAccessibilityDelivery(
                accessibilityEnabledProvider = { true },
            )
            // Stage something first so we can verify clearStaged behaviour.
            delivery.deliver("staged", WriteResult.Inserted(InsertMethod.SetText))
            assertEquals("staged", delivery.insertedText.value)

            val collected = async {
                withTimeoutOrNull(timeMillis = 50) { delivery.insertions.first() }
            }

            val outcome = delivery.deliver("", WriteResult.Inserted(InsertMethod.Paste))

            assertTrue(outcome is DeliveryOutcome.FellBack)
            assertEquals("", delivery.insertedText.value)
            assertNull(
                "Empty input must not emit an insertion event",
                collected.await(),
            )
        }

    @Test
    fun `clearStaged empties insertedText`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleAccessibilityDelivery(
                accessibilityEnabledProvider = { true },
            )
            delivery.deliver("staged", WriteResult.Inserted(InsertMethod.SetText))
            assertEquals("staged", delivery.insertedText.value)

            delivery.clearStaged()
            assertEquals("", delivery.insertedText.value)
        }

    @Test
    fun `Inserted exposes the InsertMethod chosen by the writer`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleAccessibilityDelivery(
                accessibilityEnabledProvider = { true },
            )

            val outcome = delivery.deliver(
                "via paste",
                WriteResult.Inserted(InsertMethod.Paste),
            )

            assertEquals(DeliveryOutcome.Inserted(InsertMethod.Paste), outcome)
        }

    @Test
    fun `FellBack exposes the writer's reason for the fallback`() =
        runTest(UnconfinedTestDispatcher()) {
            val delivery = BubbleAccessibilityDelivery(
                accessibilityEnabledProvider = { true },
            )

            val refused = delivery.deliver(
                "refused",
                WriteResult.FellBackToClipboard(FallbackReason.ActionRefused),
            )
            assertEquals(
                DeliveryOutcome.FellBack(FallbackReason.ActionRefused),
                refused,
            )

            val noTarget = delivery.deliver(
                "no-target",
                WriteResult.FellBackToClipboard(FallbackReason.NoEditableTarget),
            )
            assertEquals(
                DeliveryOutcome.FellBack(FallbackReason.NoEditableTarget),
                noTarget,
            )
        }
}
