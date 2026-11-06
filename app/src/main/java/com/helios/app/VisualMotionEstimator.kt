package com.helios.app

enum class VisualMotionKind { UNKNOWN, STABLE, GLOBAL_SHIFT }

data class VisualMotionEvidence(
    val kind: VisualMotionKind = VisualMotionKind.UNKNOWN,
    val timeMs: Long = 0,
    val matchedTiles: Int = 0,
    val consensusTiles: Int = 0,
    val shiftPixels: Double = 0.0,
    val shiftX: Int = 0,
    val shiftY: Int = 0,
)

/** Small grayscale block matcher. A coherent shift across the image is camera-motion evidence. */
class VisualMotionEstimator {
    private var previous: IntArray? = null
    private var previousMs = 0L

    fun reset() { previous = null; previousMs = 0L }

    fun addFrame(timeMs: Long, gray: IntArray): VisualMotionEvidence {
        require(gray.size == WIDTH * HEIGHT)
        val old = previous
        val age = timeMs - previousMs
        previous = gray.copyOf()
        previousMs = timeMs
        if (old == null || age !in 100..800) return VisualMotionEvidence(timeMs = timeMs)
        val votes = mutableListOf<Pair<Int, Int>>()
        for (cy in intArrayOf(10, 23, 36, 49)) for (cx in intArrayOf(10, 25, 40, 55, 70)) {
            var low = 255; var high = 0
            for (dy in -2..2) for (dx in -2..2) {
                val value = old[(cy + dy) * WIDTH + cx + dx]
                if (value < low) low = value
                if (value > high) high = value
            }
            if (high - low < 24) continue
            var best = Int.MAX_VALUE; var bestX = 0; var bestY = 0; var zero = Int.MAX_VALUE
            for (sy in -4..4) for (sx in -4..4) {
                var error = 0
                for (dy in -2..2) for (dx in -2..2) {
                    error += kotlin.math.abs(old[(cy + dy) * WIDTH + cx + dx] -
                        gray[(cy + dy + sy) * WIDTH + cx + dx + sx])
                }
                if (sx == 0 && sy == 0) zero = error
                if (error < best) { best = error; bestX = sx; bestY = sy }
            }
            if (best > 25 * 25) continue
            // A tiny SAD gain is not enough to call a block moving.
            votes.add(if (zero - best < 5 * 25) 0 to 0 else bestX to bestY)
        }
        if (votes.size < 8) return VisualMotionEvidence(timeMs = timeMs, matchedTiles = votes.size)
        val counts = votes.groupingBy { it }.eachCount()
        val winner = counts.maxByOrNull { it.value }!!
        val required = maxOf(6, (votes.size * 0.7).toInt() + 1)
        if (winner.value < required) return VisualMotionEvidence(timeMs = timeMs,
            matchedTiles = votes.size, consensusTiles = winner.value)
        val shift = kotlin.math.hypot(winner.key.first.toDouble(), winner.key.second.toDouble())
        val kind = if (shift >= 1.0) VisualMotionKind.GLOBAL_SHIFT else VisualMotionKind.STABLE
        return VisualMotionEvidence(kind, timeMs, votes.size, winner.value, shift,
            winner.key.first, winner.key.second)
    }

    companion object { const val WIDTH = 80; const val HEIGHT = 60 }
}
