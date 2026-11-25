package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class CalibrationEdgeVisibilityTest {
    private val profile = EyeProfile(.22,.22,10.0,10.0,.3,.3)
    @Test fun narrowedLidsWithLowBlinkEvidenceRemainVisibleAtEdges() {
        assertEquals(EyeState.OPEN, EyeObservations.personalizedState(.12,.13,.05,.08,profile))
        assertEquals(EyeState.OPEN, EyeObservations.personalizedState(.13,.12,.08,.05,profile))
    }
    @Test fun actualBlinkAndWinkAreStillRejected() {
        assertEquals(EyeState.CLOSED, EyeObservations.personalizedState(.04,.04,.9,.9,profile))
        assertEquals(EyeState.LEFT_WINK, EyeObservations.personalizedState(.04,.22,.9,.05,profile))
        assertEquals(EyeState.RIGHT_WINK, EyeObservations.personalizedState(.22,.04,.05,.9,profile))
        assertEquals(EyeState.UNCERTAIN, EyeObservations.personalizedState(.12,.13,.4,.4,profile))
        assertEquals(EyeState.OPEN, EyeObservations.personalizedState(.12,.13,.05,.05,null))
        assertEquals(EyeState.UNCERTAIN, EyeObservations.personalizedState(.12,.13,.05,.05,profile,false))
    }
    @Test fun absentBlinkScoresDoNotPretendBothEyesAreClosed() {
        assertEquals(EyeState.OPEN, EyeObservations.personalizedState(.22,.22,1.0,1.0,null,false))
        assertEquals(EyeState.OPEN, EyeObservations.personalizedState(.08,.08,.05,.05,null,true))
        assertEquals(EyeState.CLOSED, EyeObservations.personalizedState(.02,.02,.9,.9,null,true))
    }
}
