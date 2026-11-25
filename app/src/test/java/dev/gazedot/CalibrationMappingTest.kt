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
