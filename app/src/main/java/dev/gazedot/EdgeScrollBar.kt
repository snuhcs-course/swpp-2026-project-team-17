package dev.gazedot

/** Thin visible strip with a wider gaze hit region. No motion survives invalid input. */
class EdgeScrollBar {
    var direction:ScrollDirection?=null; private set
    var ownsInput=false; private set
    var progress=0f; private set
    private var candidate:ScrollDirection?=null
    private var since=0L
    private var lastAt=0L
    private var lastInside=0L
    private var lastScroll=0L
    fun reset(){direction=null;candidate=null;ownsInput=false;progress=0f;lastAt=0;lastScroll=0}
    fun update(at:Long,now:Long,eye:EyeState,p:Pair<Double,Double>?):ScrollDirection? {
        if(at<=lastAt || now-at !in 0..500 || eye!=EyeState.OPEN || p==null) { reset();return null }
        if(lastAt>0 && at-lastAt>500)reset()
        lastAt=at
        val next=when {
            p.first in .1.. .9 && p.second in 0.0.. .06 -> ScrollDirection.UP
            p.first in .1.. .9 && p.second in .94.. 1.0 -> ScrollDirection.DOWN
            p.second in .1.. .9 && p.first in 0.0.. .06 -> ScrollDirection.LEFT
            p.second in .1.. .9 && p.first in .94.. 1.0 -> ScrollDirection.RIGHT
            else -> null // corners deliberately have no competing directions
        }
        ownsInput=next!=null
        if(next==null){candidate=null;progress=0f;if(now-lastInside>500)direction=null;return null}
        lastInside=now
        if(next!=candidate){candidate=next;since=now;direction=null;progress=0f;return null}
        val elapsed=now-since
        if(elapsed>=300)direction=next
        progress=((elapsed-300)/500f).coerceIn(0f,1f)
        if(elapsed>=800 && (lastScroll==0L || now-lastScroll>=1100)) {lastScroll=now;return next}
        return null
    }
}
