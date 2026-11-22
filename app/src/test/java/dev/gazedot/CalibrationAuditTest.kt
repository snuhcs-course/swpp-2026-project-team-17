package dev.gazedot
import org.junit.Assert.*
import org.junit.Test
class CalibrationAuditTest {
    private val targets=listOf(.5 to .5,.18 to .18,.82 to .18,.82 to .82,.18 to .82)
    private fun groups(noise:(Int)->Double)=targets.map{(x,y)->
        CalibrationFixation((0 until 22).map { i -> EyeFeatures(x*6-3+noise(i),-y*12,.5,.5,.3,source=GazeSource.ITRACKER) },x,y)
    }
    @Test fun stableSamplesPassWithAnUnseenTimeBlock() {
        val r=CalibrationAudit.fit(groups{if(it%2==0) .01 else -.01})
        assertNotNull(r.report,r.model)
        assertEquals(1.0/6,r.model!!.mappingGain,.02)
    }
    @Test fun medianOnlyFitCanPassWhileWithinTargetNoiseFails() {
        val g=groups{when(it%3){0->-2.0;1->2.0;else->0.0}}
        assertNotNull(GazeModel.fit(g.map{Anchor(medianFeatures(it.samples),it.x,it.y)}))
        assertNull(CalibrationAudit.fit(g).model)
    }
    @Test fun lateDriftIsNotHiddenByTrainingFit() {
        val r=CalibrationAudit.fit(groups{if(it>=14) 1.5 else 0.0})
        assertNull(r.model)
        assertTrue(r.report.contains("미학습"))
    }
    @Test fun insufficientDataDoesNotClaimSuccess() {
        assertNull(CalibrationAudit.fit(groups{0.0}.take(3)).model)
    }
    @Test fun traceDistinguishesMissingMappingFromMissingInput() {
        val rows=(1..20).map{GazeTrace.Row(it*50L,100,"OPEN",.1 to .2,null,null)}
        assertTrue(GazeTrace.report(rows,.5 to .5,0.0).contains("보정 좌표"))
        assertTrue(GazeTrace.report(emptyList(),.5 to .5,0.0).contains("표본 부족"))
    }
}
