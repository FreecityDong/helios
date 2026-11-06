package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class VisualMotionEstimatorTest {
    private val width = VisualMotionEstimator.WIDTH
    private val height = VisualMotionEstimator.HEIGHT
    private fun textured() = IntArray(width * height) { i ->
        val x = i % width; val y = i / width
        ((x * 37 + y * 71 + x * y * 13) xor (x * 11 + y * 23)) and 255
    }
    private fun shifted(source: IntArray, dx: Int, dy: Int) = IntArray(source.size) { i ->
        val x = i % width - dx; val y = i / width - dy
        if (x in 0 until width && y in 0 until height) source[y * width + x] else 0
    }

    @Test fun detectsCoherentCameraShift() {
        val tracker = VisualMotionEstimator()
        val first = textured()
        assertEquals(VisualMotionKind.UNKNOWN, tracker.addFrame(100, first).kind)
        val result = tracker.addFrame(350, shifted(first, 2, -1))
        assertEquals(VisualMotionKind.GLOBAL_SHIFT, result.kind)
        assertTrue(result.consensusTiles >= 8)
    }

    @Test fun localChangeDoesNotBecomeGlobalMotion() {
        val tracker = VisualMotionEstimator()
        val first = textured()
        tracker.addFrame(100, first)
        val second = first.copyOf()
        for (y in 14..35) for (x in 25..48) second[y * width + x] = 255 - first[y * width + x]
        assertEquals(VisualMotionKind.STABLE, tracker.addFrame(350, second).kind)
    }

    @Test fun texturelessOrOldPairIsUnknown() {
        val tracker = VisualMotionEstimator()
        val blank = IntArray(width * height) { 80 }
        tracker.addFrame(100, blank)
        assertEquals(VisualMotionKind.UNKNOWN, tracker.addFrame(350, blank).kind)
        assertEquals(VisualMotionKind.UNKNOWN, tracker.addFrame(2_000, textured()).kind)
    }
}
