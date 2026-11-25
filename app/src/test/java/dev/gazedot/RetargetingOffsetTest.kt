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
    @Test fun fifteenPercentBiasIsNotTruncatedInEitherAxisOrDirection() {
        for (x in listOf(-.15,.15)) for (y in listOf(-.12,.12)) {
            val result=RetargetingOffset.choose(List(5) { RetargetingResidual(x to y,x to y) })!!
            assertEquals(-x,result.first,1e-9);assertEquals(-y,result.second,1e-9)
        }
    }
    @Test fun largeFitOffsetIsReducedWhenValidationOnlySupportsHalf() {
        val result=RetargetingOffset.choose(List(5) { RetargetingResidual(-.3 to .0,-.15 to .0) })!!
        assertEquals(.15,result.first,1e-9)
    }
    @Test fun largeCandidateCannotWorsenAnotherTarget() {
        val rows=List(4) { RetargetingResidual(-.2 to .0,-.2 to .0) } +
            RetargetingResidual(.03 to .0,.03 to .0)
        assertNull(RetargetingOffset.choose(rows))
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
    @Test fun validatedLargeCorrectionAndInvalidInputsAreHandled() {
        val p=RetargetingOffset.choose(List(5){RetargetingResidual(-.2 to .0,-.2 to .0)})!!
        assertEquals(.2,p.first,1e-9)
        assertNull(RetargetingOffset.choose(List(2){RetargetingResidual(.1 to .0,.1 to .0)}))
        assertNull(RetargetingOffset.choose(List(5){RetargetingResidual(Double.NaN to .0,.1 to .0)}))
    }
}
