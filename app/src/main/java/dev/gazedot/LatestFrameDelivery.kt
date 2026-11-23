package dev.gazedot

/** Coalesce steady open-eye frames, preserving eye transitions and first reopening timestamps. */
internal class LatestFrameDelivery(private val post:(Runnable)->Unit, private val remove:(Runnable)->Unit) {
    private var pending:Runnable?=null
    private var preserveNext=false
    fun submit(coalesce:Boolean=true, block:()->Unit) {
        val replace=coalesce && !preserveNext
        if(replace) pending?.let(remove)
        val task=Runnable { block() }
        pending=if(replace) task else null
        preserveNext=!coalesce
        post(task)
    }
}
