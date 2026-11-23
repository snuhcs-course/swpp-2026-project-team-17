package dev.gazedot

/** A short blink pauses collection, not the whole fixation. Never fills in missing samples. */
internal class CalibrationContinuity {
    private var lastValid: Long? = null
    private var interrupted = false
    private var resumeAfter = 0L
    fun missing(now: Long): Boolean {
        interrupted = true
        return lastValid?.let { now - it > 750 } ?: true
    }
    fun valid(at: Long): Boolean {
        if (interrupted) { resumeAfter = at + 150; interrupted = false }
        lastValid = at
        return at >= resumeAfter
    }
    fun reset() { lastValid = null; interrupted = false; resumeAfter = 0 }
}
