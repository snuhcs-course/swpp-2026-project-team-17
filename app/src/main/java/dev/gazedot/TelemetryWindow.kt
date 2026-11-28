package dev.gazedot

import kotlin.math.hypot

/** Observes delivered samples only. Never feeds back into estimation or interaction. */
internal class TelemetryWindow {
    private var start: Long? = null
    private var frames = 0L
    private var open = 0L
    private var stale = 0L
    private var missing = 0L
    private var jumps = 0L
    private var maxGap = 0L
    private var previousAt: Long? = null
    private var previousPoint: Pair<Double, Double>? = null
    private val latencies = mutableListOf<Long>()
    fun add(at: Long, latency: Long, eye: EyeState, point: Pair<Double, Double>?) {
        if (start == null) start = at
        frames++
        if (eye == EyeState.OPEN) open++
        if (latency > 500) stale++
        latencies.add(latency.coerceAtLeast(0))
        previousAt?.let { maxGap = maxOf(maxGap, at - it) }
        previousAt = at
        val valid = point?.takeIf { it.first.isFinite() && it.second.isFinite() }
        if (valid == null) missing++
        else previousPoint?.let { if (hypot(valid.first-it.first, valid.second-it.second) > .15) jumps++ }
        previousPoint = valid
    }
    fun drain(now: Long): Map<String, Any>? {
        val began = start ?: return null
        val sorted = latencies.sorted()
        val result = mapOf<String, Any>("duration_ms" to (now-began).coerceAtLeast(0),
            "frames" to frames, "open_frames" to open, "stale_frames" to stale,
            "missing_points" to missing, "jump_steps" to jumps, "max_gap_ms" to maxGap,
            "latency_p95_ms" to sorted[(kotlin.math.ceil(sorted.size*.95).toInt()-1).coerceAtLeast(0)])
        reset()
        return result
    }
    fun reset() {
        start=null; frames=0; open=0; stale=0; missing=0; jumps=0; maxGap=0
        previousAt=null; previousPoint=null; latencies.clear()
    }
}
