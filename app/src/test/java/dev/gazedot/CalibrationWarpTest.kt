package dev.gazedot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CalibrationWarpTest {
    private val targets=ScreenCalibration(0).targets
    private fun compressed(x: Double,y: Double)=.5+(x-.5)*(1-.35*((y-.5)/.42).coerceIn(0.0,1.0)) to y

    @Test fun lowerCornersReachTargetsAndExtrapolationCanReachPhoneBottom() {
        val source=targets.map { compressed(it.first,it.second) }
        val warp=CalibrationWarp.fit(source,targets)!!
        for (i in targets.indices) {
            val p=warp.map(source[i])
            assertEquals(targets[i].first,p.first,1e-8)
            assertEquals(targets[i].second,p.second,1e-8)
        }
        assertEquals(1.0,warp.map(.5 to 1.0).second,1e-8)
        // No jump at the boundary or where the two lower triangles meet.
        for (i in 0..1000) {
            val x=i/1000.0
            val a=warp.map(x to .92);val b=warp.map(x+1e-6 to .920001)
            assertTrue(hypot(a.first-b.first,a.second-b.second)<1e-4)
        }
    }

    @Test fun foldedOrExcessivelyAmplifyingCalibrationIsRejected() {
        assertNull(CalibrationWarp.fit(targets.map { .5+(it.first-.5)*.1 to it.second },targets))
        assertNull(CalibrationWarp.fit(targets.toMutableList().also { it[0]=1.5 to 1.5 },targets))
    }

    @Test fun heldOutFramesImproveWithoutChangingPerfectAffineMapping() {
        fun feature(x: Double,y: Double)=EyeFeatures((x-.5)*.3,(y-.5)*.2,.5,.5,.3)
        val groups=targets.map { (x,y) -> CalibrationFixation(List(24) { i ->
            val p=compressed(x,y);feature(p.first+.0001*sin(i.toDouble()),p.second)
        },x,y) }
        val base=GazeModel.fit(groups.map { Anchor(medianFeatures(it.samples.take(16)),it.x,it.y) })!!
        val selected=CalibrationMapping.fit(groups)!!
        fun error(model: GazeModel)=groups.flatMap { g -> g.samples.drop(20).map {
            val p=model.predictBase(it)!!;hypot(p.first-g.x,p.second-g.y)
        } }.average()
        assertTrue(error(selected)<error(base)*.4)
        val a=selected.predictBase(feature(.5,1.0))!!
        assertTrue(a.second>.98)
        val copy=selected.withScreenCorrection(.01,-.02)!!.predictForFiltering(feature(.5,1.0))!!
        assertEquals(a.first+.01,copy.first,1e-9);assertEquals(a.second-.02,copy.second,1e-9)
    }
}
