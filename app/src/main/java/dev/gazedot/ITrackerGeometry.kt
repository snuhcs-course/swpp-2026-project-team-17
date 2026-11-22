package dev.gazedot

import kotlin.math.*

data class CropBox(val x:Double,val y:Double,val width:Double,val height:Double)

/** Image coordinates are upright and unmirrored; left means the person's anatomical left. */
object ITrackerGeometry {
    fun square(cx:Double,cy:Double,side:Double)=CropBox(cx-side/2,cy-side/2,side,side)
    fun usable(b:CropBox,w:Int,h:Int):Boolean =
        listOf(b.x,b.y,b.width,b.height).all{it.isFinite()} && b.width>=12 && b.height>=12 &&
        b.x>=-w*.1 && b.y>=-h*.1 && b.x+b.width<=w*1.1 && b.y+b.height<=h*1.1

    /** Official faceGridFromFaceRect.m: rounded rectangle, clamped bounds, row-major 25x25. */
    fun grid(b:CropBox,w:Int,h:Int):FloatArray {
        require(w>0 && h>0 && usable(b,w,h))
        fun rnd(v:Double)=floor(v+.5).toInt()
        val x=rnd(b.x*25/w);val y=rnd(b.y*25/h)
        val right=(x+rnd(b.width*25/w)-1).coerceIn(0,24)
        val bottom=(y+rnd(b.height*25/h)-1).coerceIn(0,24)
        val out=FloatArray(625)
        for(j in y.coerceIn(0,24)..bottom)for(i in x.coerceIn(0,24)..right)out[j*25+i]=1f
        return out
    }
}
