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
                val points = group.samples.subList(16, 20).map { f ->
                    model.predictBase(f) ?: return Double.POSITIVE_INFINITY
                }
                val mx=points.map { it.first }.average(); val my=points.map { it.second }.average()
                val variance=points.map { (it.first-mx).pow(2)+(it.second-my).pow(2) }.average()
                // Score position error AND within-target amplification, before any display filter.
                (mx-group.x).pow(2)+(my-group.y).pow(2)+2*variance
            }
            return errors.average() + .25 * errors.max()
        }
        fun debias(model: GazeModel): GazeModel {
            val residuals=groups.map { g ->
                val points=g.samples.subList(16,20).map { model.predictBase(it)!! }
                g.x-points.map { it.first }.average() to g.y-points.map { it.second }.average()
            }
            fun median(values: List<Double>)=values.sorted()[values.size/2]
            // A robust common intercept from known targets, not a hard-coded rightward shift.
            val dx=median(residuals.map { it.first }).coerceIn(-.15,.15)
            val dy=median(residuals.map { it.second }).coerceIn(-.15,.15)
            return model.withMappingOffset(dx,dy)
        }
        val candidates = listOf(false,true).flatMap { axisAligned ->
            listOf(.006, .03, .1, .3).mapNotNull { GazeModel.fit(training, it, axisAligned) }
        }.flatMap { model -> listOf(.5, .25, .75, 0.0, 1.0).map(model::withEyeWeight) }
        val pool=(listOfNotNull(baseline)+candidates).flatMap { listOf(it,debias(it)) }
        val best = pool.minByOrNull(::score) ?: return baseline
        return if (baseline == null || score(best) < .95 * score(baseline)) best else baseline
    }
}
