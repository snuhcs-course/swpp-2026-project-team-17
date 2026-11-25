package dev.gazedot

import kotlin.math.*

/** Fit 0..15, select on 16..19, audit on 20..23. No runtime or temporal filter. */
internal object CalibrationMapping {
    fun fit(groups: List<CalibrationFixation>): GazeModel? {
        val baseline = GazeModel.fit(groups.map { Anchor(medianFeatures(it.samples.take(16)), it.x, it.y) })
        val training = groups.flatMap { group ->
            val frames = group.samples.take(16)
            fun bounded(value: Double, axis: (EyeFeatures) -> Double): Double {
                val values = frames.map(axis).sorted()
                val median = values[values.size / 2]
                val mad = values.map { abs(it - median) }.sorted()[values.size / 2]
                val limit = max(.002, 4.0 * 1.4826 * mad)
                return value.coerceIn(median - limit, median + limit)
            }
            frames.map { f ->
                val lx = bounded(f.leftX) { it.leftX }; val ly = bounded(f.leftY) { it.leftY }
                val rx = bounded(f.rightX) { it.rightX }; val ry = bounded(f.rightY) { it.rightY }
                Anchor(f.copy(x=(lx+rx)/2,y=(ly+ry)/2,leftX=lx,leftY=ly,rightX=rx,rightY=ry), group.x, group.y)
            }
        }
        fun score(model: GazeModel): Double {
            val errors = groups.map { group ->
                group.samples.subList(16, 20).map { f ->
                    val p = model.predictBase(f) ?: return Double.POSITIVE_INFINITY
                    (p.first-group.x).pow(2) + (p.second-group.y).pow(2)
                }.average()
            }
            // Every target has equal weight; do not improve the center at the expense of an edge.
            return errors.average() + .25 * errors.max()
        }
        val candidates = listOf(.006, .03, .1).mapNotNull { GazeModel.fit(training, it) }
            .flatMap { model -> listOf(.5, .25, .75, 0.0, 1.0).map(model::withEyeWeight) }
        val best = candidates.minByOrNull(::score) ?: return baseline
        // Keep the established mapping unless independent selection frames show a useful gain.
        return if (baseline == null || score(best) < .95 * score(baseline)) best else baseline
    }
}
