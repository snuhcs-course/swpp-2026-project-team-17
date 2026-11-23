package dev.gazedot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ControlAccessibilityService : AccessibilityService() {
    private val applicationWindow = ApplicationWindowTracker()
    private var busy=false
    private val handler=Handler(Looper.getMainLooper())
    private var writing: WritingController? = null
    override fun onServiceConnected() {
        instance=this
        writing?.close()
        writing = WritingController(this)
        busy = false
        applicationWindow.changed(focusedApplicationWindow())
        TrackerState.accessibilityConnected()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        writing?.onEvent(event)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Scrolling can emit WINDOW_STATE_CHANGED without leaving the app/window.
            // Cancel stale clicks in that case, but keep the user's wink-selected mode.
            if (applicationWindow.changed(focusedApplicationWindow())) TrackerState.cancelActions?.invoke()
            else TrackerState.cancelPendingClick?.invoke()
        } else if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            TrackerState.cancelPendingClick?.invoke()
        }
    }
    @Suppress("DEPRECATION")
    private fun focusedApplicationWindow(): ApplicationWindowKey? {
        val visibleWindows = windows
        return try {
            val window = visibleWindows.firstOrNull {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            } ?: return null
            val root = window.root ?: return null
            try { root.packageName?.toString()?.let { ApplicationWindowKey(it, window.id) } }
            finally { root.recycle() }
        } finally { visibleWindows.forEach { it.recycle() } }
    }

    /** Local identity only: labels/text are neither read nor sent to the cloud. */
    @Suppress("DEPRECATION")
    fun dwellTargetKey(point: Pair<Double, Double>): String? {
        if (!allowed()) return null
        val bounds = DisplaySpace.bounds(this)
        val x = (point.first * bounds.width()).toInt()
        val y = (point.second * bounds.height()).toInt()
        // The keyboard is a separate accessibility window, not a child of the message app.
        val visibleWindows = windows
        val keyboardRoot = try {
            visibleWindows.firstOrNull { window ->
                window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    android.graphics.Rect().let { window.getBoundsInScreen(it); it.contains(x, y) }
            }?.root
        } finally { visibleWindows.forEach { it.recycle() } }
        val root = keyboardRoot ?: rootInActiveWindow ?: return null
        var bestArea = Long.MAX_VALUE
        var key: String? = null
        var visits = 0
        val screen = "${root.packageName}|${root.windowId}"
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++visits > 350 || depth > 20 || !node.isVisibleToUser) return
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (node.isEnabled && (node.isClickable || node.isEditable) && rect.contains(x, y)) {
                val area = rect.width().toLong() * rect.height()
                if (area < bestArea) {
                    bestArea = area
                    key = "$screen|${node.viewIdResourceName}|${node.className}|$rect"
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        return try { visit(root, 0); key } finally { root.recycle() }
    }
    private fun reportInterrupted() {
        TrackerState.accessibilityDisconnected()
    }
    override fun onInterrupt() { writing?.hide(); reportInterrupted() }
    override fun onDestroy() {
        writing?.close(); writing = null
        if (instance === this) {
            instance = null
            reportInterrupted()
        }
        handler.removeCallbacksAndMessages(null); super.onDestroy()
    }
    private fun allowed()=TrackerState.externalActionsAllowed()
    fun tap(x: Float, y: Float): Boolean {
        if(!allowed() || busy) return false
        return gesture(Path().apply { moveTo(x,y) },80)
    }
    fun scroll(direction: ScrollDirection, width: Int, height: Int): Boolean {
        if(!allowed() || busy) return false
        val swipe = ScrollSettings.swipe(direction, width, height)
        return gesture(Path().apply {
            moveTo(swipe.from.first.toFloat(), swipe.from.second.toFloat())
            lineTo(swipe.to.first.toFloat(), swipe.to.second.toFloat())
        }, swipe.durationMs)
    }
    private fun gesture(path: Path, duration: Long): Boolean {
        val description=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,duration)).build()
        busy=true
        val accepted=runCatching { dispatchGesture(description,object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { busy=false }
            override fun onCancelled(g: GestureDescription?) { busy=false; TrackerState.actionStatus="시스템에서 동작을 취소했습니다" }
        },handler) }.getOrDefault(false)
        if(!accepted) busy=false
        return accepted
    }
    @Suppress("DEPRECATION")
    fun focusedEditor(): AccessibilityNodeInfo? {
        if(!allowed()) return null
        val root=rootInActiveWindow ?: return null
        val node=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        root.recycle()
        if(node!=null && node.isEditable && node.isEnabled && node.isVisibleToUser && !node.isPassword) return node
        node?.recycle(); return null
    }
    @Suppress("DEPRECATION")
    fun insert(target: AccessibilityNodeInfo, original: String, start: Int, end: Int, spoken: String): Boolean {
        if(!allowed()) return false
        val current=focusedEditor() ?: return false
        try {
            if(current!=target || !target.refresh() || target.text?.toString().orEmpty()!=original ||
                (target.textSelectionStart>=0 && target.textSelectionStart!=start) ||
                (target.textSelectionEnd>=0 && target.textSelectionEnd!=end)) return false
            val a=start.coerceIn(0,original.length); val b=end.coerceIn(a,original.length)
            val newText=original.substring(0,a)+spoken+original.substring(b)
            val ok=target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,newText)
            })
            if(ok) target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,a+spoken.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,a+spoken.length)
            })
            return ok
        } finally { current.recycle() }
    }
    companion object { var instance: ControlAccessibilityService?=null; private set }
}
