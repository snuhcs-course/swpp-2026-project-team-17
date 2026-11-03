package dev.gazedot

/** Screen-pixel geometry. This recognises candidate regions, not cognitive intent. */
internal data class EasyPanelTarget(
    val id: String,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val margin: Double = 0.0,
) {
    fun contains(x: Double, y: Double, expanded: Boolean = false): Boolean {
        val padding = if (expanded) margin else 0.0
        return right > left && bottom > top && x >= left - padding && x < right + padding &&
            y >= top - padding && y < bottom + padding
    }
}

/** One second of fresh input on the same candidate set; ambiguity never executes an action. */
internal class EasyPanelSelection {
    private val dwell = TutorialDwell()
    private var candidates = emptyList<String>()
    private var latched = emptyList<EasyPanelTarget>()
    private var lastInputAt: Long? = null
    private var lastSampleAt: Long? = null
    val progress get() = dwell.progress
    val waitingForExit get() = latched.isNotEmpty()
    val highlighted get() = candidates

    fun reset(clearLatch: Boolean = true) {
        dwell.reset()
        candidates = emptyList()
        lastInputAt = null
        lastSampleAt = null
        if (clearLatch) latched = emptyList()
    }

    fun holdUntilExit(target: EasyPanelTarget) {
        reset()
        latched = listOf(target)
    }

    fun expire(now: Long) {
        if (lastInputAt?.let { now - it > TutorialDwell.MAX_SAMPLE_GAP_MS } == true) reset(clearLatch = false)
    }

    fun update(
        x: Double?, y: Double?, sampleAt: Long, now: Long,
        targets: List<EasyPanelTarget>,
    ): List<String>? {
        if (x == null || y == null || !x.isFinite() || !y.isFinite() || now - sampleAt !in 0..250) {
            reset(clearLatch = false)
            return null
        }
        if (lastSampleAt?.let { sampleAt <= it } == true) {
            reset(clearLatch = false)
            return null
        }
        lastInputAt = now
        lastSampleAt = sampleAt
        if (latched.isNotEmpty()) {
            // Closing the eyes or losing tracking cannot release the post-selection latch.
            if (latched.none { it.contains(x, y, expanded = true) }) latched = emptyList()
            reset(clearLatch = false)
            return null
        }
        val direct = targets.filter { it.contains(x, y) }
        val nearby = targets.filter { it.contains(x, y, expanded = true) }
        val matches = when {
            direct.any { it.margin == 0.0 } -> direct.filter { it.margin == 0.0 }.take(1)
            nearby.size in 1..2 -> nearby
            else -> emptyList()
        }
        val ids = matches.map { it.id }.sorted()
        if (ids != candidates) { dwell.reset(); candidates = ids }
        if (!dwell.update(ids.isNotEmpty(), sampleAt, now)) return null
        latched = matches
        reset(clearLatch = false)
        return ids
    }
}
