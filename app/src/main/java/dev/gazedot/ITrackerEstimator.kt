package dev.gazedot

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** GazeCapture's frozen iTracker checkpoint. All methods run on the camera worker, never the UI. */
internal class ITrackerEstimator(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        context.assets.open("itracker.onnx").use { asset ->
            environment.createSession(asset.readBytes(), options)
        }
    }
    private fun buffer(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val face = buffer(3 * 224 * 224)
    private val left = buffer(3 * 224 * 224)
    private val right = buffer(3 * 224 * 224)
    private val grid = buffer(25 * 25)
    private var pixels = IntArray(0)

    /** Camera-relative centimetres, NOT normalized screen coordinates. Nine-point calibration maps them. */
    fun estimate(frame: Bitmap, landmarks: List<NormalizedLandmark>): Pair<Double, Double>? {
        val crops = ITrackerPreprocessing.crops(landmarks.map {
            ImagePoint(it.x().toDouble() * frame.width, it.y().toDouble() * frame.height)
        }) ?: return null
        val count = frame.width * frame.height
        if (pixels.size != count) pixels = IntArray(count)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        ITrackerPreprocessing.image(pixels, frame.width, frame.height, crops.face, face)
        ITrackerPreprocessing.image(pixels, frame.width, frame.height, crops.leftEye, left)
        ITrackerPreprocessing.image(pixels, frame.width, frame.height, crops.rightEye, right)
        ITrackerPreprocessing.grid(crops.face, frame.width, frame.height, grid)
        val shape = longArrayOf(1, 3, 224, 224)
        OnnxTensor.createTensor(environment, face, shape).use { faceTensor ->
            OnnxTensor.createTensor(environment, left, shape).use { leftTensor ->
                OnnxTensor.createTensor(environment, right, shape).use { rightTensor ->
                    OnnxTensor.createTensor(environment, grid, longArrayOf(1, 625)).use { gridTensor ->
                        session.run(mapOf("face" to faceTensor, "left_eye" to leftTensor,
                            "right_eye" to rightTensor, "face_grid" to gridTensor)).use { result ->
                            val point = (result[0].value as Array<*>).first() as FloatArray
                            val x = point[0].toDouble(); val y = point[1].toDouble()
                            return if (x.isFinite() && y.isFinite()) x to y else null
                        }
                    }
                }
            }
        }
    }

    override fun close() { session.close() }
}
