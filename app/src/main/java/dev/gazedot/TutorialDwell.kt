package dev.gazedot

/** Only fresh, consecutive camera samples can complete a selection. */
internal class TutorialDwell {
    private var startedAt: Long? = null
    private var lastSampleAt: Long? = null
    var progress = 0f
        private set

    fun reset() {
        startedAt = null
        lastSampleAt = null
        progress = 0f
    }

    fun expire(now: Long) {
        if (lastSampleAt?.let { now - it > MAX_SAMPLE_GAP_MS } == true) reset()
    }

    fun update(inside: Boolean, sampleAt: Long, now: Long): Boolean {
        if (!inside || now - sampleAt !in 0..MAX_SAMPLE_GAP_MS ||
            lastSampleAt?.let { sampleAt <= it } == true) {
            reset()
            return false
        }
        expire(sampleAt)
        if (startedAt == null) startedAt = sampleAt
        lastSampleAt = sampleAt
        progress = ((sampleAt - startedAt!!) / DURATION_MS.toFloat()).coerceIn(0f, 1f)
        return progress >= 1f
    }

    companion object {
        const val DURATION_MS = 1_000L
        // The existing camera emits at most ~15 fps. A missing stream must not count as a fixation.
        const val MAX_SAMPLE_GAP_MS = 250L
    }
}

/** Bounds are actual screen pixels, including the view's window/inset offset. */
internal data class TutorialButtonBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun contains(gaze: ScreenGaze, displayWidth: Int, displayHeight: Int): Boolean {
        if (displayWidth <= 0 || displayHeight <= 0 || width <= 0 || height <= 0 ||
            !gaze.x.isFinite() || !gaze.y.isFinite() || gaze.x !in 0.0..1.0 || gaze.y !in 0.0..1.0) return false
        val x = gaze.x * displayWidth - left
        val y = gaze.y * displayHeight - top
        if (x < 0 || x >= width || y < 0 || y >= height) return false
        val radius = minOf(width, height) / 2.0
        val dx = x - x.coerceIn(radius, width - radius)
        val dy = y - y.coerceIn(radius, height - radius)
        return dx * dx + dy * dy <= radius * radius
    }
}
