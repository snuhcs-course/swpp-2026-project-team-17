package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * The evaluation instrumentation must not change the candidate's numerics. Every check compares the instrumented app
 * code with the frozen e8fa01e copies in CandidateBaseline.kt, bit for bit (Double.equals on boxed values compares raw
 * bits, so NaN, -0.0 and last-place differences all count), and requires the read-only diagnostics to agree with the
 * unchanged functions they describe.
 */
class CandidateBehaviorTest {
    private val random = Random(20261115)

    private fun privateDouble(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as Double
    private fun privateArray(o: Any, name: String) = (o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as DoubleArray).toList()

    /** 27 anchors (three passes of the nine targets) with random gains, noise and face positions. */
    private fun randomAnchors(): List<Anchor> {
        val gainX = .02 + random.nextDouble() * .06; val gainY = .015 + random.nextDouble() * .05
        val noise = random.nextDouble() * .004
        return (1..3).flatMap { pass ->
            CalibrationRules.TARGETS.map { (x, y) ->
                Anchor(EyeFeatures((x - .5) * gainX + random.nextGaussian() * noise, (y - .5) * gainY + random.nextGaussian() * noise,
                    .5 + random.nextGaussian() * .01, .5 + (pass - 2) * random.nextDouble() * .05, .3 + random.nextGaussian() * .005), x, y)
            }
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

    @Test fun fitParametersAndPredictionsAreBitIdenticalToTheBaseline() {
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
            val p = live.parameters()
            assertEquals(privateArray(base, "wx"), p.wx); assertEquals(base.fitError, p.fitError, 0.0)
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
        assertNotNull(model.predict(far.copy(faceX = .5 + .12)))
    }

    private fun landmarks(eyeSpan: Double, aperture: Double, count: Int = 478): MutableList<NormalizedLandmark> {
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

    @Test fun featureExtractionIsUnchangedAndItsDiagnosticAgreesWithObserve() {
        val reasons = mutableSetOf<String>()
        var extracted = 0
        repeat(6_000) { i ->
            // Spans around the 12 px limit (480 px wide image: 0.025 = 12 px) exercise both implementations' rounding.
            val span = when (i % 9) { 0 -> .005; 1 -> .0249 + random.nextDouble() * .0002; else -> .05 + random.nextDouble() * .05 }
            val aperture = span * (if (i % 7 == 0) .05 else if (i % 7 == 1) .099 + random.nextDouble() * .002 else .2 + random.nextDouble() * .3)
            val p = landmarks(span, aperture, if (i % 97 == 0) 468 else 478)
            if (i % 211 == 0) p[100] = NormalizedLandmark.create(Float.NaN, .5f, 0f)
            val (w, h) = 480 to 640
            val expected = BaselineLandmarkFeatures.extract(p, w, h)
            assertEquals(expected, LandmarkFeatures.extract(p, w, h))
            val observed = EyeObservations.observe(p, w, h)
            val reason = EvaluationDiagnostics.featureRejection(p, w, h)
            assertEquals("diagnostic agrees with observe for case $i", observed.features == null, reason != null)
            if (observed.features != null) assertEquals(expected, observed.features)
            reason?.let { reasons += it }
            if (expected != null) extracted++
        }
        assertTrue(extracted > 1000)
        assertTrue(reasons.containsAll(setOf("fewer_than_478_landmarks", "eye_33_133_span_lt_12px",
            "eye_33_133_aperture_ratio_lt_0.10", "non_finite_landmarks")))
        // The second eye's checks are reached only when the first eye passes.
        val p = landmarks(.08, .03)
        p[386] = NormalizedLandmark.create(.62f, p[374].y(), 0f)
        assertEquals("eye_362_263_aperture_ratio_lt_0.10", EvaluationDiagnostics.featureRejection(p, 480, 640))
        assertNull(BaselineLandmarkFeatures.extract(p, 480, 640))
    }

    @Test fun serviceConstantsAreTheCandidates() {
        assertEquals(66L, TrackingService.FRAME_INTERVAL_MS)
        assertEquals(800L, TrackingService.WATCHDOG_NO_RESULT_MS)
        assertEquals(250L, TrackingService.DELAYED_RESULT_MS)
    }

    @Test fun medianFeaturesIsUnchanged() {
        repeat(200) {
            val samples = List(1 + random.nextInt(30)) { randomFeatures() }
            assertEquals(baselineMedianFeatures(samples), medianFeatures(samples))
        }
    }
}
