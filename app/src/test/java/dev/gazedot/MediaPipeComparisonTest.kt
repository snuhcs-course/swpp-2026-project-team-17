package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MediaPipeComparisonTest {
    private fun landmarks(scale:Double=1.0,angle:Double=0.0,tx:Double=0.0):List<NormalizedLandmark> {
        val p=MutableList(478){NormalizedLandmark.create(.5f,.5f,0f)}
        fun put(i:Int,x:Double,y:Double) {
            val dx=(x-.5)*scale;val dy=(y-.5)*scale
            p[i]=NormalizedLandmark.create((.5+dx*cos(angle)-dy*sin(angle)+tx).toFloat(),
                (.5+dx*sin(angle)+dy*cos(angle)).toFloat(),0f)
        }
        put(33,.25,.4);put(133,.40,.4);put(468,.34,.41)
        put(159,.325,.38);put(145,.325,.42)
        put(362,.60,.4);put(263,.75,.4);put(473,.69,.41)
        put(386,.675,.38);put(374,.675,.42)
        put(1,.5,.6)
        return p
    }
    @Test fun eyeLocalCoordinatesIgnoreTranslationScaleAndInPlaneRotation() {
        val base=LandmarkFeatures.extract(landmarks(),1000,1000)!!
        for(p in listOf(landmarks(tx=.08),landmarks(scale=.8),landmarks(angle=.2))) {
            val f=LandmarkFeatures.extract(p,1000,1000)!!
            assertEquals(base.leftX,f.leftX,1e-5);assertEquals(base.leftY,f.leftY,1e-5)
            assertEquals(base.rightX,f.rightX,1e-5);assertEquals(base.rightY,f.rightY,1e-5)
        }
    }
    private fun groups()=listOf(.5 to .5,.18 to .18,.82 to .18,.82 to .82,.18 to .82).map { (x,y) ->
        CalibrationFixation((0 until 22).map { i ->
            val n=if(i%2==0) .0002 else -.0002
            val lx=(x-.5)*.24+.02+n; val rx=(x-.5)*.32-.03+n
            val ly=(y-.5)*.18+n; val ry=(y-.5)*.22+n
            EyeFeatures((lx+rx)/2,(ly+ry)/2,.5,.5,.3,lx,ly,rx,ry)
        },x,y)
    }
    @Test fun separateEyeMappingsPassAuditAndPredictUnseenTarget() {
        val audit=CalibrationAudit.fit(groups());assertNotNull(audit.report,audit.model)
        val x=.38;val y=.66
        val lx=(x-.5)*.24+.02;val rx=(x-.5)*.32-.03
        val ly=(y-.5)*.18;val ry=(y-.5)*.22
        val prediction=audit.model!!.predict(EyeFeatures((lx+rx)/2,(ly+ry)/2,.5,.5,.3,lx,ly,rx,ry))!!
        assertEquals(x,prediction.first,.01);assertEquals(y,prediction.second,.01)
    }
    @Test fun largePoseChangesRejectAuditWithoutCrashing() {
        val data=groups().mapIndexed { i,g -> g.copy(samples=g.samples.map { it.copy(faceX=if(i==0) .9 else .5) }) }
        assertNull(CalibrationAudit.fit(data).model)
    }
    @Test fun winkNeverSuppliesDistortedIrisToCalibration() {
        val p=landmarks().toMutableList()
        p[386]=p[374]
        val sample=EyeObservations.observe(p,1000,1000)
        assertEquals(EyeState.LEFT_WINK,sample.state)
        assertNull(sample.features)
    }
    @Test fun ninePointMedianCalibrationAndDisplayReachAllScreenEdges() {
        fun features(x:Double,y:Double,n:Double=0.0):EyeFeatures {
            val lx=(x-.5)*.24+.02+n;val rx=(x-.5)*.32-.03+n
            val ly=(y-.5)*.18+n;val ry=(y-.5)*.22+n
            return EyeFeatures((lx+rx)/2,(ly+ry)/2,.5,.5,.3,lx,ly,rx,ry)
        }
        val groups=listOf(.08,.5,.92).flatMap { x -> listOf(.08,.5,.92).map { y ->
            CalibrationFixation((0 until 22).map { i -> features(x,y,if(i%2==0) .0002 else -.0002) },x,y)
        } }
        val audit=CalibrationAudit.fit(groups)
        assertNotNull(audit.report,audit.model)
        for(interval in listOf(33L,66L)) {
            val pipeline=GazePipeline(); var time=1_000L
            for((x,y) in listOf(.01 to .01,.99 to .01,.99 to .99,.01 to .99,.5 to .5)) {
                var sample:GazeSample?=null
                repeat((1_500/interval).toInt()) {
                    time+=interval
                    sample=pipeline.process(time,time,EyeObservation(EyeState.OPEN,features(x,y),.22,.22),audit.model,false)
                }
                assertEquals(TrackingQuality.TRACKING,sample!!.quality)
                assertEquals(x,sample!!.point!!.first,.015)
                assertEquals(y,sample!!.point!!.second,.015)
            }
        }
    }
}
