package dev.gazedot

import java.text.Normalizer

object TextEntryMetrics {
    fun normalized(s:String)=Normalizer.normalize(s,Normalizer.Form.NFC).trim()
    fun distance(a:String,b:String):Int {
        val x=normalized(a).codePoints().toArray();val y=normalized(b).codePoints().toArray()
        var prev=IntArray(y.size+1){it}
        for(i in x.indices){val next=IntArray(y.size+1);next[0]=i+1
            for(j in y.indices) next[j+1]=minOf(next[j]+1,prev[j+1]+1,prev[j]+if(x[i]==y[j])0 else 1)
            prev=next
        }
        return prev.last()
    }
    fun cer(target:String,actual:String)=distance(target,actual).toDouble()/normalized(target).codePointCount(0,normalized(target).length).coerceAtLeast(1)
}

/** Evaluation-only observer; no events are retained when the evaluation screen is inactive. */
object TextEntryProbe {
    var listener:((String)->Unit)?=null
    fun emit(event:String){listener?.invoke(event)}
}
