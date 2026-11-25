package dev.gazedot
import org.junit.Assert.*
import org.junit.Test
class CalibrationCheckSampleTest {
 private fun sample(at:Long)=GazeSample(at,TrackingQuality.TRACKING,null,.8 to .7,.6 to .5,0f,emptyList())
 @Test fun biasUsesModelOutputNotLaggingDisplayedPoint() {
  assertEquals(.8 to .7,sample(1_000).calibrationCheckPoint(1_100,0))
 }
 @Test fun deliveredAfterSettleButCapturedBeforeSettleIsRejected() {
  assertNull(sample(850).calibrationCheckPoint(1_000,0))
 }
 @Test fun staleFutureUncertainAndInvalidPointsAreRejected() {
  assertNull(sample(1_000).calibrationCheckPoint(1_251,0))
  assertNull(sample(1_000).calibrationCheckPoint(999,0))
  assertNull(sample(1_000).copy(quality=TrackingQuality.UNCERTAIN).calibrationCheckPoint(1_000,0))
  assertNull(sample(1_000).copy(rawPoint=Double.NaN to .5).calibrationCheckPoint(1_000,0))
 }
}
