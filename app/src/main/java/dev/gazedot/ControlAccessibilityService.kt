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
    private var selectionPanel: LearnedSelectionPanel?=null
    val choosing get()=selectionPanel!=null
    private var selectionCooldown=0L
    private var rankerState: String?=null
    private var rankerCache=PersonalTargetRanker()
    private fun personalRanker(): PersonalTargetRanker {
        val saved=getSharedPreferences("personal",0).getString("ranker","").orEmpty()
        if(saved!=rankerState) { rankerCache=PersonalTargetRanker().apply { restore(saved) }; rankerState=saved }
        return rankerCache
    }
    fun closeSelection() { selectionPanel?.close(); selectionPanel=null; selectionCooldown=android.os.SystemClock.uptimeMillis()+800 }
    fun updateSelection(eye: EyeState, at: Long, now: Long) { selectionPanel?.update(eye,at,now) }
    private fun hash(text: String)=java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Snapshot contains no password nodes or editable-field contents; only hashed IDs persist. */
    @Suppress("DEPRECATION")
    private fun choices(): ChoiceSnapshot? {
        val root=rootInActiveWindow ?: return null
        val bounds=DisplaySpace.bounds(this); val result=mutableListOf<AccessibleChoice>(); var visits=0
        val screen=hash("${root.packageName}|${root.className}")
        fun visit(node: AccessibilityNodeInfo,depth: Int) {
            if(++visits>350 || depth>20 || !node.isVisibleToUser || node.isPassword) return
            val r=android.graphics.Rect(); node.getBoundsInScreen(r)
            if(node.isClickable && node.isEnabled && r.intersect(bounds) && r.width()>0 && r.height()>0) {
                val id=hash("${node.viewIdResourceName}|${node.className}|$r")
                val label=if(node.isEditable) "입력칸" else (node.contentDescription ?: node.text ?: "버튼").toString().take(55)
                result+=AccessibleChoice(SelectionCandidate(id,r.left.toDouble()/bounds.width(),r.top.toDouble()/bounds.height(),
                    r.right.toDouble()/bounds.width(),r.bottom.toDouble()/bounds.height()),label)
            }
            for(i in 0 until node.childCount) node.getChild(i)?.let { try { visit(it,depth+1) } finally { it.recycle() } }
        }
        return try { visit(root,0); ChoiceSnapshot(screen,root.windowId,result.distinctBy { it.candidate.id }) } finally { root.recycle() }
    }
    /** Returns true when the action is handled/withheld by the confirmation flow. */
    fun offerSelection(point: Pair<Double,Double>): Boolean {
        if(!getSharedPreferences("personal",0).getBoolean("enabled",true)) return false
        if(choosing || android.os.SystemClock.uptimeMillis()<selectionCooldown) return true
        if(!allowed()) return true
        val all=choices() ?: return false
        val nearby=all.choices.filter { it.candidate.distance(point.first,point.second)<=.16 }
            .sortedBy { it.candidate.distance(point.first,point.second) }.take(4)
        if(nearby.isEmpty()) return false
        val frozen=all.copy(choices=nearby)
        selectionPanel=LearnedSelectionPanel(this,frozen,point.first,point.second,personalRanker()) { selected,ranker,features,index ->
            selectionPanel=null; selectionCooldown=android.os.SystemClock.uptimeMillis()+1000
            TrackerState.cancelPendingClick?.invoke()
            if(selected!=null && allowed()) {
                val current=choices()
                if(frozen.stillValid(current,selected)) {
                    // An explicit user choice is the label; dispatch success is separate.
                    ranker!!.confirm(frozen.screen,frozen.choices.map { it.candidate },features!!,index)
                    rankerState=ranker.encode()
                    getSharedPreferences("personal",0).edit().putString("ranker",rankerState).apply()
                    val c=selected.candidate; val bounds=DisplaySpace.bounds(this)
                    val ok=tap(((c.left+c.right)/2*bounds.width()).toFloat(),((c.top+c.bottom)/2*bounds.height()).toFloat())
                    TrackerState.actionStatus=if(ok) "선택 학습 ${ranker.confirmations}회 · 클릭 요청" else "선택 학습됨 · 클릭 요청 실패"
                } else TrackerState.actionStatus="화면이 바뀌어 선택 취소 · 다시 바라보세요"
            }
        }
        return true
    }
    private var busy=false
    private val handler=Handler(Looper.getMainLooper())
    private var writing: WritingController? = null
    override fun onServiceConnected() {
        instance=this
        writing = WritingController(this)
        if (TrackerState.fault?.setting == RecoverySetting.ACCESSIBILITY) TrackerState.fault = null
        TrackerState.notifyListeners()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        writing?.onEvent(event)
        // Ignore our confirmation overlay's own events, not changes in the source app window.
        if(choosing && event?.packageName?.toString()==packageName && event.windowId!=selectionPanel?.snapshot?.window) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            closeSelection()
            TrackerState.cancelActions?.invoke()
        } else if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if(choosing) closeSelection()
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
    private fun reportInterrupted() {
        if (TrackerState.running) TrackerState.fault = TrackingFault("접근성 조작이 중단되었어요", "접근성 설정에서 NoTouch 눈 조작을 확인한 뒤 조작 재개를 선택해 주세요.", RecoverySetting.ACCESSIBILITY)
        TrackerState.controls=false
        TrackerState.cancelActions?.invoke()
        TrackerState.contextChanged?.invoke()
        TrackerState.notifyListeners()
    }
    override fun onInterrupt() { closeSelection(); writing?.hide(); reportInterrupted() }
    override fun onDestroy() {
        closeSelection(); writing?.close(); writing = null
        if(instance===this) instance=null
        reportInterrupted()
        handler.removeCallbacksAndMessages(null); super.onDestroy()
    }
    private fun allowed()=TrackerState.externalActionsAllowed() && !TrackerState.speaking
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
