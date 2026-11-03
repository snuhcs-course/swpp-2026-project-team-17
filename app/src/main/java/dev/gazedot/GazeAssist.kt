package dev.gazedot

import kotlin.math.hypot
import kotlin.math.max

internal data class AssistPoint(val x: Double, val y: Double)
internal data class AssistFocus(val points: List<AssistPoint>, val center: AssistPoint,
    val spread: Double, val shortSide: Double)
internal data class AssistTarget(val id: String, val left: Double, val top: Double,
    val right: Double, val bottom: Double) {
    fun distance(p: AssistPoint) = hypot(p.x - p.x.coerceIn(left, right), p.y - p.y.coerceIn(top, bottom))
}

/** Detects repeated unsuccessful fixation attempts in one area, not the user's cognitive intent.
 * All positions are full-display pixels. Thresholds scale with the display's shorter dimension.
 */
internal class GazeAssistDetector {
    private data class Frame(val at: Long, val receivedAt: Long, val p: AssistPoint, val progress: Float, val targetKey: String?)
    private val frames = ArrayDeque<Frame>()
    private var cooldownUntil = 0L

    fun reset(now: Long = 0, cooldownMs: Long = 0) {
        frames.clear()
        cooldownUntil = now + cooldownMs
    }

    fun recentFocus(now: Long, shortSide: Double): AssistFocus? {
        if (frames.lastOrNull()?.let { now - it.receivedAt in 0..MANUAL_FOCUS_AGE_MS } != true) return null
        return focus(shortSide)
    }

    fun update(p: AssistPoint?, sampleAt: Long, now: Long, shortSide: Double,
        dwellProgress: Float, interrupted: Boolean, targetKey: String? = null): AssistFocus? {
        if (interrupted) { reset(now, COOLDOWN_MS); return null }
        if (p == null || !p.x.isFinite() || !p.y.isFinite() || shortSide <= 0 ||
            now - sampleAt !in 0..TutorialDwell.MAX_SAMPLE_GAP_MS) {
            frames.clear(); return null
        }
        if (now < cooldownUntil) return null
        frames.lastOrNull()?.let {
            if (sampleAt <= it.at || sampleAt - it.at > TutorialDwell.MAX_SAMPLE_GAP_MS ||
                now - it.receivedAt > TutorialDwell.MAX_SAMPLE_GAP_MS) frames.clear()
        }
        frames.addLast(Frame(sampleAt, now, p, dwellProgress, targetKey))
        while (frames.size > 1 && now - frames.first().receivedAt > WINDOW_MS) frames.removeFirst()
        if (frames.size < MIN_SAMPLES || now - frames.first().receivedAt < MIN_OBSERVATION_MS) return null
        val focus = focus(shortSide) ?: return null
        // Broad exploration / a smooth glance across the screen must not open the panel.
        if (focus.spread > MAX_CLUSTER_RADIUS * shortSide) return null
        var anchor = frames.first().p
        var failures = 0
        var reversals = 0
        var previousStep: AssistPoint? = null
        var previousProgress = 0f
        var previousKey = frames.first().targetKey
        val visitedKeys = mutableSetOf(previousKey)
        var revisits = 0
        for (frame in frames) {
            val dx = frame.p.x - anchor.x; val dy = frame.p.y - anchor.y
            val moved = hypot(dx, dy) > InteractionEngine.FIXATION_RADIUS * shortSide
            val changedTarget = frame.targetKey != previousKey
            if (changedTarget && !visitedKeys.add(frame.targetKey)) revisits++
            if (moved || changedTarget || (previousProgress > 0f && frame.progress < previousProgress)) {
                failures++
                anchor = frame.p
            }
            previousProgress = frame.progress
            previousKey = frame.targetKey
        }
        for ((a, b) in frames.zipWithNext()) {
            val step = AssistPoint(b.p.x - a.p.x, b.p.y - a.p.y)
            if (hypot(step.x, step.y) < SIGNIFICANT_STEP * shortSide) continue
            previousStep?.let { if (it.x * step.x + it.y * step.y < 0) reversals++ }
            previousStep = step
        }
        // Tiny jitter at a button boundary can reset dwell every frame, before progress even rises.
        val boundaryOscillation = revisits >= MIN_REVERSALS
        if (failures < MIN_FAILURES || (!boundaryOscillation &&
                (reversals < MIN_REVERSALS || focus.spread < MIN_SPREAD * shortSide))) return null
        reset(now, COOLDOWN_MS)
        return focus
    }

    private fun focus(shortSide: Double): AssistFocus? {
        if (frames.isEmpty() || shortSide <= 0) return null
        val points = frames.map { it.p }
        fun median(values: List<Double>) = values.sorted().let { it[it.size / 2] }
        val center = AssistPoint(median(points.map { it.x }), median(points.map { it.y }))
        val distances = points.map { hypot(it.x - center.x, it.y - center.y) }.sorted()
        // A small number of outliers must not move the proposed target area across the screen.
        return AssistFocus(points, center, distances[((distances.size - 1) * .8).toInt()], shortSide)
    }

    companion object {
        const val WINDOW_MS = 1_800L
        const val MIN_OBSERVATION_MS = 1_600L
        const val COOLDOWN_MS = 4_000L
        const val MANUAL_FOCUS_AGE_MS = 3_000L
        const val MIN_SAMPLES = 12
        const val MIN_FAILURES = 3
        const val MIN_REVERSALS = 3
        const val MAX_CLUSTER_RADIUS = .16
        const val MIN_SPREAD = .02
        const val SIGNIFICANT_STEP = .012
    }
}

/** Rank actual button rectangles by robust distance to recent gaze, never by tree order. */
internal object NearbyGazeCandidates {
    fun rank(focus: AssistFocus, targets: List<AssistTarget>): List<String> {
        val radius = (BASE_RADIUS * focus.shortSide + focus.spread).coerceAtMost(MAX_RADIUS * focus.shortSide)
        return targets.filter { it.right > it.left && it.bottom > it.top }
            .distinctBy { it.id }.mapNotNull { target ->
                val centerDistance = target.distance(focus.center)
                val distances = focus.points.map(target::distance).sorted()
                if (distances.isEmpty() || centerDistance > radius ||
                    distances.count { it <= radius } < max(1, (distances.size * .6).toInt())) null
                else {
                    val robustDistance = distances.take(max(1, (distances.size * .8).toInt())).average()
                    target.id to (.6 * robustDistance + .4 * centerDistance)
                }
            }.sortedWith(compareBy<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(3).map { it.first }
    }
    const val BASE_RADIUS = .12
    const val MAX_RADIUS = .24
}
