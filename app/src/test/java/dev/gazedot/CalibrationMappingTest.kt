package dev.gazedot

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class CalibrationMappingTest {
    private val targets = ScreenCalibration(0).targets
    private fun features(x: Double, y: Double, leftNoise: Double = 0.0) =
        EyeFeatures((x-.5)*.3,(y-.5)*.2,.5,.5,.3,
            leftX=(x-.5)*.3+leftNoise,leftY=(y-.5)*.2+leftNoise,
            rightX=(x-.5)*.3,rightY=(y-.5)*.2)

    @Test fun commonLeftwardOffsetIsLearnedFromKnownTargetsWithoutHardCodingDirection() {
        for (shift in listOf(-.024,.024)) {
            val groups=targets.map { (x,y) -> CalibrationFixation(List(24) { i ->
                val f=features(x,y)
                if(i<16) f else f.copy(x=f.x+shift,leftX=f.leftX+shift,rightX=f.rightX+shift)
            },x,y) }
            val old=GazeModel.fit(groups.map { Anchor(medianFeatures(it.samples.take(16)),it.x,it.y) })!!
            val corrected=CalibrationMapping.fit(groups)!!
            val center=features(.5,.5).let { it.copy(x=it.x+shift,leftX=it.leftX+shift,rightX=it.rightX+shift) }
            assertTrue(abs(old.predictBase(center)!!.first-.5)>.07)
            assertEquals(.5,corrected.predictBase(center)!!.first,.015)
            for (g in groups) {
                val p=corrected.predictBase(g.samples.last())!!
                assertEquals(g.x,p.first,.025);assertEquals(g.y,p.second,.025)
            }
        }
    }

    @Test fun axisAlignedMappingDoesNotTurnVerticalNoiseIntoHorizontalMotion() {
        val anchors=targets.map { (x,y) -> Anchor(features(x,y).let {
            it.copy(leftX=it.leftX+.012*(y-.5),rightX=it.rightX+.012*(y-.5))
        },x,y) }
        val full=GazeModel.fit(anchors)!!
        val aligned=GazeModel.fit(anchors,axisAligned=true)!!
        val center=features(.5,.5)
        val verticalNoise=center.copy(leftY=.01,rightY=.01,y=.01)
        assertTrue(abs(full.predictBase(center)!!.first-full.predictBase(verticalNoise)!!.first)>.001)
        assertEquals(aligned.predictBase(center)!!.first,aligned.predictBase(verticalNoise)!!.first,1e-12)
        assertTrue(aligned.predictBase(features(.92,.5))!!.first>.90)
        assertTrue(aligned.predictBase(features(.08,.5))!!.first<.10)
    }

    @Test fun mappingOffsetDoesNotMutateTheSourceOrDisappearDuringRetargeting() {
        val base=GazeModel.fit(targets.map { (x,y)->Anchor(features(x,y),x,y) })!!
        val f=features(.5,.5); val before=base.predictBase(f)!!
        val shifted=base.withMappingOffset(.08,-.03)
        assertEquals(before,base.predictBase(f))
        assertEquals(before.first+.08,shifted.predictBase(f)!!.first,1e-9)
        val retargeted=shifted.withScreenCorrection(-.02,.01)!!
        assertEquals(before.first+.06,retargeted.predictForFiltering(f)!!.first,1e-9)
        assertEquals(before.second-.02,retargeted.predictForFiltering(f)!!.second,1e-9)
    }

    @Test fun unstableEyeDoesNotShiftTheStableEyesMapping() {
        val groups=targets.map { (x,y) -> CalibrationFixation((0 until 24).map { i ->
            features(x,y,if(i<16) .012*sin(i*1.7) else .018+.004*sin(i*1.7))
        },x,y) }
        val baseline=GazeModel.fit(groups.map { Anchor(medianFeatures(it.samples.take(16)),it.x,it.y) })!!
        val selected=ScreenCalibration.fit(groups).model!!
        fun error(model:GazeModel)=groups.flatMap { g -> g.samples.subList(20,24).map {
            val p=model.predictBase(it)!!;hypot(p.first-g.x,p.second-g.y)
        } }.average()
        assertTrue(error(selected)<error(baseline)*.5)
        // Novel positions, not just the nine fitted points.
        for ((x,y) in listOf(.27 to .38,.72 to .69,.03 to .97)) {
            val p=selected.predictBase(features(x,y,.02))!!
            assertEquals(x,p.first,.02);assertEquals(y,p.second,.02)
        }
        val corrected=selected.withScreenCorrection(.02,-.01)!!
        val p=selected.predictBase(features(.3,.7,.02))!!
        val q=corrected.predictForFiltering(features(.3,.7,.02))!!
        assertEquals(p.first+.02,q.first,1e-9);assertEquals(p.second-.01,q.second,1e-9)
    }

    @Test fun finalAuditSamplesAreNeverUsedToChooseOrFitMapping() {
        val groups=targets.map { (x,y) -> CalibrationFixation(List(24){features(x,y)},x,y) }
        val corrupted=groups.map { g -> g.copy(samples=g.samples.mapIndexed { i,f ->
            if(i<20)f else features(g.x+.35,g.y+.35)
        }) }
        val a=CalibrationMapping.fit(groups)!!;val b=CalibrationMapping.fit(corrupted)!!
        assertEquals(a.predictBase(features(.3,.7)),b.predictBase(features(.3,.7)))
        assertNotNull(ScreenCalibration.fit(groups).model)
        assertTrue(ScreenCalibration.fit(corrupted).rms!! > .30)
    }

    @Test fun oneExtremeTrainingFrameCannotMoveTheMapping() {
        val groups=targets.map { (x,y) -> CalibrationFixation(List(24){ i ->
            if(i==3)features(x,y,5.0) else features(x,y)
        },x,y) }
        val result=ScreenCalibration.fit(groups)
        assertNotNull(result.model)
        assertTrue(result.rms!!<.015)
    }
}
