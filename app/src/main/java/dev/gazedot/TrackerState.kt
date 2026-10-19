package dev.gazedot

/** Normalized display coordinates, timestamped when the camera frame was processed. */
data class ScreenGaze(val x: Double, val y: Double, val uptimeMillis: Long)

/** Accessed on the main thread. Camera worker posts immutable results here. */
object TrackerState {
    var running = false
    var model: GazeModel? = null
    var features: EyeFeatures? = null
    var gaze: ScreenGaze? = null
        private set
    var status = "시작 전 · 기기를 거치대에 고정해 주세요"
    var listener: (() -> Unit)? = null
    fun publish(f: EyeFeatures?, message: String, screenGaze: ScreenGaze? = null) {
        features = f
        gaze = screenGaze
        status = message
        listener?.invoke()
    }
}
