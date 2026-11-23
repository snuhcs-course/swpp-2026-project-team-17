package dev.gazedot
import org.junit.Assert.*
import org.junit.Test
class NaturalEyeBaselineTest {
 private fun frame(i:Int)=FaceFrame(i*70L,600,800,emptyList(),HeadPose(25.0,18.0,14.0,.25,.7,.015),
     4.0,4.0,.3,.3,.08,.23,.02,.03,.25,.35)
 @Test fun naturalOffCenterPoseAndSmallIrisDoNotRequireAlignment() {
  val b=NaturalEyeBaseline(); var p:EyeProfile?=null
  for(i in 0..19) p=b.add(frame(i),true)
  assertNotNull(p)
  assertEquals(EyeState.OPEN,EyeObservations.personalizedState(.08,.23,.02,.03,p))
  assertEquals(EyeState.LEFT_WINK,EyeObservations.personalizedState(.02,.23,.9,.03,p))
  assertSame(p,b.add(frame(21).copy(leftOpen=.04,leftBlink=.9),true))
 }
 @Test fun missingScoresBlinksAndDuplicateFramesCannotCreateBaseline() {
  val b=NaturalEyeBaseline()
  for(i in 0..30) assertNull(b.add(frame(i),false))
  for(i in 31..60) assertNull(b.add(frame(i).copy(leftBlink=.8),true))
  repeat(30){assertNull(b.add(frame(61),true))}
 }
 @Test fun oldSamplesAndInvalidValuesDoNotAccumulate() {
  val b=NaturalEyeBaseline()
  for(i in 0..18) assertNull(b.add(frame(i),true))
  assertNull(b.add(frame(200),true))
  for(i in 201..230) assertNull(b.add(frame(i).copy(leftOpen=Double.NaN),true))
 }
}
