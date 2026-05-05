package com.whisperboard.accessibility

/**
 * Writes a polished transcript into the currently focused editable field via
 * the Android accessibility tree. Pure logic module — no Android imports —
 * so the find-node + perform-action decision flow can be exercised under JVM
 * tests with stub nodes.
 *
 * The platform-side glue ([WhisperBoardAccessibilityService]) supplies an
 * implementation of [EditableNodeProvider] backed by
 * `AccessibilityService.findFocus(FOCUS_INPUT)` and a real
 * `AccessibilityNodeInfo.performAction(...)`. Tests supply a stub provider
 * built on [StubEditableNode] / [stubProviderOf].
 *
 * Decision flow, mirroring the Agent Brief:
 *
 * 1. Ask the [EditableNodeProvider] for the focused editable node.
 * 2. If none, return [WriteResult.FellBackToClipboard] with reason
 *    [FallbackReason.NoEditableTarget] — caller writes to the clipboard.
 * 3. Otherwise prefer `ACTION_SET_TEXT` (replaces field contents). If the
 *    node refuses or returns false, fall back to `ACTION_PASTE` (after
 *    staging the text on the clipboard so the paste has something to read).
 * 4. If both refuse, return [WriteResult.FellBackToClipboard] with reason
 *    [FallbackReason.ActionRefused].
 *
 * The result type is a sealed hierarchy so the bubble can branch on
 * "inserted" vs "fell back" without inspecting message strings.
 *
 * The bubble's standalone mode (clipboard auto-copy) is the *fallback*, not
 * a replacement — accessibility is a pure upgrade per ADR-0001. Whatever the
 * outcome, the user's words are preserved.
 */
class AccessibilityWriter(
    private val nodeProvider: EditableNodeProvider,
) {

    /**
     * Attempt to insert [text] into the focused editable node. Returns a
     * sealed [WriteResult] describing what happened so the caller can decide
     * whether to additionally copy to the clipboard, surface a toast, etc.
     *
     * Empty input is reported as [WriteResult.FellBackToClipboard] with
     * reason [FallbackReason.NoEditableTarget] only when the field is also
     * absent — when a field is present, an empty insert is treated as
     * [WriteResult.Inserted] (nothing to do, but no fallback either).
     */
    fun write(text: String): WriteResult {
        val node = nodeProvider.findFocusedEditableNode()
            ?: return WriteResult.FellBackToClipboard(FallbackReason.NoEditableTarget)

        if (node.supportsAction(EditableAction.SetText)) {
            if (node.performSetText(text)) {
                return WriteResult.Inserted(method = InsertMethod.SetText)
            }
        }

        if (node.supportsAction(EditableAction.Paste)) {
            // ACTION_PASTE reads from the clipboard. The caller is
            // responsible for staging [text] on the clipboard before the
            // paste action fires; we model that as a side-effect signal in
            // the result so the bubble's overlay service can do it.
            if (node.performPaste()) {
                return WriteResult.Inserted(method = InsertMethod.Paste)
            }
        }

        return WriteResult.FellBackToClipboard(FallbackReason.ActionRefused)
    }
}

/**
 * Outcome of an [AccessibilityWriter.write] call. The bubble overlay branches
 * on this:
 *
 * - [Inserted] — the focused field accepted the write. Show the "in-place
 *   insertion" confirmation in the result UI.
 * - [FellBackToClipboard] — the write could not happen against the focused
 *   field. The caller should auto-copy the text and show the standalone-mode
 *   "Copied" toast so the user's words are never lost.
 */
sealed interface WriteResult {
    /** The focused editable node accepted [InsertMethod]. */
    data class Inserted(val method: InsertMethod) : WriteResult

    /**
     * The text could not be inserted into a focused field. The caller must
     * auto-copy the transcript so the user can paste it manually.
     */
    data class FellBackToClipboard(val reason: FallbackReason) : WriteResult
}

/** Which accessibility action successfully wrote the text. */
enum class InsertMethod {
    /**
     * `AccessibilityNodeInfo.ACTION_SET_TEXT` — preferred. Replaces the
     * field's content with the supplied text.
     */
    SetText,

    /**
     * `AccessibilityNodeInfo.ACTION_PASTE` — fallback. Inserts the
     * clipboard content at the cursor; the caller must stage the text on
     * the clipboard before this action fires.
     */
    Paste,
}

/** Why the writer fell back to clipboard auto-copy. */
enum class FallbackReason {
    /** No focused editable node was discovered in the accessibility tree. */
    NoEditableTarget,

    /**
     * A focused node was discovered but it refused both `ACTION_SET_TEXT`
     * and `ACTION_PASTE`. Some webviews and custom controls do this.
     */
    ActionRefused,
}

/**
 * Source of the focused editable node. Production code wires this to
 * `AccessibilityService.findFocus(FOCUS_INPUT)`; tests pass a stub.
 */
fun interface EditableNodeProvider {
    fun findFocusedEditableNode(): EditableNode?
}

/**
 * Abstraction over an `AccessibilityNodeInfo` that's editable. Keeps the
 * writer decoupled from platform types so the JVM tests can drive it.
 */
interface EditableNode {
    /** Whether the node advertises support for [action]. */
    fun supportsAction(action: EditableAction): Boolean

    /** Execute `ACTION_SET_TEXT` with [text]. Returns whether the action succeeded. */
    fun performSetText(text: String): Boolean

    /** Execute `ACTION_PASTE`. Returns whether the action succeeded. */
    fun performPaste(): Boolean
}

/** Editable-node actions the writer cares about. */
enum class EditableAction { SetText, Paste }
