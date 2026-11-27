package dev.gazedot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Controlled noise/outlier tests. These are not a claim of real-user gaze accuracy. */
class GazeStabilizerQualityTest {
    @Test fun isolatedDetectorSpikesDoNotBreak95PercentFixationCriterion() {
        for (target in listOf(.5 to .5,.03 to .03,.97 to .97,.03 to .97,.97 to .03)) {
            for (interval in listOf(33L,66L)) {
                val filter=GazeStabilizer();filter.update(target,0)
                val points=(1..150).map { i ->
                    val noise=.02*sin(i*1.7)
                    val spike=if(i%15==0) .35 else 0.0
                    filter.update(target.first+noise+spike to target.second-noise-spike,i*interval).point
                }
                val result=GazeAccuracy.evaluate(points,target,1080,2340)
                assertTrue("synthetic fixture target=$target interval=$interval rate=${result.hitRate}",result.meets95Percent)
            }
        }
    }

    @Test fun rejectionDoesNotTradeAllStabilityForSlowIntentionalMovement() {
        for(interval in listOf(33L,66L,100L)) {
            val filter=GazeStabilizer();filter.update(.1 to .1,0)
            var p=.1 to .1
            for(i in 1..(800/interval).toInt()) p=filter.update(.9 to .9,i*interval).point
            assertTrue(hypot(p.first-.9,p.second-.9)<.04)
        }
    }
}
