package dev.gazedot

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.security.MessageDigest

/** Explicit app adapters. Unknown IDs/screens fail closed; verify each shipped app version on device. */
internal object MessagingEditors {
    private val editors = mapOf(
        "com.kakao.talk" to setOf("message_edit_text"),
        "com.samsung.android.messaging" to setOf("message_edit_text", "embedded_text_editor", "message_text"),
        "com.google.android.apps.messaging" to setOf("compose_message_text"),
    )
    private val headers = setOf("chat_room_title", "chatroom_title", "chat_title", "title", "toolbar_title",
        "conversation_title", "conversation_title_text", "recipient_name", "recipient_text", "recipients")
    private val headerContainers = setOf("toolbar", "action_bar", "chat_room_toolbar", "toolbar_container",
        "conversation_toolbar", "top_bar", "title_bar", "chat_room_header")
    private val specificHeaders = setOf("chat_room_title", "chatroom_title", "chat_title", "conversation_title", "conversation_title_text")
    fun supports(packageName: String, id: String, password: Boolean, inputType: Int): Boolean {
        if (password || id !in editors[packageName].orEmpty().map { "$packageName:id/$it" }) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation !in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) &&
            !(inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
    @Suppress("DEPRECATION")
    fun snapshot(root: AccessibilityNodeInfo, editor: AccessibilityNodeInfo): EditorSnapshot? {
        val pkg = editor.packageName?.toString() ?: return null
        val id = editor.viewIdResourceName ?: return null
        if (!editor.isEditable || !editor.isEnabled || !editor.isVisibleToUser || !editor.isFocused ||
            !supports(pkg, id, editor.isPassword, editor.inputType)) return null
        val markers = mutableListOf<String>()
        var visits = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int, inHeader: Boolean) {
            if (++visits > 350 || depth > 20) return
            val viewId = node.viewIdResourceName.orEmpty()
            val localId = viewId.substringAfterLast('/')
            val knownHeader = inHeader || (viewId.startsWith("$pkg:id/") && localId in headerContainers)
            if (node.isVisibleToUser && !node.isEditable && viewId.startsWith("$pkg:id/") &&
                localId in headers && (knownHeader || localId in specificHeaders)) {
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { markers += "$viewId:$it" }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1, knownHeader) } finally { child.recycle() }
            }
        }
        // Only the known header metadata is read. Never read message bubble text/history.
        visit(root, 0, false)
        if (markers.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(markers.sorted().joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val result = EditorSnapshot(pkg, editor.windowId, id, digest, editor.text?.toString().orEmpty(),
            editor.textSelectionStart, editor.textSelectionEnd)
        return result.takeIf { it.validSelection }
    }
}

internal enum class WriteResult { CONFIRMED, TEXT_CONFIRMED, REJECTED, UNCERTAIN }

@Suppress("DEPRECATION")
internal class WritingTarget private constructor(
    private val service: AccessibilityService, private val editor: AccessibilityNodeInfo,
    var expected: EditorSnapshot,
) {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    var invalid = false; private set
    var pending = false; private set
    fun invalidate() { invalid = true }
    fun current(): EditorSnapshot? {
        if (closed || invalid || !editor.refresh()) return null
        val windows = service.windows
        try {
            // A foreground switch invalidates the binding even if the old app's node is still alive.
            if (windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused && it.id != expected.windowId }) return null
            val window = windows.firstOrNull { it.id == expected.windowId } ?: return null
            if (!window.isFocused) return null
            val root = window.root ?: return null
            try {
                val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
                try { if (focus != editor) return null } finally { focus.recycle() }
                return MessagingEditors.snapshot(root, editor)
            } finally { root.recycle() }
        } finally { windows.forEach { it.recycle() } }
    }
    fun unchanged() = current()?.let { expected.unchanged(it) } == true

    fun insert(addition: String, result: (WriteResult) -> Unit) {
        if (pending || !unchanged()) { result(WriteResult.REJECTED); return }
        val plan = expected.plan(addition) ?: run { result(WriteResult.REJECTED); return }
        pending = true
        val at = maxOf(expected.start, expected.end)
        if (expected.start != at || expected.end != at) {
            setSelection(at)
            val collapsed = current()
            if (collapsed == null || collapsed.text != expected.text || collapsed.start != at || collapsed.end != at) {
                pending = false; invalid = true; result(WriteResult.REJECTED); return
            }
        }
        // Persist the draft as uncertain before calling this method; a crash may follow IPC success.
        runCatching {
            editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.text)
            })
        }
        val deadline = SystemClock.uptimeMillis() + 1_500
        fun checkResult() {
            if (closed) return
            val actual = current()
            if (actual != null && plan.textConfirmed(actual)) {
                setSelection(plan.caret)
                val final = current()
                pending = false
                if (final != null && plan.confirmed(final)) {
                    expected = final; result(WriteResult.CONFIRMED)
                } else {
                    invalid = true; result(WriteResult.TEXT_CONFIRMED)
                }
            } else if (SystemClock.uptimeMillis() >= deadline) {
                // Even a false return from ACTION_SET_TEXT is not proof that the IPC did no work.
                pending = false; invalid = true; result(WriteResult.UNCERTAIN)
            } else main.postDelayed({ checkResult() }, 75)
        }
        main.post { checkResult() }
    }
    private fun setSelection(at: Int) = runCatching {
        editor.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, at)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, at)
        })
    }
    fun close() { closed = true; main.removeCallbacksAndMessages(null); editor.recycle() }

    companion object {
        fun capture(service: AccessibilityService): WritingTarget? {
            val root = service.rootInActiveWindow ?: return null
            try {
                val editor = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
                val snapshot = MessagingEditors.snapshot(root, editor)
                if (snapshot == null) { editor.recycle(); return null }
                return WritingTarget(service, editor, snapshot)
            } finally { root.recycle() }
        }
    }
}
