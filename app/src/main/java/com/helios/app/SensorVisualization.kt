package com.helios.app

import kotlin.math.*

/** Completed RGB/depth pairs per wall-clock second, measured over >=2 seconds. */
class FrameRate {
    private var start = 0L
    private var count = 0
    @Volatile var fps = 0.0
        private set
    @Synchronized fun reset() { start = 0; count = 0; fps = 0.0 }
    @Synchronized fun record(now: Long) {
        if (start == 0L) { start = now; return }
        count++
        if (now - start >= 2000) { fps = count * 1000.0 / (now - start); count = 0; start = now }
    }
}

/** Rainbow depth visualization: near red, far blue, invalid black. Metric data is unchanged. */
object DepthPalette {
    private val lut = IntArray(256) { i ->
        val hue = i / 255.0 * 4.0
        val section = hue.toInt()
        val f = hue - section
        val rgb = when (section) {
            0 -> doubleArrayOf(1.0, f, 0.0)
            1 -> doubleArrayOf(1.0 - f, 1.0, 0.0)
            2 -> doubleArrayOf(0.0, 1.0, f)
            else -> doubleArrayOf(0.0, 1.0 - (hue - 3).coerceIn(0.0, 1.0), 1.0)
        }
        (0xff shl 24) or ((rgb[0] * 255).toInt() shl 16) or ((rgb[1] * 255).toInt() shl 8) or (rgb[2] * 255).toInt()
    }
    fun color(mm: Float): Int = if (mm <= 0 || !mm.isFinite()) 0xff000000.toInt() else lut[(mm / 5000 * 255).toInt().coerceIn(0, 255)]
}

/** Relative gyro attitude. Stationary bias calibration reduces drift; no absolute heading reference. */
class GyroOrientation {
    private var q = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    private var lastUs = 0L
    private var bias = doubleArrayOf(0.0, 0.0, 0.0)
    @Synchronized fun reset(clearBias: Boolean = false) {
        q = doubleArrayOf(1.0, 0.0, 0.0, 0.0); lastUs = 0
        if (clearBias) bias = doubleArrayOf(0.0, 0.0, 0.0)
    }
    @Synchronized fun calibrateStationaryBias(value: List<Double>): Boolean {
        if (value.size != 3 || value.any { !it.isFinite() } || sqrt(value.sumOf { it * it }) >= 0.1) return false
        bias = value.toDoubleArray()
        return true
    }
    @Synchronized fun snapshot(): DoubleArray = q.copyOf()
    @Synchronized fun update(timestampUs: Long, x: Double, y: Double, z: Double) {
        val dt = (timestampUs - lastUs) / 1e6
        val valid = lastUs != 0L && dt > 0 && dt <= 0.1
        lastUs = timestampUs
        if (!valid || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val gx = x - bias[0]; val gy = y - bias[1]; val gz = z - bias[2]
        val speed = sqrt(gx*gx + gy*gy + gz*gz)
        if (speed < 1e-12) return
        val a = speed * dt / 2
        val s = sin(a) / speed
        val w = cos(a); val u = gx*s; val v = gy*s; val t = gz*s
        val p = q
        q = doubleArrayOf(p[0]*w-p[1]*u-p[2]*v-p[3]*t,
            p[0]*u+p[1]*w+p[2]*t-p[3]*v,
            p[0]*v-p[1]*t+p[2]*w+p[3]*u,
            p[0]*t+p[1]*v-p[2]*u+p[3]*w)
        val norm = sqrt(q.sumOf { it*it }); q = q.map { it/norm }.toDoubleArray()
    }
}
