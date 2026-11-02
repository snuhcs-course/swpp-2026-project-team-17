package dev.gazedot

/** Normalized display coordinates, timestamped when the camera frame was processed. */
data class ScreenGaze(val x: Double, val y: Double, val uptimeMillis: Long)

/**
 * The inputs and result of one completed nine-point calibration, kept in memory like the model itself.
 * [anchors] are the exact GazeModel.fit inputs; [basisWidth]/[basisHeight] are the normalization basis
 * the calibration screen divided by (WindowManager.maximumWindowMetrics on API 30+).
 */
class CalibrationRecord(
    val model: GazeModel, anchors: List<Anchor>,
    val basisWidth: Int, val basisHeight: Int, val rotation: Int,
    val viewLeft: Int, val viewTop: Int, val viewWidth: Int, val viewHeight: Int,
    val completedAtMs: Long,
) {
    val anchors: List<Anchor> = anchors.toList()
}

/** Accessed on the main thread. Camera worker posts immutable results here. */
object TrackerState {
    var running = false
    var model: GazeModel? = null
    var features: EyeFeatures? = null
    var gaze: ScreenGaze? = null
        private set
    var status = "시작 전 · 기기를 거치대에 고정해 주세요"
    var listener: (() -> Unit)? = null

    /** The last calibration completed in the current tracking run. Gaze evaluation snapshots it. */
    var calibration: CalibrationRecord? = null
        private set
    var calibrationSerial = 0
        private set
    /** Calibration serial already used by an evaluation run; each run needs a fresh calibration. */
    var evaluatedCalibrationSerial = 0
    /** Set by TrackingService: clears the smoothing state; the argument names the cause. */
    var resetSmoothing: ((String) -> Unit)? = null
    /** Set by TrackingService: on-screen origin and size of the pointer overlay view, or null. */
    var overlayBounds: (() -> IntArray?)? = null

    fun publish(f: EyeFeatures?, message: String, screenGaze: ScreenGaze? = null) {
        features = f
        gaze = screenGaze
        status = message
        listener?.invoke()
    }

    fun calibrationCompleted(record: CalibrationRecord) {
        calibration = record
        calibrationSerial++
    }

    fun clearCalibration() { calibration = null }
}
