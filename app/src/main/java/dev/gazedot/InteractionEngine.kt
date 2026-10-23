package dev.gazedot

import kotlin.math.hypot

enum class EyeState { OPEN, CLOSED, LEFT_WINK, RIGHT_WINK, UNCERTAIN, LOST }
sealed class EyeAction {
    data class Click(val point: Pair<Double, Double>) : EyeAction()
    data class Scroll(val direction: ScrollDirection) : EyeAction()
    data class ScrollMode(val enabled: Boolean) : EyeAction()
    data object Dictate : EyeAction()
    data object Cancel : EyeAction()
}

/** Dwell uses result receipt time; blink and scroll timing retains camera timestamps. */
class InteractionEngine {
    private val dwell = TutorialDwell()
    private val scroll = ScrollController()
    val scrollMode get() = scroll.enabled
    val progress get() = dwell.progress
    val pending get() = anchor != null
    private var anchor: Pair<Double, Double>? = null
    private var anchorKey: String? = null
    private var selectedPoint: Pair<Double, Double>? = null
    private var selectedKey: String? = null
    private var previousFrame: Long? = null
    private var cooldownUntil = 0L

    private fun cancelDwell() {
        dwell.reset(); anchor = null; anchorKey = null
    }

    fun cancelClick() = cancelDwell()

    fun reset(preserveSelection: Boolean = false, preserveScrollMode: Boolean = false) {
        cancelDwell()
        if (!preserveSelection) { selectedPoint = null; selectedKey = null }
        if (preserveScrollMode) scroll.cancelPending() else scroll.reset()
        previousFrame = null; cooldownUntil = 0
    }

    private fun sameTarget(point: Pair<Double, Double>, key: String?, reference: Pair<Double, Double>, referenceKey: String?) =
        if (key != null || referenceKey != null) key != null && key == referenceKey
        else hypot(point.first - reference.first, point.second - reference.second) <= FIXATION_RADIUS

    fun update(
        now: Long,
        eye: EyeState,
        point: Pair<Double, Double>?,
        clickPoint: Pair<Double, Double>? = point,
        targetKey: String? = null,
        receivedAt: Long = now,
    ): List<EyeAction> {
        if (eye == EyeState.LOST || previousFrame?.let { now - it > 250 || now <= it } == true) {
            val cancelled = pending
            reset(preserveSelection = true); previousFrame = now
            return if (cancelled) listOf(EyeAction.Cancel) else emptyList()
        }
        previousFrame = now
        val scrolling = scroll.update(now, receivedAt, eye, point)
        if (scrolling.modeChanged != null) {
            cancelDwell()
            return listOf(EyeAction.ScrollMode(scrolling.modeChanged))
        }
        if (scroll.enabled) {
            cancelDwell()
            return scrolling.direction?.let { listOf(EyeAction.Scroll(it)) } ?: emptyList()
        }
        if (scrolling.doubleBlink) {
            cancelDwell(); cooldownUntil = now + 700
            return listOf(EyeAction.Dictate)
        }
        if (eye != EyeState.OPEN) {
            cancelDwell()
            return emptyList()
        }
        if (now < cooldownUntil) return emptyList()
        if (point == null || !point.first.isFinite() || !point.second.isFinite()) {
            cancelDwell()
            return emptyList()
        }
        if (point.first !in 0.0..1.0 || point.second !in 0.0..1.0) {
            cancelDwell(); selectedPoint = null; selectedKey = null
            return emptyList()
        }
        val target = clickPoint?.takeIf { it.first in 0.0..1.0 && it.second in 0.0..1.0 }
        if (target == null) { cancelDwell(); return emptyList() }
        selectedPoint?.let {
            if (sameTarget(target, targetKey, it, selectedKey)) return emptyList()
            selectedPoint = null; selectedKey = null
        }
        if (anchor?.let { sameTarget(target, targetKey, it, anchorKey) } != true) {
            cancelDwell(); anchor = target; anchorKey = targetKey
        }
        if (dwell.update(true, now, receivedAt)) {
            selectedPoint = target; selectedKey = targetKey
            cancelDwell()
            return listOf(EyeAction.Click(target))
        }
        return emptyList()
    }

    companion object {
        // Stable fixation is the fallback when no accessible button/field is available.
        const val FIXATION_RADIUS = 0.04
    }
}
