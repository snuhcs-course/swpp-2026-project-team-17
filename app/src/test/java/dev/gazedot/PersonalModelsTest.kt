package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class PersonalModelsTest {
    private fun anchor(x: Double,y: Double)=CorrectionAnchor(x*.8+.08,y*.85-.06,x,y)
    private val fit=listOf(anchor(.2,.2),anchor(.8,.2),anchor(.5,.5),anchor(.2,.8),anchor(.8,.8))
    private val validation=listOf(anchor(.35,.35),anchor(.65,.35),anchor(.35,.65),anchor(.65,.65))
    @Test fun correctionImprovesDistinctHeldOutTargets() {
        val model=ResidualCorrection.fit(fit)!!
        assertTrue(ResidualCorrection.accept(validation,model,null))
        assertTrue(ResidualCorrection.error(validation,model)<ResidualCorrection.error(validation,null))
    }
    @Test fun correctionRejectsCollapsedNonfiniteOrInsufficientTraining() {
        assertNull(ResidualCorrection.fit(fit.take(4)))
        assertNull(ResidualCorrection.fit(fit.map { it.copy(rawX=.5) }))
        assertNull(ResidualCorrection.fit(fit.map { it.copy(rawY=Double.NaN) }))
    }
    @Test fun worseCorrectionDoesNotReplaceExistingMapping() {
        val perfect=validation.map { CorrectionAnchor(it.targetX,it.targetY,it.targetX,it.targetY) }
        assertFalse(ResidualCorrection.accept(perfect,ResidualCorrection(1.0,.1,1.0,.1),null))
        assertFalse(ResidualCorrection.accept(validation.take(3),ResidualCorrection.fit(fit)!!,null))
    }
    @Test fun appliedCorrectionIsBounded() {
        val p=ResidualCorrection(1.5,.25,.65,-.25).apply(1.0,1.0)
        assertEquals(1.18,p.first,1e-9); assertEquals(.82,p.second,1e-9)
    }
    private val choices=listOf(SelectionCandidate("a",.3,.3,.4,.4),SelectionCandidate("b",.5,.3,.6,.4))
    @Test fun explicitConfirmationsChangeLearnedScoresAndPersist() {
        val model=PersonalTargetRanker()
        fun features()=choices.map { model.features("screen",it,.45,.35) }
        val before=model.probabilities(features())[1]
        repeat(15) { model.confirm("screen",choices,features(),1) }
        assertTrue(model.probabilities(features())[1]>before)
        assertEquals(15,model.confirmations)
        val loaded=PersonalTargetRanker(); assertTrue(loaded.restore(model.encode()))
        assertEquals(15,loaded.confirmations)
        assertArrayEquals(model.weights,loaded.weights,1e-12)
        assertTrue(loaded.probabilities(choices.map { loaded.features("screen",it,.45,.35) })[1]>.5)
    }
    @Test fun predictionDoesNotTrainAndMalformedRestoreIsAtomic() {
        val model=PersonalTargetRanker(); val before=model.encode()
        repeat(20) { model.probabilities(choices.map { model.features("screen",it,.45,.35) }) }
        assertEquals(before,model.encode())
        assertFalse(model.restore("1\n10\nNaN,0,0,0\n")); assertEquals(before,model.encode())
    }
    @Test fun softmaxIsFiniteAndNormalized() {
        val model=PersonalTargetRanker()
        val p=model.probabilities(choices.map { model.features("s",it,.4,.4) })
        assertEquals(1.0,p.sum(),1e-12); assertTrue(p.all { it.isFinite() && it in 0.0..1.0 })
    }
    @Test fun gradientUpdateImprovesConfirmedChoiceEvenWithFrozenFeatures() {
        val model=PersonalTargetRanker()
        val frozen=listOf(doubleArrayOf(.05,0.0,0.0,.1),doubleArrayOf(.15,0.0,0.0,.1))
        val before=model.probabilities(frozen)[1]
        model.confirm("s",choices,frozen,1)
        assertTrue(model.probabilities(frozen)[1]>before)
    }
    @Test fun modelRejectsInvalidLabelWithoutUpdating() {
        val model=PersonalTargetRanker(); val before=model.encode()
        assertTrue(runCatching { model.confirm("s",choices,choices.map { model.features("s",it,.5,.5) },-1) }.isFailure)
        assertEquals(before,model.encode())
    }
    @Test fun changedWindowTargetOrLabelInvalidatesConfirmation() {
        val a=AccessibleChoice(choices[0],"One")
        val frozen=ChoiceSnapshot("screen",1,listOf(a))
        assertTrue(frozen.stillValid(frozen,a))
        assertFalse(frozen.stillValid(frozen.copy(window=2),a))
        assertFalse(frozen.stillValid(frozen.copy(screen="other"),a))
        assertFalse(frozen.stillValid(frozen.copy(choices=listOf(a.copy(label="Delete"))),a))
        assertFalse(frozen.stillValid(frozen.copy(choices=listOf(a.copy(candidate=choices[0].copy(left=.2)))),a))
        assertFalse(frozen.stillValid(null,a))
    }
    @Test fun correctedPredictionRunsThroughExistingModelAndCanBeRemoved() {
        val anchors=listOf(.15,.5,.85).flatMap { x -> listOf(.15,.5,.85).map { y ->
            Anchor(EyeFeatures((x-.5)*.3,(y-.5)*.2,.5,.5,.25),x,y)
        } }
        val model=GazeModel.fit(anchors)!!; val eye=EyeFeatures(0.0,0.0,.5,.5,.25)
        val original=model.predict(eye)!!
        val corrected=model.withCorrection(ResidualCorrection(1.0,.05,1.0,-.04))
        assertEquals(original.first+.05,corrected.predict(eye)!!.first,1e-9)
        assertEquals(original.second-.04,corrected.predict(eye)!!.second,1e-9)
        assertEquals(original,corrected.withCorrection(null).predict(eye))
        assertEquals(corrected.predict(eye),corrected.predict(eye.copy(faceX=.9)))
        assertNull(GazeModel.fit(anchors)!!.correction)
    }
}
