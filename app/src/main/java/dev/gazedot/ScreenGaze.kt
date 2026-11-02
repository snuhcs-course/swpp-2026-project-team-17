package dev.gazedot

/** Final local camera estimate in full-display coordinates; never clamp delivery. */
data class ScreenGaze(
    val x: Double,
    val y: Double,
    val uptimeMillis: Long,
    val quality: TrackingQuality = TrackingQuality.TRACKING,
) {
    val valid get() = quality == TrackingQuality.TRACKING && x.isFinite() && y.isFinite()
    fun fresh(now: Long) = valid && now - uptimeMillis in 0..TutorialDwell.MAX_SAMPLE_GAP_MS
}

fun GazeSample.toScreenGaze(): ScreenGaze? = point?.let {
    ScreenGaze(it.first, it.second, timestampMs, quality)
}

/** The view origin already includes system bars, cutouts and window offsets. */
internal data class DisplayCoordinates(val width: Int, val height: Int) {
    fun normalize(screenX: Double, screenY: Double) = screenX / width to screenY / height
    fun inView(gaze: ScreenGaze, left: Int, top: Int) =
        gaze.x * width - left to gaze.y * height - top
}
