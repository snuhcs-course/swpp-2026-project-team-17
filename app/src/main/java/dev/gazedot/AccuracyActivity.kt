package dev.gazedot

import android.os.*
import android.graphics.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import kotlin.math.abs

/** Fit five residual anchors, then accept only on four distinct held-out targets. */
class AccuracyActivity : ComponentActivity() {
    private var active=false
    private var view: MeasurementView?=null
    private val handler=Handler(Looper.getMainLooper())
    private var returnButton: Button?=null
    private val returnDwell=TutorialDwell()
    private var returnSampleAt=0L
    private val resultTick=object: Runnable {
        override fun run() {
            val button=returnButton ?: return
            val gaze=TrackerState.gaze; val now=SystemClock.uptimeMillis()
            val loc=IntArray(2); button.getLocationOnScreen(loc); val display=DisplaySpace.bounds(this@AccuracyActivity)
            val inside=gaze?.fresh(now)==true && TutorialButtonBounds(loc[0],loc[1],button.width,button.height).contains(gaze,display.width(),display.height())
            returnDwell.expire(now)
            if(!inside) returnDwell.reset()
            if(gaze!=null && gaze.uptimeMillis>returnSampleAt) {
                returnSampleAt=gaze.uptimeMillis
                if(returnDwell.update(inside,gaze.uptimeMillis,now)) { finish(); return }
            }
            handler.postDelayed(this,50)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val model=TrackerState.model
        if(model==null || !TrackerState.running) { setContentView(TextView(this).apply { text="먼저 카메라와 5점 보정을 완료해 주세요." }); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view=MeasurementView(model); setContentView(view)
    }
    override fun onStart() { super.onStart(); TrackerState.settingsStarted(); active=true; TrackerState.precisionTraining=true }
    override fun onStop() {
        handler.removeCallbacks(resultTick); returnDwell.reset()
        view?.cancel(); TrackerState.precisionTraining=false
        if(active) { active=false; TrackerState.settingsStopped() }
        super.onStop()
    }
    override fun onRestart() {
        super.onRestart()
        // Never resume a partial target after an unobserved pose/time change.
        showResult("측정이 중단되었습니다. 기존 보정을 유지합니다.\n홈에서 정밀 보정을 다시 시작해 주세요.")
    }
    override fun onResume() {
        super.onResume()
        if(returnButton!=null) { TrackerState.precisionTraining=false; handler.removeCallbacks(resultTick); handler.post(resultTick) }
    }
    private fun showResult(message: String) {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,100,32,40) }
        root.addView(TextView(this).apply { text=message; textSize=20f })
        returnButton=Button(this).apply { text="돌아가기 · 1초 응시 또는 터치"; setOnClickListener { finish() } }
        root.addView(returnButton)
        setContentView(root); TrackerState.precisionTraining=false
        handler.removeCallbacks(resultTick); handler.post(resultTick)
    }
    private inner class MeasurementView(val original: GazeModel): View(this@AccuracyActivity) {
        val targets=listOf(.2 to .2,.8 to .2,.5 to .5,.2 to .8,.8 to .8,
            .35 to .35,.65 to .35,.35 to .65,.65 to .65)
        val records=mutableListOf<CorrectionAnchor>()
        val samples=mutableListOf<Triple<Double,Double,EyeFeatures>>()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        var index=0; var since=SystemClock.uptimeMillis(); var last=0L; var closed=false
        val tick=object: Runnable {
            override fun run() {
                if(closed) return
                val now=SystemClock.uptimeMillis()
                if(!TrackerState.running || TrackerState.model !== original) { end("추적 또는 기본 모델이 변경되어 중단했습니다. 기존 보정 유지."); return }
                if(now-since>8000) { end("눈 표본이 부족하거나 흔들림이 큽니다. 기존 보정 유지.\n조명·자세를 확인하고 다시 시도하세요."); return }
                val sample=TrackerState.sample
                val features=sample?.calibrationFeatures(now,last)
                if(features!=null && sample.timestampMs>=since+800) {
                    last=sample.timestampMs
                    original.predictUncorrected(features)?.let { samples+=Triple(it.first,it.second,features) }
                }
                if(samples.size>=12 && now-since>=1800 && width>0 && height>0) {
                    fun median(v: List<Double>)=v.sorted()[v.size/2]
                    val x=median(samples.map { it.first }); val y=median(samples.map { it.second })
                    val fx=median(samples.map { it.third.faceX }); val fy=median(samples.map { it.third.faceY })
                    if(samples.count { abs(it.first-x)<=.08 && abs(it.second-y)<=.08 && abs(it.third.faceX-fx)<=.03 && abs(it.third.faceY-fy)<=.03 } >= samples.size*.8) {
                        val loc=IntArray(2); getLocationOnScreen(loc); val display=DisplaySpace.bounds(this@AccuracyActivity)
                        val target=targets[index]
                        records+=CorrectionAnchor(x,y,(loc[0]+width*target.first)/display.width(),(loc[1]+height*target.second)/display.height())
                        index++; samples.clear(); since=now
                        if(index==targets.size) { complete(); return }
                    } else samples.clear()
                }
                invalidate(); postDelayed(this,50)
            }
        }
        init { setBackgroundColor(Color.rgb(15,23,42)); post(tick) }
        fun cancel() { closed=true; removeCallbacks(tick) }
        override fun onDetachedFromWindow() { cancel(); super.onDetachedFromWindow() }
        fun complete() {
            val candidate=ResidualCorrection.fit(records.take(5)); val validation=records.drop(5)
            val before=ResidualCorrection.error(validation,original.correction)
            val after=candidate?.let { ResidualCorrection.error(validation,it) }
            val accept=candidate!=null && ResidualCorrection.accept(validation,candidate,original.correction)
            if(accept && TrackerState.model===original) {
                TrackerState.cancelActions?.invoke(); TrackerState.model=original.withCorrection(candidate); TrackerState.clearCoordinates()
            }
            val report="학습 5점 / 별도 확인 4점\n평균 오차: %.3f → %s\n%s\n화면 가로·세로 정규화 거리 기준\n현재 카메라 세션에 적용됩니다.".format(before,after?.let { "%.3f".format(it) } ?: "산출 불가",
                if(accept) "추가 보정 적용됨" else "개선 기준 미달 · 기존 보정 유지")
            getSharedPreferences("personal",MODE_PRIVATE).edit().putString("accuracy_report",report).apply()
            val log=org.json.JSONObject().put("accepted",accept).put("before",before)
                .put("after",after ?: org.json.JSONObject.NULL).put("at",System.currentTimeMillis())
                .put("anchors",org.json.JSONArray(records.map { listOf(it.rawX,it.rawY,it.targetX,it.targetY) }))
            runCatching { java.io.File(filesDir,"last-accuracy-check.json").writeText(log.toString()) }
            end(report)
        }
        fun end(message: String) {
            cancel(); TrackerState.precisionTraining=false
            showResult(message)
        }
        override fun onDraw(c: Canvas) {
            val t=targets.getOrNull(index) ?: return; val d=resources.displayMetrics.density
            paint.color=Color.WHITE; paint.textSize=16*d; paint.textAlign=Paint.Align.CENTER
            c.drawText("${if(index<5) "추가 보정" else "새 위치에서 확인"} ${index+1}/9",width/2f,40*d,paint)
            c.drawText("머리를 고정하고 파란 점만 보세요",width/2f,65*d,paint)
            paint.color=Color.CYAN; c.drawCircle((width*t.first).toFloat(),(height*t.second).toFloat(),12*d,paint)
        }
    }
}
