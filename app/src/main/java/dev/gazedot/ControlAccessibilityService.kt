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
    private var cloudCacheAt=0L
    private var cloudCache: UiSnapshot?=null
    private var busy=false
    private val handler=Handler(Looper.getMainLooper())
    override fun onServiceConnected() { instance=this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        cloudCache=null; cloudCacheAt=0
        if(event?.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            TrackerState.cancelActions?.invoke()
        } else if(CloudSession.contextEnabled && TrackerState.interactionAllowed &&
            (event?.eventType==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || event?.eventType==AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
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
    /** Labels/bounds only; never export password nodes or editable field contents. */
    @Suppress("DEPRECATION")
    fun cloudSnapshot(): UiSnapshot? {
        if(!CloudSession.contextEnabled || !TrackerState.running || TrackerState.collecting) return null
        val now=android.os.SystemClock.uptimeMillis()
        if(now-cloudCacheAt<250) return cloudCache
        val root=rootInActiveWindow ?: return null
        val bounds=DisplaySpace.bounds(this)
        val targets=mutableListOf<UiTarget>()
        var visits=0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if(++visits>350 || depth>20 || targets.size>=40 || node.isPassword || !node.isVisibleToUser) return
            if(node.isEnabled && (node.isClickable || node.isEditable)) {
                val r=android.graphics.Rect(); node.getBoundsInScreen(r)
                if(r.intersect(bounds) && r.width()>0 && r.height()>0) {
                    val label=if(node.isEditable) "입력칸 (내용 제외)" else
                        (node.contentDescription ?: node.text ?: node.className ?: "버튼").toString().take(100)
                    targets+=UiTarget("t${targets.size}",label,r.left.toDouble()/bounds.width(),r.top.toDouble()/bounds.height(),
                        r.right.toDouble()/bounds.width(),r.bottom.toDouble()/bounds.height(),node.isEditable)
                }
            }
            for(i in 0 until node.childCount) {
                val child=node.getChild(i) ?: continue
                try { visit(child,depth+1) } finally { child.recycle() }
            }
        }
        return try {
            val pkg=root.packageName?.toString().orEmpty()
            visit(root,0)
            val canonical=pkg+"|"+root.windowId+"|"+targets.joinToString { it.json().toString() }
            val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) }
            UiSnapshot(pkg,fingerprint,targets).also { cloudCache=it; cloudCacheAt=now }
        } finally { root.recycle() }
    }
    override fun onInterrupt() { TrackerState.controls=false; TrackerState.cancelActions?.invoke() }
    override fun onDestroy() {
        if(instance===this) instance=null
        TrackerState.controls=false; TrackerState.cancelActions?.invoke()
        handler.removeCallbacksAndMessages(null); super.onDestroy()
    }
    private fun allowed()=TrackerState.running && TrackerState.controls && TrackerState.interactionAllowed && !TrackerState.collecting
    fun tap(x: Float, y: Float): Boolean {
        if(!allowed() || busy) return false
        return gesture(Path().apply { moveTo(x,y) },80)
    }
    fun scroll(direction: ScrollDirection, width: Int, height: Int): Boolean {
        if(!allowed() || busy) return false
        val (from, to) = ScrollSettings.swipe(direction, width, height)
        return gesture(Path().apply {
            moveTo(from.first.toFloat(), from.second.toFloat())
            lineTo(to.first.toFloat(), to.second.toFloat())
        }, ScrollSettings.DURATION_MS)
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
