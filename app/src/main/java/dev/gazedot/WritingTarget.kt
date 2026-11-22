package dev.gazedot

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Bind the user-selected editable node, without app-specific field IDs or room titles. */
internal object MessagingEditors {
    private val packages = setOf(
        "com.kakao.talk", "com.samsung.android.messaging", "com.google.android.apps.messaging",
    )
    fun supportsPackage(packageName: String) = packageName in packages

    fun supports(packageName: String, password: Boolean, inputType: Int): Boolean {
        if (!supportsPackage(packageName) || password) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        return !(inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) &&
            !(inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    fun snapshot(editor: AccessibilityNodeInfo, onFailure: (String) -> Unit = {}): EditorSnapshot? {
        fun reject(message: String): EditorSnapshot? { onFailure(message); return null }
        val pkg = editor.packageName?.toString()
            ?: return reject("입력칸의 앱 정보를 읽지 못했어요.")
        if (!editor.isEditable || !editor.isEnabled || !editor.isVisibleToUser)
            return reject("현재 입력칸을 사용할 수 없어요.")
        if (!editor.isFocused) return reject("메시지 입력칸을 먼저 눌러 주세요.")
        if (!supports(pkg, editor.isPassword, editor.inputType))
            return reject("이 입력칸에서는 작성할 수 없어요.")
        // The key is for snapshot comparisons only. current() also compares the actual nodes.
        // Some editors expose their placeholder through text when empty.
        val text = if (editor.isShowingHintText) "" else editor.text?.toString().orEmpty()
        return EditorSnapshot(pkg, editor.windowId, editor.hashCode(), text,
            editor.textSelectionStart, editor.textSelectionEnd).withEmptySelectionFallback()
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
                return MessagingEditors.snapshot(editor)
            } finally { root.recycle() }
        } finally { windows.forEach { it.recycle() } }
    }
    fun unchanged() = current()?.let { expected.unchanged(it) } == true

    fun insert(addition: String, result: (WriteResult) -> Unit) {
        if (pending || !unchanged()) { result(WriteResult.REJECTED); return }
        val plan = expected.plan(addition) ?: run { result(WriteResult.REJECTED); return }
        applyPlan(plan, result)
    }

    fun applyCandidate(candidate: WritingCandidate, result: (WriteResult) -> Unit) {
        if (pending || !unchanged()) { result(WriteResult.REJECTED); return }
        val plan = expected.candidatePlan(candidate) ?: run { result(WriteResult.REJECTED); return }
        applyPlan(plan, result)
    }

    /** Include the compact composer container, not only the text's inner bounds. */
    fun protectedBounds(keyboardTop: Int): android.graphics.Rect {
        val bounds = android.graphics.Rect().also { editor.getBoundsInScreen(it) }
        val padding = RecoveryUi.dp(service, 64)
        var ancestor = editor.parent
        repeat(4) {
            val node = ancestor ?: return bounds
            val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            val next = node.parent
            node.recycle()
            if (rect.contains(bounds) && rect.top >= bounds.top - padding &&
                rect.bottom <= keyboardTop && rect.height() <= bounds.height() + padding) bounds.set(rect)
            ancestor = next
        }
        ancestor?.recycle()
        return bounds
    }

    private fun applyPlan(plan: InsertionPlan, result: (WriteResult) -> Unit) {
        pending = true
        val at = maxOf(expected.start, expected.end)
        if (expected.start != at || expected.end != at) {
            setSelection(at)
            val collapsed = current()
            if (collapsed == null || collapsed.text != expected.text || collapsed.start != at || collapsed.end != at) {
                pending = false; invalid = true; result(WriteResult.REJECTED); return
            }
        }
        // Never automatically retry an unconfirmed IPC; the real editor remains visible for inspection.
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
        fun capture(service: AccessibilityService, onFailure: (String) -> Unit = {}): WritingTarget? {
            val root = service.rootInActiveWindow ?: run {
                onFailure("현재 앱 화면을 읽지 못했어요."); return null
            }
            try {
                val editor = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: run {
                    onFailure("메시지 입력칸을 먼저 눌러 주세요."); return null
                }
                val snapshot = try { MessagingEditors.snapshot(editor, onFailure) }
                    catch (error: RuntimeException) { editor.recycle(); throw error }
                if (snapshot == null) { editor.recycle(); return null }
                if (!snapshot.validSelection) {
                    editor.recycle()
                    onFailure("글자 사이를 눌러 커서를 지정해 주세요.")
                    return null
                }
                return WritingTarget(service, editor, snapshot)
            } finally { root.recycle() }
        }
    }
}
