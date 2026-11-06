package com.helios.app

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/** Use relative attitude for real view changes; a small back-and-forth sway must not accumulate as a turn. */
class SceneMotionGate {
    private var lastMs: Long? = null
    private var turnRadians = 0.0
    private var measuredRotationRadians = 0.0
    private var anchor: DoubleArray? = null
    private var quietSince: Long? = null
    private var turning = false
    var viewDeltaRadians = 0.0
        private set
    var viewChangeReason: String? = null
        private set
    var viewChanged = false
        private set

    fun update(now: Long, motion: MotionSnapshot, pose: DoubleArray? = null): Boolean {
        viewChanged = false; viewChangeReason = null; viewDeltaRadians = 0.0
        val dt = lastMs?.let { ((now - it).coerceIn(0, 200)) / 1_000.0 } ?: 0.0
        lastMs = now
        if (motion.stale || !motion.calibrated || motion.state != MotionState.STILL) {
            turnRadians = 0.0; measuredRotationRadians = 0.0; anchor = null; quietSince = null; turning = false
            return false
        }
        val rate = max(motion.rotationRate, motion.tiltRotationRate)
        if (!rate.isFinite()) return false
        val q = pose?.takeIf { it.size == 4 && it.all(Double::isFinite) }?.let { raw ->
            val norm = sqrt(raw.sumOf { it * it })
            if (norm in 0.9..1.1) DoubleArray(4) { raw[it] / norm } else null
        }
        if (anchor == null) measuredRotationRadians = 0.0
        measuredRotationRadians += rate * dt
        if (q != null) {
            if (anchor == null) anchor = q
            turnRadians = 2 * acos(abs(anchor!!.indices.sumOf { anchor!![it] * q[it] }).coerceIn(0.0, 1.0))
            // A wrong stationary bias can make relative attitude rotate faster than
            // the gyroscope actually measured. Re-anchor that inconsistent attitude;
            // a real slow turn still accumulates matching raw angular evidence.
            if (!turning && rate < 0.12 && turnRadians >= 0.12 &&
                turnRadians > measuredRotationRadians * 1.5 + 0.02) {
                anchor = q; turnRadians = 0.0; measuredRotationRadians = 0.0
            }
        } else if (rate >= 0.12) turnRadians += max(0.0, rate - 0.06) * dt
        if (rate < 0.12) {
            if (quietSince == null) quietSince = now
            if (now - quietSince!! >= 500 && (turning || q == null)) {
                turnRadians = 0.0; measuredRotationRadians = 0.0; turning = false; anchor = q
            }
        } else quietSince = null
        viewDeltaRadians = turnRadians
        if (!turning && (turnRadians >= 0.12 || rate >= 0.55)) {
            turning = true; viewChanged = true
            viewChangeReason = if (rate >= 0.55) "fast_rotation" else if (q != null) "relative_pose" else "rate_integration"
        }
        return !turning && rate < 0.55
    }
    fun reset() { lastMs = null; turnRadians = 0.0; measuredRotationRadians = 0.0; anchor = null; quietSince = null; turning = false; viewChanged = false }
}
