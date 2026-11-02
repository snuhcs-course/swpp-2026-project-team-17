package dev.gazedot

enum class EyeGesture { WINK, DOUBLE_BLINK }

enum class ScrollDirection(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0)
}

/** Emit gestures only after both eyes reopen. Lost/ambiguous input never becomes a wink. */
class EyeGestureDetector {
    private var previousAt: Long? = null
    private var ready = false
    private var closure: EyeState? = null
    private var closedAt = 0L
    private var firstBlinkOpenedAt: Long? = null
    private var uncertainAt: Long? = null

    fun reset() {
        previousAt = null; ready = false; closure = null; firstBlinkOpenedAt = null; uncertainAt = null
    }

    fun update(at: Long, eye: EyeState): EyeGesture? {
        if (previousAt?.let { at <= it || at - it > 250 } == true) reset()
        previousAt = at
        if (eye == EyeState.LOST) {
            ready = false; closure = null; firstBlinkOpenedAt = null
            return null
        }
        if (eye == EyeState.UNCERTAIN) {
            if (uncertainAt == null) uncertainAt = at
            if (at - uncertainAt!! > 150) { ready = false; closure = null; firstBlinkOpenedAt = null }
            return null
        }
        uncertainAt = null
        if (eye == EyeState.OPEN) {
            val type = closure
            closure = null
            val wasReady = ready
            ready = true
            if (!wasReady || type == null) return null
            val duration = at - closedAt
            if (type == EyeState.LEFT_WINK || type == EyeState.RIGHT_WINK) {
                firstBlinkOpenedAt = null
                return if (duration in WINK_MIN_MS..WINK_MAX_MS) EyeGesture.WINK else null
            }
            if (duration !in 70..350) { firstBlinkOpenedAt = null; return null }
            val prior = firstBlinkOpenedAt
            if (prior != null && closedAt - prior in 70..450) {
                firstBlinkOpenedAt = null
                return EyeGesture.DOUBLE_BLINK
            }
            firstBlinkOpenedAt = at
            return null
        }
        if (!ready) return null
        if (closure == null) {
            closure = eye; closedAt = at
        } else if (closure != eye) {
            // A staggered ordinary blink must not count as a unilateral wink.
            if (eye == EyeState.CLOSED) closure = EyeState.CLOSED
            else { closure = null; ready = false; firstBlinkOpenedAt = null }
        }
        return null
    }

    companion object {
        const val WINK_MIN_MS = 100L
        const val WINK_MAX_MS = 800L
    }
}

/** Shared by the tutorial animation and accessibility swipes. */
object ScrollSettings {
    const val TOP_BOUNDARY = 1.0 / 3.0
    const val BOTTOM_BOUNDARY = 2.0 / 3.0
    const val DIRECTION_HOLD_MS = 350L
    const val DISTANCE_FRACTION = 0.15
    const val DURATION_MS = 650L
    const val INTERVAL_MS = 1_100L

    fun direction(point: Pair<Double, Double>): ScrollDirection? {
        val (x, y) = point
        if (x !in 0.0..1.0 || y !in 0.0..1.0) return null
        val horizontal = when {
            x < TOP_BOUNDARY -> ScrollDirection.LEFT
            x > BOTTOM_BOUNDARY -> ScrollDirection.RIGHT
            else -> null
        }
        val vertical = when {
            y < TOP_BOUNDARY -> ScrollDirection.UP
            y > BOTTOM_BOUNDARY -> ScrollDirection.DOWN
            else -> null
        }
        // Corners select one axis; a tie retains vertical behavior.
        return if (horizontal != null && (vertical == null || kotlin.math.abs(x - .5) > kotlin.math.abs(y - .5))) horizontal else vertical
    }

    fun swipe(direction: ScrollDirection, width: Int, height: Int): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        val dx = direction.dx * width * DISTANCE_FRACTION / 2
        val dy = direction.dy * height * DISTANCE_FRACTION / 2
        return (width / 2.0 + dx to height / 2.0 + dy) to
            (width / 2.0 - dx to height / 2.0 - dy)
    }
}

data class ScrollStep(val modeChanged: Boolean? = null, val direction: ScrollDirection? = null,
    val doubleBlink: Boolean = false, val wink: Boolean = false)

class ScrollController {
    var enabled = false
        private set
    private val gestures = EyeGestureDetector()
    private var previousAt: Long? = null
    private var direction: ScrollDirection? = null
    private var since = 0L
    private var lastScrollAt: Long? = null

    fun reset() {
        enabled = false; previousAt = null; lastScrollAt = null; cancelPending()
    }

    fun cancelPending() {
        gestures.reset(); direction = null
    }

    fun update(at: Long, receivedAt: Long, eye: EyeState, point: Pair<Double, Double>?): ScrollStep {
        if (receivedAt - at !in 0..250 || eye == EyeState.LOST ||
            previousAt?.let { at <= it || at - it > 250 } == true) {
            val wasEnabled = enabled
            reset(); previousAt = at
            return ScrollStep(modeChanged = if (wasEnabled) false else null)
        }
        previousAt = at
        val gesture = gestures.update(at, eye)
        if (gesture == EyeGesture.WINK) {
            enabled = !enabled; direction = null
            return ScrollStep(modeChanged = enabled, wink = true)
        }
        if (!enabled || eye != EyeState.OPEN || point == null ||
            point.first !in 0.0..1.0 || point.second !in 0.0..1.0) {
            direction = null
            return ScrollStep(doubleBlink = gesture == EyeGesture.DOUBLE_BLINK)
        }
        val next = ScrollSettings.direction(point)
        if (next != direction) { direction = next; since = receivedAt }
        if (next != null && receivedAt - since >= ScrollSettings.DIRECTION_HOLD_MS &&
            lastScrollAt?.let { receivedAt - it >= ScrollSettings.INTERVAL_MS } != false) {
            lastScrollAt = receivedAt
            return ScrollStep(direction = next)
        }
        return ScrollStep()
    }
}

/** Advance only after actual movement in both directions and exiting scroll mode. */
class ScrollPractice {
    private var exitedByWink = false
    var up = false
        private set
    var down = false
        private set
    var left = false
        private set
    var right = false
        private set
    fun moved(delta: Int) {
        moved(0, delta)
    }
    fun moved(dx: Int, dy: Int) {
        if (dy < 0) up = true
        if (dy > 0) down = true
        if (dx < 0) left = true
        if (dx > 0) right = true
    }
    fun modeChanged(enabled: Boolean) { exitedByWink = !enabled }
    fun complete(scrollMode: Boolean) = up && down && !scrollMode && exitedByWink
}
