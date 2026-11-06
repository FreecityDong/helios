package com.helios.app

import kotlin.math.sqrt
import kotlin.math.max

enum class MotionState { UNKNOWN, STILL, MOVING, SHAKING }

data class MotionSnapshot(
    val state: MotionState = MotionState.UNKNOWN,
    val stale: Boolean = true,
    val calibrated: Boolean = false,
    val gravity: List<Double> = emptyList(),
    val dynamicAcceleration: Double = 0.0,
    val peakAcceleration: Double = 0.0,
    val rotationRate: Double = 0.0,
    val tiltRotationRate: Double = 0.0,
    val visualMotion: VisualMotionKind = VisualMotionKind.UNKNOWN,
    val visualConsensusTiles: Int = 0,
    val source: String = "imu",
    val shakeReason: String = "none",
    val calibrationReason: String = "none",
    val orientationDelta: Double = 0.0,
    val gyroBias: List<Double> = emptyList(),
    val movementAcceleration: Double = 0.0,
)

/** Gemini 2 acceleration is in m/s²; calibration records gravity in the worn camera frame. */
class MotionTracker {
    private var config=MotionConfig()
    @Synchronized internal fun configure(value:MotionConfig) {
        if(config==value) return
        config=value
        candidate=MotionState.UNKNOWN;candidateSince=0
        // Keep the user's worn gravity/bias calibration, then debounce new state.
    }
    private val acceleration = ArrayDeque<Pair<Long, DoubleArray>>()
    private val rotation = ArrayDeque<Pair<Long, DoubleArray>>()
    private val visual = ArrayDeque<VisualMotionEvidence>()
    private var gravity: DoubleArray? = null
    private var gyroBias: DoubleArray? = null
    private var pendingCalibration = false
    private var candidate = MotionState.UNKNOWN
    private var candidateSince = 0L
    private var stable = MotionState.UNKNOWN
    private var lastAccelMs = 0L
    private var lastGyroMs = 0L

    @Synchronized fun requestCalibration() {
        gravity = null; gyroBias = null
        pendingCalibration = true
        acceleration.clear(); rotation.clear(); visual.clear()
        candidate = MotionState.UNKNOWN; stable = MotionState.UNKNOWN
    }

    @Synchronized fun reset() {
        gravity = null; gyroBias = null; pendingCalibration = false
        acceleration.clear(); rotation.clear(); visual.clear()
        candidate = MotionState.UNKNOWN; stable = MotionState.UNKNOWN
        lastAccelMs = 0; lastGyroMs = 0
    }

    @Synchronized fun addAcceleration(timeMs: Long, vector: DoubleArray) {
        if (vector.size < 3 || vector.take(3).any { !it.isFinite() }) return
        lastAccelMs = timeMs
        acceleration.addLast(timeMs to vector.copyOf(3))
        trim(timeMs)
        if (pendingCalibration && acceleration.size >= 60 && acceleration.last().first - acceleration.first().first >= 1_200) {
            val values = acceleration.map { it.second }
            val mean = DoubleArray(3) { axis -> values.map { it[axis] }.average() }
            val deviation = values.map { norm(diff(it, mean)) }.average()
            val magnitude = norm(mean)
            val gyroStable = rotation.size >= 30 && rotation.map { norm(it.second) }.average() < 0.1
            if (deviation < 0.45 && magnitude in 8.5..10.8 && gyroStable) {
                gravity = mean
                gyroBias = DoubleArray(3) { axis -> rotation.map { it.second[axis] }.average() }
                pendingCalibration = false
                visual.clear()
                candidate = MotionState.UNKNOWN; stable = MotionState.UNKNOWN
            }
        }
    }

    @Synchronized fun addRotation(timeMs: Long, vector: DoubleArray) {
        if (vector.size < 3 || vector.take(3).any { !it.isFinite() }) return
        lastGyroMs = timeMs
        rotation.addLast(timeMs to vector.copyOf(3))
        trim(timeMs)
    }

    @Synchronized fun addVisualEvidence(evidence: VisualMotionEvidence) {
        visual.addLast(evidence)
        while (visual.isNotEmpty() && evidence.timeMs - visual.first().timeMs > 1_500) visual.removeFirst()
    }

    @Synchronized fun snapshot(nowMs: Long): MotionSnapshot {
        val reference = gravity
        val stale = lastAccelMs == 0L || lastGyroMs == 0L ||
            nowMs - lastAccelMs !in 0..1_000 || nowMs - lastGyroMs !in 0..1_000
        if (stale || reference == null || acceleration.size < 30 || rotation.size < 30) {
            if (stale) {
                visual.clear()
            }
            stable = MotionState.UNKNOWN; candidate = MotionState.UNKNOWN
            return MotionSnapshot(stale = stale, calibrated = reference != null, gravity = reference?.toList() ?: emptyList())
        }
        val recentVectors = acceleration.filter { nowMs - it.first in 0..1_500 }.map { it.second }
        // Torso pitch is not evidence of camera displacement. Preserve the worn
        // reference until explicit recalibration/reset; retain the delta as a diagnostic.
        val orientationDelta = if (recentVectors.size >= 30) {
            val mean = DoubleArray(3) { axis -> recentVectors.map { it[axis] }.average() }
            norm(diff(mean, reference))
        } else 0.0
        val recentAccel = acceleration.filter { nowMs - it.first in 0..1_500 }.map { norm(diff(it.second, reference)) }
        val recentGyroVectors = rotation.filter { nowMs - it.first in 0..1_500 }.map { it.second }
        val recentRotation = recentGyroVectors.map { norm(it) }
        if (recentAccel.size < 20 || recentRotation.size < 20) return MotionSnapshot(calibrated = true, gravity = reference.toList())
        val accelerationP90 = percentile(recentAccel, 0.9)
        // Motion uses variation around the current short-window gravity/offset,
        // not deviation from a fixed worn orientation. Keep the original channel
        // for impact/tilt candidates and diagnostics.
        val currentMean = DoubleArray(3) { axis -> recentVectors.map { it[axis] }.average() }
        val movementAcceleration = percentile(recentVectors.map { norm(diff(it,currentMean)) },0.9)
        val accelerationPeak = recentAccel.maxOrNull() ?: 0.0
        val impactSamples = recentAccel.count { it >= config.impact }
        val gyroP90 = percentile(recentRotation, 0.9)
        // Gemini 2's accelerometer and gyroscope share an IMU frame. Rotation parallel
        // to calibrated gravity is a normal yaw turn; only perpendicular (tilt) rotation
        // can contribute to this candidate. This is not a fall decision/help trigger.
        val gravityNorm = norm(reference)
        val tiltP90 = percentile(recentGyroVectors.map { gyro ->
            val parallel = gyro.indices.sumOf { gyro[it] * reference[it] } / gravityNorm
            sqrt(max(0.0, norm(gyro) * norm(gyro) - parallel * parallel))
        }, 0.9)
        val sustainedAbruptMotion = accelerationP90 >= config.shakeAcceleration && tiltP90 >= config.shakeTilt
        val briefImpactMotion = impactSamples >= 3 && tiltP90 >= config.impactTilt
        val shakeReason = when {
            briefImpactMotion -> "impact_with_rotation"
            sustainedAbruptMotion -> "strong_acceleration_with_rotation"
            else -> "none"
        }
        val imuCandidate = when {
            shakeReason != "none" -> MotionState.SHAKING
            movementAcceleration >= config.acceleration || gyroP90 >= config.gyro -> MotionState.MOVING
            else -> MotionState.STILL
        }
        val freshVisual = visual.filter { nowMs - it.timeMs in 0..1_200 }
        val pair = freshVisual.takeLast(2)
        val visualMoving = pair.size == 2 && pair.all {
            it.kind == VisualMotionKind.GLOBAL_SHIFT && it.consensusTiles >= 8
        } && pair[1].timeMs - pair[0].timeMs in 200..600 &&
            (pair[0].shiftX * pair[1].shiftX + pair[0].shiftY * pair[1].shiftY).toDouble() >=
            pair[0].shiftPixels * pair[1].shiftPixels * 0.7
        val next = if (imuCandidate == MotionState.STILL && visualMoving) MotionState.MOVING else imuCandidate
        val visualLatest = freshVisual.lastOrNull()
        if (next != candidate) { candidate = next; candidateSince = nowMs }
        val holdMs = if (next == MotionState.SHAKING) config.shakingMs else if (next == MotionState.STILL) config.stillMs else config.movingMs
        if (nowMs - candidateSince >= holdMs) stable = next
        return MotionSnapshot(stable, stale = false, calibrated = true, gravity = reference.toList(),
            dynamicAcceleration = accelerationP90, peakAcceleration = accelerationPeak, movementAcceleration = movementAcceleration,
            rotationRate = gyroP90, tiltRotationRate = tiltP90,
            visualMotion = visualLatest?.kind ?: VisualMotionKind.UNKNOWN,
            visualConsensusTiles = visualLatest?.consensusTiles ?: 0,
            source = if (imuCandidate == MotionState.STILL && visualMoving) "imu+rgb" else "imu",
            shakeReason = shakeReason, orientationDelta = orientationDelta, gyroBias = gyroBias?.toList() ?: emptyList())
    }

    private fun trim(nowMs: Long) {
        while (acceleration.isNotEmpty() && nowMs - acceleration.first().first > 3_000) acceleration.removeFirst()
        while (rotation.isNotEmpty() && nowMs - rotation.first().first > 3_000) rotation.removeFirst()
    }

    private fun diff(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
    private fun norm(a: DoubleArray) = sqrt(a.take(3).sumOf { it * it })
    private fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * fraction).toInt()]
    }
}
