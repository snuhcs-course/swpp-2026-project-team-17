package dev.gazedot

import kotlin.math.*

internal data class RetargetingResidual(val fit: Pair<Double,Double>, val check: Pair<Double,Double>)
internal object RetargetingOffset {
    fun choose(rows: List<RetargetingResidual>): Pair<Double,Double>? {
        if (rows.size < 3 || rows.any { r ->
            !listOf(r.fit.first,r.fit.second,r.check.first,r.check.second).all(Double::isFinite)
        }) return null
        fun squared(p: Pair<Double,Double>)=p.first*p.first+p.second*p.second
        fun median(values: List<Double>)=values.sorted()[values.size/2]
        val dx=-median(rows.map { it.fit.first })
        val dy=-median(rows.map { it.fit.second })
        val before=rows.map { squared(it.check) }
        val baseline=before.average()
        val candidates=listOf(1.0,.5,.25).map { dx*it to dy*it }
        return candidates.filter { offset ->
            val after=rows.map { squared(it.check.first+offset.first to it.check.second+offset.second) }
            // Independent later frames must improve overall error without sacrificing a target.
            after.average() < baseline*.95 && after.indices.all { after[it] <= before[it]+1e-8 }
        }.minByOrNull { offset -> rows.map {
            squared(it.check.first+offset.first to it.check.second+offset.second)
        }.average() }
    }
}
