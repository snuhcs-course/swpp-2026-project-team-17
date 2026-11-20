package dev.gazedot

import android.content.Intent
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import org.json.*

/** Six-second fixation: one second settling, five seconds of unfiltered telemetry. */
class GazeDiagnosticActivity: ComponentActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private var active=false
    private var finished=false
    private var started=0L
    private var model:GazeModel?=null
    private val filtered=mutableListOf<List<Double>>()
    private var last=0L
    private lateinit var chart:View
    private val tick=object:Runnable {
        override fun run() {
            if(finished)return
            if(!TrackerState.running || TrackerState.model!==model) { showResult("추적·보정이 변경되어 진단 중단","");return }
            val now=SystemClock.uptimeMillis()
            TrackerState.sample?.let { s -> if(s.timestampMs>last && s.timestampMs>=started+1000) {
                last=s.timestampMs
                s.point?.let { filtered+=listOf(s.timestampMs.toDouble(),it.first,it.second) }
            } }
            if(now-started>=6000){complete();return}
            chart.invalidate();handler.postDelayed(this,50)
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        model=TrackerState.model
        if(!TrackerState.running){showResult("먼저 카메라를 시작해 주세요. 보정 실패 상태에서도 원본 진단은 가능합니다.","");return}
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        chart=object:View(this) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c:Canvas) {
                c.drawColor(Color.rgb(15,23,42));paint.color=Color.WHITE;paint.textAlign=Paint.Align.CENTER
                paint.textSize=16*resources.displayMetrics.density
                c.drawText("폰과 머리를 고정하고 가운데 점만 보세요",width/2f,70f*resources.displayMetrics.density,paint)
                c.drawText("${((6000-(SystemClock.uptimeMillis()-started)).coerceAtLeast(0)+999)/1000}초",width/2f,100f*resources.displayMetrics.density,paint)
                paint.color=Color.CYAN;c.drawCircle(width/2f,height/2f,10*resources.displayMetrics.density,paint)
            }
        }
        setContentView(chart);started=SystemClock.uptimeMillis();handler.post(tick)
    }
    override fun onStart(){super.onStart();TrackerState.settingsStarted();active=true;TrackerState.precisionTraining=!finished}
    override fun onStop(){handler.removeCallbacksAndMessages(null);if(!finished){finished=true};TrackerState.precisionTraining=false;if(active){active=false;TrackerState.settingsStopped()};super.onStop()}
    override fun onRestart(){super.onRestart();showResult("진단이 중단되었습니다. 다시 열어 측정해 주세요.","")}
    private fun complete(){
        val loc=IntArray(2);chart.getLocationOnScreen(loc);val bounds=DisplaySpace.bounds(this)
        val target=(loc[0]+chart.width/2.0)/bounds.width() to (loc[1]+chart.height/2.0)/bounds.height()
        val rows=GazeTrace.since(started+1000)
        val report=GazeTrace.report(rows,target,model?.mappingGain ?: 0.0)
        val json=JSONObject().put("version",29).put("report",report).put("target",JSONArray(listOf(target.first,target.second)))
            .put("rows",JSONArray(rows.map { r -> JSONObject().put("at",r.at).put("latency_ms",r.latency).put("eye",r.eye)
                .put("raw_cm",r.raw?.let{JSONArray(listOf(it.first,it.second))} ?: JSONObject.NULL)
                .put("mapped_unclipped",r.mapped?.let{JSONArray(listOf(it.first,it.second))} ?: JSONObject.NULL)
                .put("head",r.head?.let{JSONArray(it)} ?: JSONObject.NULL) }))
            .put("filtered",JSONArray(filtered)).put("calibration",getSharedPreferences("personal",MODE_PRIVATE).getString("calibration_audit","없음"))
        json.put("calibration_samples",runCatching { JSONObject(java.io.File(filesDir,"last-calibration-audit.json").readText()) }.getOrNull() ?: JSONObject.NULL)
        val data=json.toString(2)
        runCatching { java.io.File(filesDir,"last-gaze-diagnostic.json").writeText(data) }
        getSharedPreferences("personal",MODE_PRIVATE).edit().putString("gaze_diagnostic",data).apply()
        showResult(report,data)
    }
    private fun showResult(report:String,data:String){
        finished=true;handler.removeCallbacksAndMessages(null)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,72,24,32)}
        root.addView(TextView(this).apply{text=report;textSize=17f})
        if(data.isNotEmpty())root.addView(Button(this).apply{text="진단 결과 공유";setOnClickListener{startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,data)},"진단 결과 공유"))}})
        root.addView(Button(this).apply{text="돌아가기";setOnClickListener{finish()}})
        setContentView(ScrollView(this).apply{addView(root)})
    }
}
