package com.helios.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan

/** Intrinsic layout of the pinned Orbbec Android SDK 2.0.6 (20-byte native struct). */
internal data class VideoMode(val index: Int, val width: Int, val height: Int, val fps: Int,
    val horizontalFov: Double? = null, val verticalFov: Double? = null,
    val projection: HorizontalProjection? = null) {
    val angularCoverage: Double? get() = horizontalFov?.let { h -> verticalFov?.let { h * it } }

    companion object {
        fun fromIntrinsic(index: Int, width: Int, height: Int, fps: Int, bytes: ByteArray): VideoMode {
            val fallback = VideoMode(index, width, height, fps)
            if (bytes.size < 20) return fallback
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val fx = b.float.toDouble(); val fy = b.float.toDouble()
            val cx = b.float.toDouble(); val cy = b.float.toDouble()
            val iw = b.short.toInt() and 0xffff; val ih = b.short.toInt() and 0xffff
            if (iw != width || ih != height || !fx.isFinite() || !fy.isFinite() ||
                fx <= 0 || fy <= 0 || cx !in 0.0..(width - 1).toDouble() ||
                cy !in 0.0..(height - 1).toDouble()) return fallback
            val hf = Math.toDegrees(atan(cx / fx) + atan((width - 1 - cx) / fx))
            val vf = Math.toDegrees(atan(cy / fy) + atan((height - 1 - cy) / fy))
            return fallback.copy(horizontalFov = hf, verticalFov = vf,
                projection = HorizontalProjection(width,height,fx.toFloat(),cx.toFloat(),fy.toFloat(),cy.toFloat()))
        }
    }
}

internal object VideoModeSelection {
    fun select(modes: List<VideoMode>, color: Boolean): VideoMode {
        require(modes.isNotEmpty())
        // Keep video/IMU processing at the existing 30 FPS budget.
        val fpsError = modes.minOf { kotlin.math.abs(it.fps - 30) }
        val timed = modes.filter { kotlin.math.abs(it.fps - 30) == fpsError }
        val candidates = timed.filter { it.width >= 640 && it.height >= 360 }.ifEmpty { timed }
        val measured = candidates.filter { it.angularCoverage != null }
        val widest = measured.maxOfOrNull { it.angularCoverage!! }
        val pool = if (widest != null) measured.filter { it.angularCoverage!! >= widest * 0.98 }
            else if (color) candidates.filter { kotlin.math.abs(it.width.toDouble() / it.height - 16.0 / 9) < 0.02 }
                .ifEmpty { candidates }
            else candidates
        return pool.minWith(compareBy<VideoMode> { it.width.toLong() * it.height }.thenBy { it.index })
    }
}
