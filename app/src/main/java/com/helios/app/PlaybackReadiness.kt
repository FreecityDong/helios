package com.helios.app

internal object PlaybackReadiness {
    const val MAX_WAIT_MS=1_000L
    fun waitMs(elapsedMs:Long,ready:Boolean):Long = when {
        elapsedMs<0 -> MAX_WAIT_MS
        elapsedMs>=MAX_WAIT_MS -> 0
        !ready -> minOf(50,MAX_WAIT_MS-elapsedMs)
        else -> (250-elapsedMs).coerceAtLeast(0)
    }
}
