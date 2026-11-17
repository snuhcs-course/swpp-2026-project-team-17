package dev.gazedot

/** Only bilateral closure after a fresh OPEN sample can navigate or confirm. */
class BlinkRemote(private val count:Int) {
    init { require(count>0) }
    var index=0; private set
    private var last=0L
    private var armed=false
    private var closedAt:Long?=null
    fun update(eye:EyeState,at:Long,now:Long):Boolean {
        if(at<=last)return false
        if(now-at !in 0..500 || (last>0 && at-last>400)) { armed=false;closedAt=null }
        last=at
        if(now-at !in 0..500)return false
        when(eye) {
            EyeState.OPEN -> {
                val duration=closedAt?.let{at-it};closedAt=null;armed=true
                if(duration!=null) {
                    if(duration in 1000..2500)return true
                    if(duration in 80..600)index=(index+1)%count
                }
            }
            EyeState.CLOSED -> if(armed && closedAt==null)closedAt=at
            else -> { armed=false;closedAt=null }
        }
        return false
    }
}
