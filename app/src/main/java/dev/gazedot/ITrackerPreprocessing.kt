package dev.gazedot

import java.nio.FloatBuffer
import kotlin.math.*

internal data class ImagePoint(val x: Double, val y: Double)
internal data class ImageCrop(val left: Double, val top: Double, val size: Double)
internal data class ITrackerCrops(val face: ImageCrop, val leftEye: ImageCrop, val rightEye: ImageCrop)

/** Pixel-space geometry for upright, unmirrored camera frames. No Android classes, for verification. */
internal object ITrackerPreprocessing {
    const val IMAGE_SIZE = 224
    const val GRID_SIZE = 25

    fun crops(points: List<ImagePoint>): ITrackerCrops? {
        if (points.size < 478 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val facePoints = points.take(468)
        val minX = facePoints.minOf { it.x }; val maxX = facePoints.maxOf { it.x }
        val minY = facePoints.minOf { it.y }; val maxY = facePoints.maxOf { it.y }
        val faceSide = max(maxX - minX, maxY - minY) * 1.1
        if (faceSide < 24) return null
        val face = ImageCrop((minX + maxX - faceSide) / 2, (minY + maxY - faceSide) / 2, faceSide)
        fun eye(a: Int, b: Int): ImageCrop? {
            val first = points[a]; val second = points[b]
            val side = hypot(first.x - second.x, first.y - second.y) * 2
            if (side < 24) return null
            return ImageCrop((first.x + second.x - side) / 2, (first.y + second.y - side) / 2, side)
        }
        // Anatomical left is on the image's right. Do not mirror or swap these inputs.
        return ITrackerCrops(face, eye(362, 263) ?: return null, eye(33, 133) ?: return null)
    }

    /** RGB [0,1], CHW. Zero padding matches the official PyTorch prepareDataset.cropImage. */
    fun image(pixels: IntArray, width: Int, height: Int, crop: ImageCrop, output: FloatBuffer) {
        require(width > 0 && height > 0 && pixels.size >= width * height && crop.size >= 1)
        output.clear()
        val plane = IMAGE_SIZE * IMAGE_SIZE
        for (y in 0 until IMAGE_SIZE) {
            val sourceY = (crop.top + (y + .5) * crop.size / IMAGE_SIZE - .5)
                .coerceIn(crop.top, crop.top + crop.size - 1)
            val y0 = floor(sourceY).toInt(); val y1 = y0 + 1
            val fy = sourceY - y0
            for (x in 0 until IMAGE_SIZE) {
                val sourceX = (crop.left + (x + .5) * crop.size / IMAGE_SIZE - .5)
                    .coerceIn(crop.left, crop.left + crop.size - 1)
                val x0 = floor(sourceX).toInt(); val x1 = x0 + 1
                val fx = sourceX - x0
                fun pixel(x: Int, y: Int) = if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0
                val p00 = pixel(x0, y0); val p10 = pixel(x1, y0)
                val p01 = pixel(x0, y1); val p11 = pixel(x1, y1)
                for (channel in 0..2) {
                    val shift = 16 - 8 * channel
                    fun value(pixel: Int) = (pixel ushr shift) and 255
                    val upper = value(p00) * (1 - fx) + value(p10) * fx
                    val lower = value(p01) * (1 - fx) + value(p11) * fx
                    output.put(channel * plane + y * IMAGE_SIZE + x,
                        ((upper * (1 - fy) + lower * fy) / 255).toFloat())
                }
            }
        }
        output.rewind()
    }

    /** Preserve upstream's one-based rectangle consumed by ITrackerData.makeGrid, including its offset. */
    fun grid(face: ImageCrop, width: Int, height: Int, output: FloatBuffer) {
        require(width > 0 && height > 0)
        fun rounded(value: Double) = (if (value >= 0) floor(value + .5) else ceil(value - .5)).toInt()
        val left = rounded(face.left / width * GRID_SIZE) + 1
        val top = rounded(face.top / height * GRID_SIZE) + 1
        val w = rounded(face.size / width * GRID_SIZE)
        val h = rounded(face.size / height * GRID_SIZE)
        output.clear()
        for (y in 0 until GRID_SIZE) for (x in 0 until GRID_SIZE) {
            output.put(if (x >= left && x < left + w && y >= top && y < top + h) 1f else 0f)
        }
        output.rewind()
    }
}
