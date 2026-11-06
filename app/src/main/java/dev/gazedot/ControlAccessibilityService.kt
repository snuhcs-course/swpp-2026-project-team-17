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
    private var busy=false
    private val handler=Handler(Looper.getMainLooper())
    private var easyPanel: EasyControlPanel? = null
    override fun onServiceConnected() {
        instance = this
        easyPanel?.close()
        easyPanel = EasyControlPanel(this)
        syncEasyPanelPreference()
    }
    fun setEasyPanelEnabled(enabled: Boolean) { EasyControlSettings.setEnabled(this, enabled) }
    internal fun syncEasyPanelPreference() {
        easyPanel?.setEnabled(EasyControlSettings.isEnabled(this) && TrackerState.running)
    }
    internal fun observeForGazeAssistance(sample: GazeSample, eye: EyeState) {
        easyPanel?.observe(sample, eye)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) easyPanel?.onWindowEvent(event)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            TrackerState.cancelActions?.invoke()
        } else if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            TrackerState.cancelPendingClick?.invoke()
        }
    }
    /** Local identity only: labels/text are neither read nor sent to the cloud. */
    @Suppress("DEPRECATION")
    fun dwellTargetKey(point: Pair<Double, Double>): String? {
        if (!allowed()) return null
        val root = rootInActiveWindow ?: return null
        val bounds = DisplaySpace.bounds(this)
        val x = (point.first * bounds.width()).toInt()
        val y = (point.second * bounds.height()).toInt()
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
    override fun onInterrupt() { easyPanel?.close(); TrackerState.controls=false; TrackerState.cancelActions?.invoke() }
    override fun onDestroy() {
        easyPanel?.close()
        if(instance===this) instance=null
        TrackerState.controls=false; TrackerState.cancelActions?.invoke()
        handler.removeCallbacksAndMessages(null); super.onDestroy()
    }
    private fun allowed()=TrackerState.running && TrackerState.controls && TrackerState.interactionAllowed &&
        !TrackerState.collecting && !TrackerState.easyPanelBlocking
    fun tap(x: Float, y: Float): Boolean {
        if(!allowed() || busy) return false
        return gesture(Path().apply { moveTo(x,y) },80)
    }
    /** Prefer the app's directional action; swipes are a fallback inside that scroll area. */
    @Suppress("DEPRECATION")
    fun scroll(direction: ScrollDirection, width: Int, height: Int,
        gaze: Pair<Double, Double>? = null): Boolean {
        if (!allowed() || busy || width <= 0 || height <= 0) return false
        val point = gaze?.takeIf { it.first.isFinite() && it.second.isFinite() }
            ?.let { it.first.coerceIn(0.0, 1.0) * width to it.second.coerceIn(0.0, 1.0) * height }
            ?: (width / 2.0 to height / 2.0)
        val root = rootInActiveWindow
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val targets = mutableListOf<ScrollTarget>()
        val fallbackTargets = mutableListOf<ScrollTarget>()
        val screen = android.graphics.Rect(0, 0, width, height)
        var visits = 0
        fun command(d: ScrollDirection) = when (d) {
            ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++visits > 350 || depth > 20 || !node.isVisibleToUser || node.isPassword) return
            val rect = android.graphics.Rect().also(node::getBoundsInScreen)
            if (node.isEnabled && rect.intersect(screen) && rect.width() > 0 && rect.height() > 0) {
                val actions = node.actionList.map { it.id }.toSet()
                val directions = ScrollDirection.entries.filter { command(it) in actions }.toSet()
                if (node.isScrollable || directions.isNotEmpty()) {
                    val area = ScrollArea(rect.left, rect.top, rect.right, rect.bottom)
                    val name = node.className?.toString().orEmpty()
                    val horizontal = directions.any { it.dx != 0 } || name.contains("HorizontalScrollView") ||
                        name.contains("ViewPager")
                    val vertical = directions.any { it.dy != 0 } || (!horizontal && node.isScrollable)
                    val fallback = ScrollDirection.entries.filter {
                        if (it.dx != 0) horizontal else vertical
                    }.toSet()
                    nodes += AccessibilityNodeInfo.obtain(node)
                    targets += ScrollTarget(area, directions)
                    fallbackTargets += ScrollTarget(area, fallback)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        try {
            if (root != null) visit(root, 0)
            val exact = ScrollTargeting.choose(targets, direction, point)
            if (exact != null && nodes[exact].performAction(command(direction))) return true
            val fallback = exact ?: ScrollTargeting.choose(fallbackTargets, direction, point)
            val area = fallback?.let { targets[it].area } ?: ScrollArea(0, 0, width, height)
            val (from, to) = ScrollTargeting.swipe(direction, area)
            return gesture(Path().apply {
                moveTo(from.first.toFloat(), from.second.toFloat())
                lineTo(to.first.toFloat(), to.second.toFloat())
            }, ScrollSettings.DURATION_MS)
        } finally {
            nodes.forEach { it.recycle() }
            root?.recycle()
        }
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
