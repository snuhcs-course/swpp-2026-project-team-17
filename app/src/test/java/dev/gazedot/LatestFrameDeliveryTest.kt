package dev.gazedot
import org.junit.Assert.*
import org.junit.Test
class LatestFrameDeliveryTest {
 @Test fun busyUiReceivesOnlyNewestPendingFrame() {
  val queue=mutableListOf<Runnable>();val seen=mutableListOf<Int>()
  val d=LatestFrameDelivery({queue.add(it);Unit},{queue.remove(it);Unit})
  repeat(100){i->d.submit{seen.add(i)}}
  assertEquals(1,queue.size);queue.removeAt(0).run();assertEquals(listOf(99),seen)
 }
 @Test fun nextFrameAfterDeliveryStillRuns() {
  val queue=mutableListOf<Runnable>();val seen=mutableListOf<Int>()
  val d=LatestFrameDelivery({queue.add(it);Unit},{queue.remove(it);Unit})
  d.submit{seen.add(1)};queue.removeAt(0).run()
  d.submit{seen.add(2)};queue.removeAt(0).run()
  assertEquals(listOf(1,2),seen)
 }
 @Test fun blinkAndFirstReopeningAreNotDroppedOrRetimed() {
  val queue=mutableListOf<Runnable>();val seen=mutableListOf<Int>()
  val d=LatestFrameDelivery({queue.add(it);Unit},{queue.remove(it);Unit})
  d.submit{seen.add(1)}
  d.submit(false){seen.add(2)} // closed eye, original capture time retained
  d.submit{seen.add(3)} // first reopened frame, also retained
  d.submit{seen.add(4)}
  d.submit{seen.add(5)}
  queue.toList().forEach{it.run()}
  assertEquals(listOf(1,2,3,5),seen)
 }
}
