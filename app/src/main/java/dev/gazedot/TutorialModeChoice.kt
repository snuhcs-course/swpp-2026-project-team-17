package dev.gazedot

/** A new page must first receive fresh gaze outside its options before a gaze choice can start. */
internal class TutorialModeChoice {
    private val dwell = TutorialDwell()
    private var armed = false
    var candidate: Boolean? = null
        private set
    val progress get() = dwell.progress

    fun reset() { armed = false; interrupt() }

    fun interrupt() { candidate = null; dwell.reset() }

    fun expire(now: Long) = dwell.expire(now)

    fun update(target: Boolean?, valid: Boolean, sampleAt: Long, now: Long): Boolean? {
        if (!valid || now - sampleAt !in 0..TutorialDwell.MAX_SAMPLE_GAP_MS) {
            interrupt()
            return null
        }
        if (!armed) {
            if (target == null) armed = true
            interrupt()
            return null
        }
        if (target != candidate) { dwell.reset(); candidate = target }
        return if (dwell.update(target != null, sampleAt, now)) target else null
    }
}
