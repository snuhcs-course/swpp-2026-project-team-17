package dev.gazedot

import kotlin.math.*

/** Confirm isolated jumps, then follow corroborated moves without adding another filter. */
class GazeStabilizer {
    data class Result(val point: Pair<Double, Double>, val confirmed: Boolean)
    private var point: Pair<Double, Double>? = null
    private var at: Long? = null
    private var previousRaw: Pair<Double, Double>? = null
    private var jump: Pair<Double, Double>? = null
    private var movingUntil = Long.MIN_VALUE
    private val window = ArrayDeque<Pair<Double, Double>>()
    fun reset() { point=null; at=null; previousRaw=null; jump=null; movingUntil=Long.MIN_VALUE; window.clear() }
    private fun distance(a:Pair<Double,Double>,b:Pair<Double,Double>)=hypot(a.first-b.first,a.second-b.second)
    fun update(raw: Pair<Double, Double>, time: Long): Result {
        val old=point
        if(old==null) { point=raw;at=time;previousRaw=raw;window.add(raw);return Result(raw,true) }
        val previous=previousRaw
        previousRaw=raw
        if(distance(raw,old) > .15) {
            val candidate=jump
            if(candidate==null || distance(candidate,raw) > .08) {
                jump=raw
                return Result(old,false)
            }
            window.clear()
            movingUntil=time+150
        } else if(previous!=null && distance(raw,previous)<=.025 && distance(raw,old)>=.035) {
            // A repeated new location is movement; alternating fixation noise is not.
            movingUntil=time+150
        }
        jump=null
        window.addLast(raw)
        while(window.size>3) window.removeFirst()
        // Pick a real 2D sample, not independent x/y medians that invent a corner.
        val representative=window.minBy { candidate -> window.sumOf { distance(candidate,it) } }
        val moving=time<=movingUntil && previous?.let { distance(raw,it)<=.08 }==true
        val target=if(moving) raw else representative
        val dt=((time-(at?:time)).coerceIn(1,250)).toDouble()
        val delta=distance(target,old)
        val tau=if(moving) 40.0 else 100.0
        val alpha=1-exp(-dt/tau)
        val next=if(delta<.003) old else (old.first+alpha*(target.first-old.first) to old.second+alpha*(target.second-old.second))
        point=next;at=time
        return Result(next,true)
    }
}
