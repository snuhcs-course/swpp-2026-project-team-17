package dev.gazedot

import kotlin.math.*

/** Accuracy against the instructed target, never against the prediction's own mean.
 * Coordinates are normalized independently on each axis; tolerance is in screen-width units.
 * Missing/stale predictions count as misses instead of disappearing from the denominator.
 */
internal object GazeAccuracy {
    data class Result(val samples: Int, val valid: Int, val hits: Int, val hitRate: Double,
        val p95ErrorInWidths: Double?, val meets95Percent: Boolean)

    fun evaluate(points: List<Pair<Double,Double>?>, target: Pair<Double,Double>,
        width: Int, height: Int): Result {
        require(width>0 && height>0 && target.first.isFinite() && target.second.isFinite())
        val errors=points.map { p ->
            if(p==null || !p.first.isFinite() || !p.second.isFinite()) Double.POSITIVE_INFINITY
            else hypot(p.first-target.first,(p.second-target.second)*height.toDouble()/width)
        }.sorted()
        val hits=errors.count { it<=.05+1e-12 }
        val rate=if(errors.isEmpty()) 0.0 else hits.toDouble()/errors.size
        val p95=errors.getOrNull(ceil(errors.size*.95).toInt()-1)?.takeIf(Double::isFinite)
        return Result(errors.size,errors.count(Double::isFinite),hits,rate,p95,errors.isNotEmpty() && rate>=.95)
    }
}
