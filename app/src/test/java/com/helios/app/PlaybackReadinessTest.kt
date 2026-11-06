package com.helios.app
import org.junit.Assert.*
import org.junit.Test
class PlaybackReadinessTest {
    @Test fun actualPcmReadinessReplacesTheOldFixedDelay() {
        assertTrue(PlaybackReadiness.waitMs(600,false)>0)
        assertEquals(0L,PlaybackReadiness.waitMs(600,true))
    }
    @Test fun noRiskSentenceWaitsForeverForAFailedBluetoothDevice() {
        assertEquals(0L,PlaybackReadiness.waitMs(1_000,false))
        assertEquals(0L,PlaybackReadiness.waitMs(10_000,false))
    }
    @Test fun readyRouteStillGetsAShortInitialWarmup() {
        assertEquals(150L,PlaybackReadiness.waitMs(100,true))
        assertEquals(0L,PlaybackReadiness.waitMs(250,true))
    }
}
