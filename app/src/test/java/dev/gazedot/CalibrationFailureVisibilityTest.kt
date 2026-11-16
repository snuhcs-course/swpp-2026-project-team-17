package dev.gazedot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A calibration attempt that ends without a model used to show its failure message for one frame only: the next
 * processed frame replaced it with "눈 검출됨 · 9점 보정을 시작해 주세요", so a rejected fit looked like a completed
 * calibration with a dead pointer. The per-frame messages themselves are those of feature/home-screen.
 */
class CalibrationFailureVisibilityTest {
    private val failure = "보정 실패 · 시선 변화가 불충분하거나 오차가 큽니다. 기기를 고정하고 다시 시도하세요."

    @Before fun reset() { TrackerState.trackingStopped(); TrackerState.model = null }
    @After fun cleanUp() { TrackerState.trackingStopped(); TrackerState.model = null }

    @Test
    fun withoutAFailedCalibrationEveryFrameShowsTheBaseMessage() {
        val expected = mapOf(
            TrackingQuality.LOST to ("얼굴 검출 대기" to "얼굴 검출 대기"),
            TrackingQuality.CLOSED to ("눈 감김 감지" to "눈 감김 감지"),
            TrackingQuality.UNCERTAIN to ("눈 상태 확인 중" to "눈 상태 확인 중"),
            TrackingQuality.STALE to ("영상 처리가 지연됩니다 · 조작 일시 대기" to "영상 처리가 지연됩니다 · 조작 일시 대기"),
            TrackingQuality.OUT_OF_RANGE to ("눈 검출됨 · 9점 보정을 시작해 주세요" to "얼굴 위치가 달라졌습니다 · 원래 위치로 돌아오거나 재보정하세요"),
            TrackingQuality.TRACKING to ("추적 중 · 홈으로 나가도 빨간 점이 표시됩니다" to "추적 중 · 홈으로 나가도 빨간 점이 표시됩니다"),
        )
        assertEquals(TrackingQuality.entries.toSet(), expected.keys)
        expected.forEach { (quality, messages) ->
            assertEquals(messages.first, TrackerState.frameStatus(quality, hasModel = false))
            assertEquals(messages.second, TrackerState.frameStatus(quality, hasModel = true))
        }
    }

    @Test
    fun aRejectedFitStaysVisibleWhileEyesAreDetected() {
        val session = TrackerState.beginCalibration()
        assertFalse(TrackerState.finishCalibration(session, null))
        TrackerState.calibrationFailed(failure)
        assertNull(TrackerState.model)
        // Eyes detected without a model: previously "눈 검출됨 · 9점 보정을 시작해 주세요" replaced the failure here.
        assertEquals(failure, TrackerState.frameStatus(TrackingQuality.OUT_OF_RANGE, hasModel = false))
        // A model kept from an earlier calibration still tracks, but the failed attempt is not hidden.
        assertEquals(failure, TrackerState.frameStatus(TrackingQuality.TRACKING, hasModel = true))
        // Transient detection states keep their own messages.
        assertEquals("얼굴 검출 대기", TrackerState.frameStatus(TrackingQuality.LOST, hasModel = false))
        assertEquals("눈 감김 감지", TrackerState.frameStatus(TrackingQuality.CLOSED, hasModel = false))
    }

    @Test
    fun theFailureClearsWhenACalibrationStartsOrSucceedsOrTrackingStops() {
        TrackerState.calibrationFailed(failure)
        val session = TrackerState.beginCalibration()
        assertNull(TrackerState.calibrationFailure)

        TrackerState.calibrationFailed(failure)
        val model = GazeModel.fit(CalibrationRules.TARGETS.map { Anchor(eyeFor(it), it.first, it.second) })
        assertNotNull(model)
        assertTrue(TrackerState.finishCalibration(session, model))
        assertNull(TrackerState.calibrationFailure)

        TrackerState.calibrationFailed(failure)
        TrackerState.trackingStopped()
        assertNull(TrackerState.calibrationFailure)
        assertEquals("눈 검출됨 · 9점 보정을 시작해 주세요", TrackerState.frameStatus(TrackingQuality.OUT_OF_RANGE, hasModel = false))
    }
}
