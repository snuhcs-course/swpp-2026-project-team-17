package dev.gazedot

import java.nio.FloatBuffer
import org.junit.Assert.*
import org.junit.Test

class ITrackerPreprocessingTest {
    @Test fun keepsAnatomicalEyeOrderAndRejectsInvalidDetections() {
        val points = MutableList(478) { ImagePoint(100.0, 100.0) }
        points[10] = ImagePoint(100.0, 10.0)
        points[152] = ImagePoint(100.0, 190.0)
        points[33] = ImagePoint(40.0, 70.0); points[133] = ImagePoint(70.0, 70.0)
        points[362] = ImagePoint(130.0, 70.0); points[263] = ImagePoint(160.0, 70.0)
        val crops = ITrackerPreprocessing.crops(points)!!
        assertEquals(145.0, crops.leftEye.left + crops.leftEye.size / 2, .00001)
        assertEquals(55.0, crops.rightEye.left + crops.rightEye.size / 2, .00001)
        assertNull(ITrackerPreprocessing.crops(points.take(467)))
        points[1] = ImagePoint(Double.NaN, 0.0)
        assertNull(ITrackerPreprocessing.crops(points))
    }

    @Test fun producesRgbChwWithZeroPaddingAndNoMirror() {
        val plane = 224 * 224
        val output = FloatBuffer.allocate(3 * plane)
        val pixels = intArrayOf(0xffff0000.toInt(), 0xff0000ff.toInt(),
            0xffff0000.toInt(), 0xff0000ff.toInt())
        ITrackerPreprocessing.image(pixels, 2, 2, ImageCrop(0.0, 0.0, 2.0), output)
        assertEquals(1f, output[0], 0f)
        assertEquals(1f, output[2 * plane + 223], 0f)
        val left = 112 * 224 + 56; val right = 112 * 224 + 167
        assertTrue(output[left] > .99f)
        assertEquals(0f, output[plane + left], 0f)
        assertTrue(output[2 * plane + left] < .01f)
        assertTrue(output[right] < .01f)
        assertTrue(output[2 * plane + right] > .99f)
        ITrackerPreprocessing.image(pixels, 2, 2, ImageCrop(-1.0, -1.0, 4.0), output)
        assertEquals(0f, output[0], 0f)
        assertEquals(0f, output[2 * plane + 223], 0f)
        assertEquals(0, output.position())
    }

    @Test fun faceGridUsesScreenPositionAndClipsAtEdges() {
        val grid = FloatBuffer.allocate(625)
        ITrackerPreprocessing.grid(ImageCrop(20.0, 40.0, 40.0), 100, 200, grid)
        assertEquals(0f, grid[5 * 25 + 5], 0f)
        assertEquals(1f, grid[6 * 25 + 6], 0f)
        assertEquals(1f, grid[10 * 25 + 15], 0f)
        assertEquals(0f, grid[11 * 25 + 6], 0f)
        assertEquals(0f, grid[6 * 25 + 16], 0f)
        ITrackerPreprocessing.grid(ImageCrop(-20.0, -20.0, 40.0), 100, 100, grid)
        assertEquals(36, (0 until 625).count { grid[it] == 1f })
    }
}
