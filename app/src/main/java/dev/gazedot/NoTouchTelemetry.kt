package dev.gazedot

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NoTouchApplication : Application() {
    override fun onCreate() { super.onCreate(); NoTouchTelemetry.initialize(this) }
}

/** Main-thread observer. No images, iris features, screen text, package names or user ID. */
object NoTouchTelemetry {
    private var context: Context? = null
    private var analytics: FirebaseAnalytics? = null
    private val window = TelemetryWindow()
    private val recent = ArrayDeque<JSONObject>()
    private var session = UUID.randomUUID().toString()
    private var seq = 0L
    private var lastPoint = -1L
    private var lastSummary = -1L
    var enabled = false; private set
    val connected get() = analytics != null
    fun initialize(context: Context) {
        this.context = context.applicationContext
        // Default-on for team testing; preserve an explicit opt-out across restarts/upgrades.
        enabled = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE).getBoolean("enabled", true)
        analytics = runCatching {
            if (FirebaseApp.initializeApp(context) != null) FirebaseAnalytics.getInstance(context) else null
        }.getOrNull()
        runCatching { analytics?.setAnalyticsCollectionEnabled(enabled) }
        event("telemetry_started")
    }
    fun setEnabled(value: Boolean) {
        enabled = value
        context?.getSharedPreferences("telemetry", Context.MODE_PRIVATE)?.edit()?.putBoolean("enabled",value)?.apply()
        window.reset(); lastPoint=-1; lastSummary=-1
        runCatching { analytics?.setAnalyticsCollectionEnabled(value) }
        if (!value) { recent.clear(); runCatching { analytics?.resetAnalyticsData() } }
        else { session=UUID.randomUUID().toString(); seq=0; event("telemetry_enabled") }
    }
    fun event(name: String, values: Map<String, Any> = emptyMap()) {
        if (!enabled) return
        // A telemetry failure must never interrupt camera processing or a gesture callback.
        runCatching {
            val data = values + mapOf("trace_session" to session, "seq" to ++seq,
                "elapsed_ms" to SystemClock.uptimeMillis(), "app_build" to BuildConfig.VERSION_CODE.toLong())
            val bundle = Bundle()
            val json = JSONObject().put("event",name)
            data.forEach { (key,value) ->
                when (value) {
                    is String -> { bundle.putString(key,value); json.put(key,value) }
                    is Long -> { bundle.putLong(key,value); json.put(key,value) }
                    is Int -> { bundle.putLong(key,value.toLong()); json.put(key,value) }
                    is Double -> if(value.isFinite()) { bundle.putDouble(key,value); json.put(key,value) }
                }
            }
            recent.addLast(json)
            while (recent.size>300) recent.removeFirst()
            analytics?.logEvent(name,bundle)
        }
    }
    fun frame(at: Long, latency: Long, eye: EyeState, point: Pair<Double,Double>?, quality: String,
              collecting: Boolean, raw: Pair<Double,Double>? = null) {
        if (!enabled) return
        runCatching {
            window.add(at,latency,eye,point)
            if(lastSummary<0) lastSummary=at
            if(at-lastSummary>=10_000) { flush(at); lastSummary=at }
            if(lastPoint<0 || at-lastPoint>=1_000) {
                lastPoint=at
                val values=mutableMapOf<String,Any>("eye" to eye.name,"quality" to quality,
                    "latency_ms" to latency,"phase" to if(collecting) "calibration" else "tracking",
                    "has_point" to if(point==null) 0L else 1L)
                point?.let { values["x"]=it.first; values["y"]=it.second }
                raw?.let { values["raw_x"]=it.first; values["raw_y"]=it.second }
                event("gaze_sample",values)
            }
        }
    }
    fun flush(at: Long = SystemClock.uptimeMillis()) {
        if(enabled) runCatching { window.drain(at)?.let { event("gaze_window",it) } }
    }
    fun export(): String = JSONObject().put("schema",1).put("firebase_configured",connected)
        .put("events",JSONArray(recent.toList())).toString(2)
}
