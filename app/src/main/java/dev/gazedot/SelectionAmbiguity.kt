package dev.gazedot

object SelectionAmbiguity {
    /** One distinct innermost target, safely inside its bounds, needs no confirmation. */
    fun clearHit(candidates:List<SelectionCandidate>,x:Double,y:Double):Boolean {
        val hits=candidates.filter{x>=it.left && x<=it.right && y>=it.top && y<=it.bottom}
        val inner=hits.filter{a -> hits.none{b -> b!==a && b.left>=a.left && b.top>=a.top && b.right<=a.right && b.bottom<=a.bottom &&
            (b.left>a.left || b.top>a.top || b.right<a.right || b.bottom<a.bottom)}}
        if(inner.size!=1)return false
        val c=inner.single();val dx=(c.right-c.left)*.1;val dy=(c.bottom-c.top)*.1
        return dx>0 && dy>0 && x>c.left+dx && x<c.right-dx && y>c.top+dy && y<c.bottom-dy
    }
}
