package com.whisperboard.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the find-node + perform-action decision in [AccessibilityWriter].
 *
 * The brief calls out three scenarios that *must* be exercised under JVM
 * tests because they govern whether the user's words land in the focused
 * field or fall back to the clipboard:
 *
 * - **Editable field present** -> [WriteResult.Inserted] with method
 *   [InsertMethod.SetText] preferred over [InsertMethod.Paste].
 * - **No editable field** -> [WriteResult.FellBackToClipboard] with reason
 *   [FallbackReason.NoEditableTarget].
 * - **Node refuses both actions** -> [WriteResult.FellBackToClipboard] with
 *   reason [FallbackReason.ActionRefused].
 *
 * Stub nodes ([StubEditableNode]) drive the test so the writer can be
 * exercised without an `AccessibilityNodeInfo`.
 */
class AccessibilityWriterTest {

    @Test
    fun `editable field present prefers ACTION_SET_TEXT and returns Inserted`() {
        val node = StubEditableNode(
            supportedActions = setOf(EditableAction.SetText, EditableAction.Paste),
            setTextSucceeds = true,
            pasteSucceeds = true,
        )
        val writer = AccessibilityWriter { node }

        val result = writer.write("Hello, world.")

        assertEquals(WriteResult.Inserted(InsertMethod.SetText), result)
        assertEquals(
            "ACTION_SET_TEXT must be the preferred action",
            "Hello, world.",
            node.lastSetTextValue,
        )
        assertEquals(
            "ACTION_PASTE must not be invoked when SET_TEXT succeeds",
            0,
            node.pasteCalls,
        )
    }

    @Test
    fun `node only supporting paste falls back to ACTION_PASTE`() {
        val node = StubEditableNode(
            supportedActions = setOf(EditableAction.Paste),
            setTextSucceeds = false,
            pasteSucceeds = true,
        )
        val writer = AccessibilityWriter { node }

        val result = writer.write("Pasted")

        assertEquals(WriteResult.Inserted(InsertMethod.Paste), result)
        assertEquals(1, node.pasteCalls)
    }

    @Test
    fun `set_text refusal falls through to paste when paste is supported`() {
        val node = StubEditableNode(
            supportedActions = setOf(EditableAction.SetText, EditableAction.Paste),
            setTextSucceeds = false,
            pasteSucceeds = true,
        )
        val writer = AccessibilityWriter { node }

        val result = writer.write("Words")

        assertEquals(
            "When ACTION_SET_TEXT is advertised but returns false, the writer must " +
                "try ACTION_PASTE before giving up",
            WriteResult.Inserted(InsertMethod.Paste),
            result,
        )
    }

    @Test
    fun `no editable field returns FellBackToClipboard with NoEditableTarget`() {
        val writer = AccessibilityWriter { null }

        val result = writer.write("anything")

        assertEquals(
            WriteResult.FellBackToClipboard(FallbackReason.NoEditableTarget),
            result,
        )
    }

    @Test
    fun `node refuses both actions returns FellBackToClipboard with ActionRefused`() {
        val node = StubEditableNode(
            supportedActions = setOf(EditableAction.SetText, EditableAction.Paste),
            setTextSucceeds = false,
            pasteSucceeds = false,
        )
        val writer = AccessibilityWriter { node }

        val result = writer.write("Refused")

        assertEquals(
            WriteResult.FellBackToClipboard(FallbackReason.ActionRefused),
            result,
        )
        assertEquals(
            "Both actions must have been attempted before falling back",
            "Refused",
            node.lastSetTextValue,
        )
        assertEquals(1, node.pasteCalls)
    }

    @Test
    fun `node advertising no actions falls back without attempting either`() {
        val node = StubEditableNode(
            supportedActions = emptySet(),
            setTextSucceeds = true,
            pasteSucceeds = true,
        )
        val writer = AccessibilityWriter { node }

        val result = writer.write("None advertised")

        assertEquals(
            WriteResult.FellBackToClipboard(FallbackReason.ActionRefused),
            result,
        )
        assertEquals(
            "performSetText must not be invoked when SetText is unsupported",
            null,
            node.lastSetTextValue,
        )
        assertEquals(
            "performPaste must not be invoked when Paste is unsupported",
            0,
            node.pasteCalls,
        )
    }

    @Test
    fun `provider is consulted on each write call`() {
        var available = true
        val node = StubEditableNode(
            supportedActions = setOf(EditableAction.SetText),
            setTextSucceeds = true,
            pasteSucceeds = false,
        )
        val writer = AccessibilityWriter {
            if (available) node else null
        }

        val first = writer.write("first")
        assertTrue(first is WriteResult.Inserted)

        // Lose focus between calls — common case when the user dismisses
        // the focused app between dictation and result.
        available = false
        val second = writer.write("second")
        assertEquals(
            WriteResult.FellBackToClipboard(FallbackReason.NoEditableTarget),
            second,
        )
    }
}

/**
 * Test stub for [EditableNode]. Records every action invocation and lets
 * the test author choose which actions are supported and which return
 * `true` from `performAction`.
 */
private class StubEditableNode(
    private val supportedActions: Set<EditableAction>,
    private val setTextSucceeds: Boolean,
    private val pasteSucceeds: Boolean,
) : EditableNode {
    var lastSetTextValue: String? = null
        private set
    var pasteCalls: Int = 0
        private set

    override fun supportsAction(action: EditableAction): Boolean =
        action in supportedActions

    override fun performSetText(text: String): Boolean {
        lastSetTextValue = text
        return setTextSucceeds
    }

    override fun performPaste(): Boolean {
        pasteCalls += 1
        return pasteSucceeds
    }
}
