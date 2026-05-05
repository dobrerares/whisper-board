package com.whisperboard.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that turns the bubble's standalone-mode auto-copy into
 * **in-place insertion**: when granted, the polished transcript is written
 * directly into the focused editable field instead of (or in addition to —
 * see fallback below) being copied to the clipboard.
 *
 * Per ADR-0001, accessibility is a *pure upgrade* — the bubble must continue
 * to work in standalone mode if the user never grants this permission. The
 * IME does not use this service at all (it has `InputConnection`).
 *
 * Wiring:
 *
 * - [BubbleOverlayService] discovers a running accessibility service via
 *   [isEnabled] and asks [INSTANCE] to write text via [tryWrite].
 * - [tryWrite] delegates to [AccessibilityWriter] — the deep, JVM-tested
 *   module — using `findFocus(FOCUS_INPUT)` as the node provider and a
 *   thin wrapper around [AccessibilityNodeInfo] for the action calls.
 * - The result ([WriteResult]) flows back to the caller, which decides
 *   whether to fall back to clipboard auto-copy.
 *
 * No event handling beyond keeping the service alive — the focus tracking
 * happens lazily inside [tryWrite] each time text needs to be written. This
 * is intentional: subscribing to every focus event in the accessibility
 * tree is expensive and out of scope for v1.
 */
class WhisperBoardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityService"

        /**
         * The currently bound service instance. `null` when the user has not
         * granted the accessibility service. The bubble overlay reads this
         * to decide whether to attempt in-place insertion.
         *
         * Kept as a static reference because the bubble's overlay service
         * runs in the same process and the standard Android accessibility
         * binding is opaque (no client-side handle).
         */
        @Volatile
        var instance: WhisperBoardAccessibilityService? = null
            private set

        /**
         * Whether the user has enabled the Whisper Board accessibility
         * service in system settings. Read both the bound-instance flag and
         * the system-secure setting so callers can check before any IPC.
         *
         * The bound-instance check is authoritative when present; the
         * settings string is the fallback for the moment between toggle-on
         * in system settings and the service binding.
         */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = "${context.packageName}/${WhisperBoardAccessibilityService::class.java.name}"
            // The system stores enabled services as a colon-separated list
            // of "package/class" entries. A simple substring check is
            // sufficient because component names cannot legally contain ':'.
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Connected — in-place insertion is now available")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "Unbound — falling back to standalone mode")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Required override; the bubble does not subscribe to focus events from
     * the accessibility tree. Focus discovery is on-demand inside
     * [tryWrite] via `findFocus(FOCUS_INPUT)`.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** Required override; nothing to undo on interrupt. */
    override fun onInterrupt() = Unit

    /**
     * Attempt to write [text] into the currently focused editable field. If
     * the result is [WriteResult.Inserted] with [InsertMethod.Paste] the
     * caller is responsible for ensuring [text] is already staged on the
     * system clipboard before calling — `ACTION_PASTE` reads from there.
     *
     * The convenience pre-stage call below ensures both action paths
     * succeed without forcing the bubble overlay to know which one was
     * picked.
     */
    fun tryWrite(text: String): WriteResult {
        if (text.isEmpty()) {
            return WriteResult.FellBackToClipboard(FallbackReason.NoEditableTarget)
        }

        // Stage the text on the clipboard up-front so ACTION_PASTE has a
        // value to read. The bubble overlay also wants the text on the
        // clipboard if we end up falling back, so this is a no-op cost.
        stageOnClipboard(text)

        val provider = EditableNodeProvider {
            val node: AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            node?.let { AccessibilityNodeAdapter(it) }
        }
        val writer = AccessibilityWriter(provider)
        return writer.write(text)
    }

    private fun stageOnClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Whisper Board", text))
    }
}

/**
 * Bridges [EditableNode] over an [AccessibilityNodeInfo]. Lives outside the
 * deep [AccessibilityWriter] module so the writer stays JVM-testable.
 */
private class AccessibilityNodeAdapter(
    private val node: AccessibilityNodeInfo,
) : EditableNode {

    override fun supportsAction(action: EditableAction): Boolean {
        val expected = when (action) {
            EditableAction.SetText -> AccessibilityNodeInfo.ACTION_SET_TEXT
            EditableAction.Paste -> AccessibilityNodeInfo.ACTION_PASTE
        }
        // actionList exposes the AccessibilityAction objects on API 21+;
        // .id maps to the constants used by performAction.
        return node.actionList.any { it.id == expected }
    }

    override fun performSetText(text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun performPaste(): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}
