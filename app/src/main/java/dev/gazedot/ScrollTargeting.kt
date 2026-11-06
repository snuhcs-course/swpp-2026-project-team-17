package dev.gazedot

/** Screen-pixel bounds of a visible scroll container. Independent of Android node ownership. */
internal data class ScrollArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Double, y: Double) = x >= left && x < right && y >= top && y < bottom
    fun distanceSquared(x: Double, y: Double): Double {
        val dx = x - x.coerceIn(left.toDouble(), right.toDouble())
        val dy = y - y.coerceIn(top.toDouble(), bottom.toDouble())
        return dx * dx + dy * dy
    }
}

internal data class ScrollTarget(val area: ScrollArea, val directions: Set<ScrollDirection>)

internal object ScrollTargeting {
    /** Never substitute a vertical action for a horizontal request in nested containers. */
    fun choose(targets: List<ScrollTarget>, direction: ScrollDirection,
        gaze: Pair<Double, Double>): Int? = targets.indices.filter {
        direction in targets[it].directions && targets[it].area.width > 0 && targets[it].area.height > 0
    }.minWithOrNull(compareBy<Int>(
        { if (targets[it].area.contains(gaze.first, gaze.second)) 0 else 1 },
        { targets[it].area.distanceSquared(gaze.first, gaze.second) },
        { targets[it].area.width.toLong() * targets[it].area.height }))

    fun swipe(direction: ScrollDirection, area: ScrollArea): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        val (from, to) = ScrollSettings.swipe(direction, area.width, area.height)
        return (from.first + area.left to from.second + area.top) to
            (to.first + area.left to to.second + area.top)
    }
}
