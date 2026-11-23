package dev.gazedot

import kotlin.math.*

/** Reject isolated spikes; retain the pre-v33 fixation response without inventing 2D corners. */
class GazeStabilizer {
    data class Result(val point: Pair<Double, Double>, val confirmed: Boolean)
    private var point: Pair<Double, Double>? = null
    private var at: Long? = null
    private var jump: Pair<Double, Double>? = null
    private val window = ArrayDeque<Pair<Double, Double>>()
    fun reset() { point=null; at=null; jump=null; window.clear() }
    private fun distance(a:Pair<Double,Double>,b:Pair<Double,Double>)=hypot(a.first-b.first,a.second-b.second)
    fun update(raw: Pair<Double, Double>, time: Long): Result {
        val old=point
        if(old==null) { point=raw;at=time;window.add(raw);return Result(raw,true) }
        if(distance(raw,old) > .15) {
            val candidate=jump
            if(candidate==null || distance(candidate,raw) > .08) {
                jump=raw
                return Result(old,false)
            }
            window.clear()
        }
        jump=null
        window.addLast(raw)
        while(window.size>3) window.removeFirst()
        // Pick a real 2D sample, not independent x/y medians that invent a corner.
        val representative=window.minBy { candidate -> window.sumOf { distance(candidate,it) } }
        val target=representative
        val dt=((time-(at?:time)).coerceIn(1,250)).toDouble()
        val delta=distance(target,old)
        val tau=if(delta<.06) 220.0 else 65.0
        val alpha=1-exp(-dt/tau)
        val next=if(delta<.003) old else (old.first+alpha*(target.first-old.first) to old.second+alpha*(target.second-old.second))
        point=next;at=time
        return Result(next,true)
    }
}
