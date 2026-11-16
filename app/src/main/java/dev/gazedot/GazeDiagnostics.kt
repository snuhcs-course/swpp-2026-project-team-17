package dev.gazedot

import android.util.Log

/**
 * Debug builds only: logcat diagnostics (tag [TAG]) of calibration outcomes and of the live gaze path, so a device
 * failure can be traced from camera features to the pointer and the tutorial. Read-only: nothing here feeds back into
 * calibration, prediction, smoothing, validity or interaction. Live lines are rate-limited to one per [INTERVAL_MS]
 * per source; calibration outcomes and screen changes are logged as they happen.
 */
object GazeDiagnostics {
    const val TAG = "NoTouchDiag"
    const val INTERVAL_MS = 1_000L
    var enabled = BuildConfig.DEBUG
    /** Where lines go; tests replace it. */
    var sink: (String) -> Unit = { Log.i(TAG, it) }
    private val last = HashMap<String, Long>()

    /** True at most once per [INTERVAL_MS] for each [key]. */
    fun due(key: String, now: Long): Boolean {
        val previous = last[key]
        if (previous != null && now - previous in 0 until INTERVAL_MS) return false
        last[key] = now
        return true
    }

    fun event(text: String) { if (enabled) sink(text) }

    fun calibration(outcome: CalibrationOutcome, saved: Boolean) {
        if (enabled) calibrationLines(outcome, saved).forEach(sink)
    }

    /**
     * The outcome, every anchor exactly as GazeModel.fit received it (Double.toString), and the face position per pass
     * relative to the mean of all anchors, which is the reference a fitted model's validity check uses.
     */
    fun calibrationLines(outcome: CalibrationOutcome, saved: Boolean): List<String> {
        val anchors = outcome.anchors
        val lines = mutableListOf("calibration finished id=${outcome.calibrationId} failure=${outcome.failure ?: "none"} " +
            "model=${if (outcome.model != null) "accepted" else "none"} saved=$saved anchors=${anchors.size}/${CalibrationRules.TOTAL_ANCHORS} " +
            "retries=${anchors.sumOf { it.movementRetries }} duration_ms=${outcome.finishedAtMs - outcome.startedAtMs}")
        anchors.forEach { a ->
            val e = a.anchor.eye
            lines += "anchor pass=${a.pass} point=${a.point} pos=${a.position.id} fx=${e.x} fy=${e.y} face_x=${e.faceX} " +
                "face_y=${e.faceY} scale=${e.scale} tx=${a.anchor.x} ty=${a.anchor.y} retries=${a.movementRetries}"
        }
        if (anchors.isNotEmpty()) {
            val eyes = anchors.map { it.anchor.eye }
            val refX = eyes.map { it.faceX }.average()
            val refY = eyes.map { it.faceY }.average()
            val refScale = eyes.map { it.scale }.average()
            val mx = eyes.map { it.x }.average()
            val my = eyes.map { it.y }.average()
            val sx = kotlin.math.sqrt(eyes.map { (it.x - mx) * (it.x - mx) }.average())
            val sy = kotlin.math.sqrt(eyes.map { (it.y - my) * (it.y - my) }.average())
            lines += "anchors mean face_x=$refX face_y=$refY scale=$refScale feature_std_x=$sx (fit needs >= 0.008) " +
                "feature_std_y=$sy (fit needs >= 0.006)"
            anchors.groupBy { it.pass }.toSortedMap().forEach { (pass, group) ->
                val g = group.map { it.anchor.eye }
                lines += "pass $pass pos=${group.first().position.id} n=${g.size} face_x=${g.map { it.faceX }.average()} " +
                    "face_y=${g.map { it.faceY }.average()} scale=${g.map { it.scale }.average()} " +
                    "face_y_minus_mean=${g.map { it.faceY }.average() - refY} " +
                    "scale_ratio_to_mean=${g.map { it.scale }.average() / refScale}"
            }
        }
        outcome.model?.parameters()?.let { p ->
            lines += "model fit_error=${p.fitError} (accepted <= 0.18) reference face_x=${p.referenceFaceX} " +
                "face_y=${p.referenceFaceY} scale=${p.referenceScale}"
        }
        return lines
    }

    /** One line about the frame the service just processed and what it did with it. */
    fun liveLine(
        sample: GazeSample, ageMs: Long, eye: EyeState, model: GazeModel?, pointerVisible: Boolean,
        actionsEnabled: Boolean, collecting: Boolean, evaluation: EvaluationMode?, interactionAllowed: Boolean,
        listening: Boolean,
    ): String {
        val f = sample.features
        val p = model?.parameters()
        val geometry = if (f == null || p == null) "" else " dfx=${f.faceX - p.referenceFaceX} " +
            "dfy=${f.faceY - p.referenceFaceY} scale_ratio=${f.scale / p.referenceScale}"
        val rejection = when {
            model == null -> "no_model"
            f == null -> "no_features"
            else -> model.rejection(f) ?: "none"
        }
        return "live q=${sample.quality} eye=$eye age_ms=$ageMs model=${if (model != null) "yes" else "no"} " +
            "rejection=$rejection$geometry point=${if (sample.point != null) "yes" else "no"} pointer_visible=$pointerVisible " +
            "collecting=$collecting evaluation=${evaluation?.id ?: "none"} actions=$actionsEnabled " +
            "interaction_allowed=$interactionAllowed screen_listening=$listening"
    }

    fun live(now: Long, line: () -> String) { if (enabled && due("live", now)) sink(line()) }

    fun tutorial(now: Long, line: () -> String) { if (enabled && due("tutorial", now)) sink(line()) }
}
