package dev.gazedot

import kotlin.math.*

/** v14 display interpolation, normalized to its 66 ms frame interval.
 * Large uncorroborated samples can move the display but never authorize an action.
 * In particular, displacement from a lagging cursor must not freeze its range.
 */
class GazeStabilizer {
    data class Result(val point: Pair<Double, Double>, val confirmed: Boolean)
    private var point: Pair<Double, Double>? = null
    private var at: Long? = null
    private var previousRaw: Pair<Double, Double>? = null
    fun reset() { point=null; at=null; previousRaw=null }
    fun update(raw: Pair<Double, Double>, time: Long): Result {
        val old=point
        if(old==null) { point=raw; at=time; previousRaw=raw; return Result(raw,true) }
        val confirmed=previousRaw?.let { hypot(raw.first-it.first,raw.second-it.second)<=.15 } ?: false
        val dt=(time-(at?:time)).coerceIn(0,250).toDouble()
        val alpha=1-(1-.28).pow(dt/66.0)
        val next=old.first+alpha*(raw.first-old.first) to old.second+alpha*(raw.second-old.second)
        point=next; at=time; previousRaw=raw
        return Result(next,confirmed)
    }
}
