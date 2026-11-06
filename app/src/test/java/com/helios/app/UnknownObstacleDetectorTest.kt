package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class UnknownObstacleDetectorTest {
    private fun depth(width: Int = 640, height: Int = 480, rect: IntArray?, mm: Int = 500): ByteArray {
        val bytes = ByteArray(width * height * 2)
        if (rect != null) for (y in rect[1] until rect[3]) for (x in rect[0] until rect[2]) {
            val offset = (y * width + x) * 2
            bytes[offset] = mm.toByte()
            bytes[offset + 1] = (mm shr 8).toByte()
        }
        return bytes
    }

    @Test fun coherentClosePatchInCenterIsCandidate() {
        val candidate = UnknownObstacleDetector.detect(depth(rect = intArrayOf(290,210,346,266)),640,480,1f)
        assertNotNull(candidate)
        assertEquals(0.5f,candidate!!.meters,0.001f)
        assertEquals(1,candidate.sector)
    }

    @Test fun sparseSideAndFarDepthAreIgnored() {
        assertNull(UnknownObstacleDetector.detect(depth(rect = null),640,480,1f))
        assertNull(UnknownObstacleDetector.detect(depth(rect = intArrayOf(30,210,86,266)),640,480,1f))
        assertNull(UnknownObstacleDetector.detect(depth(rect = intArrayOf(290,210,346,266),mm=900),640,480,1f))
        assertNull(UnknownObstacleDetector.detect(depth(rect = intArrayOf(300,220,308,228)),640,480,1f))
    }
}
