package dev.gazedot

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.*
import android.widget.*

data class AccessibleChoice(val candidate: SelectionCandidate, val label: String)
data class ChoiceSnapshot(val screen: String, val window: Int, val choices: List<AccessibleChoice>) {
    fun stillValid(current: ChoiceSnapshot?, selected: AccessibleChoice): Boolean =
        current!=null && current.screen==screen && current.window==window &&
            choices.contains(selected) && current.choices.contains(selected)
}

/** Frozen candidate set. Touch or a new deliberate gaze confirmation supplies the training label. */
class LearnedSelectionPanel(
    private val service: ControlAccessibilityService,
    val snapshot: ChoiceSnapshot,
    x: Double, y: Double,
    private val ranker: PersonalTargetRanker,
    private val done: (AccessibleChoice?, PersonalTargetRanker?, List<DoubleArray>?, Int) -> Unit,
) {
    private val manager=service.getSystemService(android.view.WindowManager::class.java)
    private val root=LinearLayout(service)
    private val buttons=mutableListOf<Button>()
    private val remote=BlinkRemote(snapshot.choices.size+1)
    private var lastButton: Button?=null
    private var armed=false
    private var closed=false
    private val opened=SystemClock.uptimeMillis()
    private val features=snapshot.choices.map { ranker.features(snapshot.screen,it.candidate,x,y) }
    private val probabilities=ranker.probabilities(features)
    private val order=snapshot.choices.indices.sortedByDescending { probabilities[it] }
    private val timeout=Runnable { select(-1) }
    init {
        val d=service.resources.displayMetrics.density
        root.orientation=LinearLayout.VERTICAL; root.setPadding((16*d).toInt(),(12*d).toInt(),(16*d).toInt(),(12*d).toInt())
        root.setBackgroundColor(Color.rgb(235,242,255))
        root.addView(TextView(service).apply {
            text="어느 버튼인가요? · 학습 ${ranker.confirmations}회\n짧게 양눈 깜빡임: 다음 번호 · 1초 감았다 뜨기: 확정\n마지막 항목은 취소입니다. 터치도 가능."
            textSize=16f; setTextColor(Color.BLACK)
        })
        order.forEachIndexed { position,index ->
            val choice=snapshot.choices[index]
            val b=Button(service).apply {
                text="${position+1}. ${choice.label}"; isAllCaps=false; textSize=18f
                maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END
                setOnClickListener { select(index) }
            }
            buttons+=b; root.addView(b,LinearLayout.LayoutParams(-1,(60*d).toInt()))
        }
        val cancel=Button(service).apply { text="해당 없음 · 취소"; setOnClickListener { select(-1) } }
        buttons+=cancel; root.addView(cancel,LinearLayout.LayoutParams(-1,(56*d).toInt()))
        val p=WindowManager.LayoutParams((minOf(service.resources.displayMetrics.widthPixels/d-32,360f)*d).toInt(),-2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.CENTER }
        manager.addView(root,p); root.postDelayed(timeout,60000); highlight()
    }
    private fun highlight() {
        buttons.forEachIndexed { i,b ->
            b.setBackgroundColor(if(i==remote.index) Color.rgb(130,185,255) else Color.rgb(240,240,240))
            b.contentDescription=(if(i==remote.index) "현재 선택 · " else "")+b.text
        }
    }
    fun update(eye: EyeState, at: Long, now: Long) {
        if(closed)return
        if(now-opened>60000 || !TrackerState.running || !TrackerState.controls || !TrackerState.interactionAllowed || TrackerState.collecting || TrackerState.precisionTraining) { select(-1); return }
        if(remote.update(eye,at,now)) {
            select(order.getOrNull(remote.index) ?: -1)
        } else highlight()
    }
    private fun select(index: Int) {
        if(closed) { return }
        val chosen=if(SystemClock.uptimeMillis()-opened<=60000) index else -1
        close()
        done(snapshot.choices.getOrNull(chosen),if(chosen>=0) ranker else null,features,chosen)
    }
    fun close() { if(closed) return; closed=true; root.removeCallbacks(timeout); if(root.isAttachedToWindow) manager.removeView(root) }
}
