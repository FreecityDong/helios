package com.helios.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VideoModeSelectionTest {
    @Test fun wideViewWinsOverPreviouslyPreferredVga() {
        val narrow = VideoMode(0,640,480,30,63.0,50.0)
        val wide = VideoMode(1,1280,720,30,86.0,55.0)
        val full = VideoMode(2,1920,1080,30,86.1,55.1)
        assertEquals(wide,VideoModeSelection.select(listOf(narrow,wide,full),true))
    }
    @Test fun equivalentCoverageUsesFewerPixelsWithoutDroppingBelowVgaWidth() {
        val tiny = VideoMode(0,320,200,30,91.0,66.0)
        val preferred = VideoMode(1,640,400,30,91.0,66.0)
        val large = VideoMode(2,1280,800,30,91.0,66.0)
        assertEquals(preferred,VideoModeSelection.select(listOf(tiny,preferred,large),false))
    }
    @Test fun absentIntrinsicsFavorWidescreenAtThirtyFps() {
        val wide = VideoMode(1,1280,720,30)
        assertEquals(wide,VideoModeSelection.select(listOf(VideoMode(0,640,480,30),wide,
            VideoMode(2,1920,1080,60)),true))
    }
    @Test fun nativeIntrinsicLayoutIsValidated() {
        val bytes=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(320f).putFloat(320f).putFloat(319.5f).putFloat(199.5f)
            .putShort(640.toShort()).putShort(400.toShort()).array()
        val mode=VideoMode.fromIntrinsic(0,640,400,30,bytes)
        assertEquals(89.91,mode.horizontalFov!!,0.02)
        assertNull(VideoMode.fromIntrinsic(0,640,480,30,bytes).horizontalFov)
    }
}
