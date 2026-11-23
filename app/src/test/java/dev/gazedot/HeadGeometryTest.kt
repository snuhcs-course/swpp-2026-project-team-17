package dev.gazedot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class HeadGeometryTest {
    private val neutral=HeadPose(0.0,0.0,0.0,.5,.5,.025)
    private fun frame(i:Int)=FaceFrame(i*70L,600,800,emptyList(),neutral,15.0,14.0,.3,.28,.18,.23,.02,.03,.45,.65)
    @Test fun measuresEachIrisAndEyeOpeningFromStableDistinctFrames() {
        val p=EyeProfile.fit((0..30).map(::frame))!!
        assertEquals(15.0,p.leftDiameter,0.0);assertEquals(14.0,p.rightDiameter,0.0)
        assertEquals(.18,p.leftOpen,0.0);assertEquals(.23,p.rightOpen,0.0)
        assertNull(EyeProfile.fit((0..30).map{frame(it).copy(leftBlink=.8)}))
        assertNull(EyeProfile.fit((0..30).map{frame(it).copy(leftDiameter=if(it%2==0)15.0 else 25.0)}))
        assertNull(EyeProfile.fit(List(30){frame(0)}))
    }
    @Test fun personalOpenBaselineSupportsDifferentEyelidShapesWithoutHidingWinks() {
        val p=EyeProfile(.08,.23,10.0,12.0,.3,.3)
        assertEquals(EyeState.OPEN,EyeObservations.personalizedState(.08,.23,.05,.05,p))
        assertEquals(EyeState.LEFT_WINK,EyeObservations.personalizedState(.02,.23,.8,.05,p))
        assertEquals(EyeState.RIGHT_WINK,EyeObservations.personalizedState(.08,.03,.05,.8,p))
    }
    @Test fun narrowOpenEyeCanReachLandmarkExtractorAfterPersonalCalibration() {
        val points=MutableList(478){com.google.mediapipe.tasks.components.containers.NormalizedLandmark.create(.5f,.5f,0f)}
        fun put(i:Int,x:Float,y:Float){points[i]=com.google.mediapipe.tasks.components.containers.NormalizedLandmark.create(x,y,0f)}
        put(33,.30f,.40f);put(133,.45f,.40f);put(468,.375f,.40f)
        put(159,.375f,.3955f);put(145,.375f,.4045f)
        put(362,.55f,.40f);put(263,.70f,.40f);put(473,.625f,.40f)
        put(386,.625f,.387f);put(374,.625f,.413f)
        val profile=EyeProfile(.23,.08,10.0,10.0,.3,.3)
        val result=EyeObservations.observe(points,600,800,profile=profile)
        assertEquals(EyeState.OPEN,result.state);assertNotNull(result.features)
        val withoutHead=EyeObservations.observe(points,600,800,hasBlinkEvidence=true,blinkScores=.02 to .03)
        assertEquals(EyeState.OPEN,withoutHead.state)
        assertNotNull(withoutHead.features)
        assertNull(withoutHead.features!!.head)
        val quick=GazeModel.quickStart().predict(withoutHead.features!!)!!
        assertEquals(.5,quick.second,.01)
    }
    @Test fun columnMajorRotationAndInvalidMatrices() {
        val m=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,-40f,1f)
        assertEquals(0.0,HeadPose.angles(m)!!.first,.0001);assertEquals(0.0,HeadPose.angles(m)!!.second,.0001);assertEquals(0.0,HeadPose.angles(m)!!.third,.0001)
        val a=Math.toRadians(10.0);m[0]=cos(a).toFloat();m[2]=-sin(a).toFloat();m[8]=sin(a).toFloat();m[10]=cos(a).toFloat()
        assertEquals(10.0,HeadPose.angles(m)!!.first,.001)
        assertNull(HeadPose.angles(FloatArray(16)))
        m[1]=Float.NaN;assertNull(HeadPose.angles(m))
    }
    private fun point(h:HeadPose,x:Double=.5,y:Double=.5)=MotionAnchor(h,
        x-h.yaw/150-(h.x-.5),y-h.pitch/120-ln(h.iris/neutral.iris)*.3,x,y)
    @Test fun headResidualGeneralizesToHeldOutTargetsAndRejectsExtrapolation() {
        val train=listOf(neutral,neutral.copy(yaw=10.0),neutral.copy(yaw=-10.0),neutral.copy(pitch=8.0),neutral.copy(pitch=-8.0),
            neutral.copy(x=.55),neutral.copy(x=.45),neutral.copy(y=.55),neutral.copy(y=.45),neutral.copy(roll=6.0),neutral.copy(roll=-6.0),neutral.copy(iris=.029),neutral.copy(iris=.022))
        val c=HeadCorrection.fit(train.map{point(it)})!!
        val validation=listOf(point(neutral.copy(yaw=8.0),.35,.35),point(neutral.copy(yaw=-8.0),.65,.65),
            point(neutral.copy(pitch=6.0),.65,.35),point(neutral.copy(pitch=-6.0),.35,.65))
        assertTrue(HeadCorrection.accept(c,validation))
        assertFalse(HeadCorrection.accept(c,validation.map{it.copy(rawX=it.targetX,rawY=it.targetY)}))
        assertNull(c.apply(neutral.copy(yaw=45.0),.5 to .5))
        assertNull(c.apply(neutral.copy(iris=0.0),.5 to .5))
        assertNull(HeadCorrection.fit(List(9){point(neutral)}))
        val base=GazeModel.fit(listOf(.15,.5,.85).flatMap{x->listOf(.15,.5,.85).map{y->
            Anchor(EyeFeatures((x-.5)*.3,(y-.5)*.2,.5,.5,.3,head=neutral),x,y)
        }})!!
        val h=neutral.copy(yaw=8.0)
        val f=EyeFeatures((.4-h.yaw/150-.5)*.3,(.6-.5)*.2,.5,.5,.3,head=h)
        val updated=base.withHeadCorrection(c)
        assertTrue(abs(updated.predict(f)!!.first-.4)<abs(base.predict(f)!!.first-.4))
        assertNull(updated.predict(f.copy(head=null)))
        assertSame(c,updated.withCorrection(ResidualCorrection(1.0,0.0,1.0,0.0)).headCorrection)
        assertNull(updated.withCorrection(ResidualCorrection(1.0,.01,1.0,0.0)).withHeadCorrection(c).correction)
    }
}
