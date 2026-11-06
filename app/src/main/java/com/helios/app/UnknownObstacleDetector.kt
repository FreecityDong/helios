package com.helios.app

import kotlin.math.abs

data class UnknownObstacleObservation(val meters: Float, val sector: Int)

/** Finds a small, coherent near-depth patch in the central aligned depth image. */
object UnknownObstacleDetector {
    fun detect(bytes: ByteArray, width: Int, height: Int, scaleMm: Float,
        maxMeters: Float = 0.60f, projection: HorizontalProjection? = null,
        band: ImageHeightBand? = null, acceptPoint: ((Int,Int,Float)->Boolean)? = null): UnknownObstacleObservation? {
        if (width <= 0 || height <= 0 || bytes.size < width.toLong() * height * 2 ||
            !scaleMm.isFinite() || scaleMm <= 0f || !maxMeters.isFinite() || maxMeters < 0.25f) return null
        val camera = projection?.takeIf { it.matches(width,height) }
        val cell = 8
        val xStart = if (camera != null) 0 else (width * 0.38f).toInt() / cell * cell
        val xEnd = if (camera != null) width / cell * cell else (width * 0.62f).toInt() / cell * cell
        val yStart = (height * (band?.startFraction ?: 0.35f)).toInt() / cell * cell
        val yEnd = (height * (band?.endFraction ?: 0.70f)).toInt() / cell * cell
        val columns = (xEnd - xStart) / cell
        val rows = (yEnd - yStart) / cell
        if (columns < 3 || rows < 3) return null
        val cells = Array(rows) { FloatArray(columns) { Float.NaN } }
        for (row in 0 until rows) for (col in 0 until columns) {
            val values = ArrayList<Float>(16)
            val x0 = xStart + col * cell
            val y0 = yStart + row * cell
            for (dy in 0 until cell step 2) for (dx in 0 until cell step 2) {
                val offset = ((y0 + dy) * width + x0 + dx) * 2
                val raw = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
                val meters = raw * scaleMm / 1_000f
                if (raw != 0 && meters in 0.25f..maxMeters &&
                    (camera == null || camera.inForward((x0+dx).toFloat(),meters)) &&
                    (acceptPoint == null || acceptPoint(x0+dx,y0+dy,meters))) values.add(meters)
            }
            if (values.size < 12) continue
            values.sort()
            if (values.last() - values.first() <= 0.12f) cells[row][col] = values[values.size / 2]
        }
        var best: UnknownObstacleObservation? = null
        for (row in 0 until rows - 2) for (col in 0 until columns - 2) {
            val center = cells[row + 1][col + 1]
            if (!center.isFinite()) continue
            var matching = 0
            val distances = ArrayList<Float>(9)
            for (dy in 0..2) for (dx in 0..2) {
                val distance = cells[row + dy][col + dx]
                if (distance.isFinite() && abs(distance - center) <= 0.10f) {
                    matching++
                    distances.add(distance)
                }
            }
            if (matching < 8) continue
            distances.sort()
            val meters = distances[distances.size / 2]
            val centerX = xStart + (col + 1.5f) * cell
            if (camera != null) {
                val left = camera.lateralMeters(centerX-1.5f*cell,meters)
                val right = camera.lateralMeters(centerX+1.5f*cell,meters)
                if (minOf(right,camera.halfForwardMeters)-
                    maxOf(left,-camera.halfForwardMeters) < 0.01f) continue
            }
            if (best == null || meters < best.meters) {
                best = UnknownObstacleObservation(meters, (centerX / width * 3).toInt().coerceIn(0, 2))
            }
        }
        return best
    }
}
