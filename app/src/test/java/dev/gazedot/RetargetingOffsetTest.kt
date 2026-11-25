package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class RetargetingOffsetTest {
    @Test fun consistentBiasIsCorrectedInEitherDirection() {
        for (x in listOf(-.07,.07)) {
            val result=RetargetingOffset.choose(List(5) { RetargetingResidual(x to .03,x to .03) })!!
            assertEquals(-x,result.first,1e-9);assertEquals(-.03,result.second,1e-9)
        }
    }
    @Test fun cornerBiasMustNotMoveAnAlreadyAccurateCenter() {
        val rows=List(4){RetargetingResidual(-.08 to .0,-.08 to .0)}+
            RetargetingResidual(.0 to .0,.0 to .0)
        // Old average would move the accurate center right by 6.4% of screen width.
        assertNull(RetargetingOffset.choose(rows))
    }
    @Test fun changingDirectionBetweenFitAndCheckDoesNotApplyStaleOffset() {
        assertNull(RetargetingOffset.choose(List(5){RetargetingResidual(-.08 to .0,.04 to .0)}))
    }
    @Test fun correctionRemainsBoundedAndInvalidInputsAreRejected() {
        val p=RetargetingOffset.choose(List(5){RetargetingResidual(-.2 to .0,-.2 to .0)})!!
        assertEquals(.08,p.first,1e-9)
        assertNull(RetargetingOffset.choose(List(2){RetargetingResidual(.1 to .0,.1 to .0)}))
        assertNull(RetargetingOffset.choose(List(5){RetargetingResidual(Double.NaN to .0,.1 to .0)}))
    }
}
