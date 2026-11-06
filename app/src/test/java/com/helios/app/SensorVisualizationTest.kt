package com.helios.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SensorVisualizationTest {
    @Test fun integratesQuarterTurnAndIgnoresDisconnectGap() {
        val pose = GyroOrientation()
        for (i in 0..100) pose.update(1_000_000L+i*10_000L, 0.0, 0.0, PI/2)
        assertEquals(sqrt(0.5), pose.snapshot()[0], 1e-6)
        assertEquals(sqrt(0.5), pose.snapshot()[3], 1e-6)
        val before = pose.snapshot()
        pose.update(9_000_000, 0.0, 0.0, 100.0)
        assertArrayEquals(before, pose.snapshot(), 1e-6)
        pose.reset(); assertArrayEquals(doubleArrayOf(1.0,0.0,0.0,0.0), pose.snapshot(), 0.0)
    }
    @Test fun rateMeasuresCompletedWork() {
        val rate = FrameRate()
        for (i in 0..40) rate.record(1000L+i*50)
        assertEquals(20.0, rate.fps, 0.01)
        rate.reset(); assertEquals(0.0, rate.fps, 0.0)
    }
    @Test fun invalidDepthIsBlackAndDistanceChangesColor() {
        assertEquals(0xff000000.toInt(), DepthPalette.color(0f))
        assertEquals(0xff0000ff.toInt(), DepthPalette.color(5000f))
        assertNotEquals(DepthPalette.color(500f), DepthPalette.color(2500f))
    }
}
