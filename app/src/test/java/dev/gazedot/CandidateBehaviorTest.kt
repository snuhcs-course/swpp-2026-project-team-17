package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * The evaluation instrumentation must not change the home-screen candidate's numerics. Every check compares the
 * instrumented app code with the frozen 30be1cf copies in CandidateBaseline.kt, bit for bit (Double.equals on boxed
 * values compares raw bits, so NaN, -0.0 and last-place differences all count).
 */
class CandidateBehaviorTest {
    private val random = Random(20261102)

    private fun privateDouble(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as Double
    private fun privateArray(o: Any, name: String) = (o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as DoubleArray).toList()

    private fun randomAnchors(): List<Anchor> {
        val gainX = .02 + random.nextDouble() * .06; val gainY = .015 + random.nextDouble() * .05
        val noise = random.nextDouble() * .004
        return SyntheticEvaluation.anchors().map { a ->
            Anchor(EyeFeatures((a.x - .5) * gainX + random.nextGaussian() * noise, (a.y - .5) * gainY + random.nextGaussian() * noise,
                .5 + random.nextGaussian() * .01, .5 + random.nextGaussian() * .01, .3 + random.nextGaussian() * .005), a.x, a.y)
        }
    }

    private fun randomFeatures(): EyeFeatures {
        fun pick(normal: Double) = when (random.nextInt(40)) {
            0 -> Double.NaN; 1 -> Double.POSITIVE_INFINITY; 2 -> -0.0
            else -> normal
        }
        return EyeFeatures(pick(random.nextGaussian() * .05), pick(random.nextGaussian() * .04),
            pick(.5 + random.nextGaussian() * .08), pick(.5 + random.nextGaussian() * .08), pick(.3 * (0.6 + random.nextDouble() * .8)))
    }

    @Test fun fitParametersAndPredictionsAreBitIdenticalToTheCandidate() {
        var predicted = 0; var rejected = 0; var clamped = 0; var fitsRejected = 0
        repeat(400) { i ->
            val anchors = if (i % 25 == 0) randomAnchors().map { it.copy(eye = it.eye.copy(x = .01)) } else randomAnchors()
            val live = GazeModel.fit(anchors)
            val base = BaselineGazeModel.fit(anchors)
            assertEquals("fit acceptance", base == null, live == null)
            if (live == null || base == null) { fitsRejected++; return@repeat }
            for (name in listOf("meanX", "meanY", "stdX", "stdY"))
                assertEquals(name, privateDouble(base, name).toRawBits(), privateDouble(live, name).toRawBits())
            assertEquals(privateArray(base, "wx"), privateArray(live, "wx")); assertEquals(privateArray(base, "wy"), privateArray(live, "wy"))
            assertEquals(base.fitError.toRawBits(), live.fitError.toRawBits())
            repeat(250) {
                val f = randomFeatures()
                val expected = base.predict(f)
                val actual = live.predict(f)
                assertEquals("predict($f)", expected, actual)
                // Diagnostics agree with predict: a rejection reason exactly when predict is null, and predict is the clamped linear map.
                assertEquals(expected == null, live.rejection(f) != null)
                val linear = live.linearOutput(f)
                assertEquals(expected, linear?.let { it.first.coerceIn(0.0, 1.0) to it.second.coerceIn(0.0, 1.0) })
                if (expected == null) rejected++ else predicted++
                if (linear != null && (linear.first !in 0.0..1.0 || linear.second !in 0.0..1.0)) clamped++
            }
        }
        // The inputs exercise every branch: rejected fits, rejected poses, predictions and active clamping.
        assertTrue(fitsRejected > 0); assertTrue(rejected > 1000); assertTrue(predicted > 1000); assertTrue(clamped > 100)
    }

    @Test fun predictionsStayClampedAndPoseRulesAreNamed() {
        val model = SyntheticEvaluation.model()
        val (ex, ey) = SyntheticEvaluation.eye(1.4, -0.3)
        val far = EyeFeatures(ex, ey, .5, .5, .3)
        val p = model.predict(far)!!
        assertEquals(1.0, p.first, 0.0); assertEquals(0.0, p.second, 0.0)
        val linear = model.linearOutput(far)!!
        assertTrue(linear.first > 1.0 && linear.second < 0.0)
        assertNull(model.rejection(far))
        assertEquals("non_finite_features", model.rejection(far.copy(scale = Double.NaN)))
        assertEquals("scale_outside_0.72_1.28", model.rejection(far.copy(scale = .3 * 1.3)))
        assertEquals("face_x_shift_gt_0.12", model.rejection(far.copy(faceX = .63)))
        assertEquals("face_y_shift_gt_0.12", model.rejection(far.copy(faceY = .37)))
        assertNull(model.predict(far.copy(faceY = .37))); assertNull(model.linearOutput(far.copy(faceY = .37)))
        // Bounds of the pose rules are inclusive exactly as in the candidate.
        assertNotNull(model.predict(far.copy(faceX = .5 + .12)))
    }

    @Test fun smootherIsBitIdenticalIncludingNullPredictionsAndResets() {
        val smoother = GazeSmoother()
        var baseline: Pair<Double, Double>? = null
        repeat(20_000) {
            when (random.nextInt(30)) {
                0 -> { smoother.reset(); baseline = null } // watchdog: the candidate set smoothed = null
                else -> {
                    val position = if (random.nextInt(12) == 0) null else random.nextDouble() to random.nextDouble()
                    baseline = baselineSmooth(baseline, position)
                    assertEquals(baseline, smoother.update(position))
                }
            }
            assertEquals(baseline, smoother.value)
        }
    }

    @Test fun smoothingFormulaKeepsItsOperationOrder() {
        val s = GazeSmoother()
        assertEquals(.2 to .9, s.update(.2 to .9))
        val expected = .2 + 0.28 * (.7 - .2) to .9 + 0.28 * (.1 - .9)
        assertEquals(expected, s.update(.7 to .1))
        assertNull(s.update(null)); assertNull(s.value)
        assertEquals(.4 to .4, s.update(.4 to .4)) // a null prediction cleared the history
    }

    private fun landmarks(eyeSpan: Double, aperture: Double, count: Int = 478): List<NormalizedLandmark> {
        val p = MutableList(count) { NormalizedLandmark.create((.3 + random.nextDouble() * .4).toFloat(), (.3 + random.nextDouble() * .4).toFloat(), 0f) }
        fun set(i: Int, x: Double, y: Double) { if (i < count) p[i] = NormalizedLandmark.create(x.toFloat(), y.toFloat(), 0f) }
        val cy = .45 + random.nextGaussian() * .01
        for ((corner1, corner2, iris, top, bottom, cx) in listOf(
            listOf(33, 133, 468, 159, 145, .38), listOf(362, 263, 473, 386, 374, .62))) {
            val c = cx.toDouble()
            set(corner1.toInt(), c - eyeSpan / 2, cy); set(corner2.toInt(), c + eyeSpan / 2, cy + random.nextGaussian() * .003)
            set(iris.toInt(), c + random.nextGaussian() * eyeSpan * .1, cy + random.nextGaussian() * eyeSpan * .05)
            set(top.toInt(), c, cy - aperture / 2); set(bottom.toInt(), c, cy + aperture / 2)
        }
        set(1, .5 + random.nextGaussian() * .02, .55)
        return p
    }
    private operator fun <T> List<T>.component6() = this[5]

    @Test fun featureExtractionIsBitIdenticalAndNamesTheFailedCheck() {
        val reasons = mutableSetOf<String>()
        var extracted = 0
        repeat(5_000) { i ->
            val span = if (i % 9 == 0) .005 else .05 + random.nextDouble() * .05
            val aperture = span * (if (i % 7 == 0) .05 else .2 + random.nextDouble() * .3)
            val p = landmarks(span, aperture, if (i % 97 == 0) 468 else 478)
            val (w, h) = 480 to 640
            val expected = BaselineLandmarkFeatures.extract(p, w, h)
            val detailed = LandmarkFeatures.extractDetailed(p, w, h)
            assertEquals(expected, LandmarkFeatures.extract(p, w, h))
            assertEquals(expected, detailed.features)
            assertEquals(expected == null, detailed.rejection != null)
            detailed.rejection?.let { reasons += it }
            if (expected != null) extracted++
        }
        assertTrue(extracted > 1000)
        assertEquals(setOf("fewer_than_478_landmarks", "eye_33_133_corner_span_lt_12px", "eye_33_133_aperture_ratio_lt_0.10"), reasons)
        // The second eye's checks are reached only when the first eye passes.
        val p = landmarks(.08, .03).toMutableList()
        p[386] = NormalizedLandmark.create(.62f, p[374].y(), 0f)
        assertEquals("eye_362_263_aperture_ratio_lt_0.10", LandmarkFeatures.extractDetailed(p, 480, 640).rejection)
        assertNull(BaselineLandmarkFeatures.extract(p, 480, 640))
    }

    @Test fun cadenceAndWatchdogConstantsAreTheCandidates() {
        assertEquals(66L, TrackingService.FRAME_INTERVAL_MS)
        assertEquals(800L, TrackingService.WATCHDOG_NO_RESULT_MS)
    }

    @Test fun medianFeaturesIsUnchanged() {
        repeat(200) {
            val samples = List(1 + random.nextInt(30)) { randomFeatures() }
            assertEquals(baselineMedianFeatures(samples), medianFeatures(samples))
        }
    }
}
