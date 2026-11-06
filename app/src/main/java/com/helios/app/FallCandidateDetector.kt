package com.helios.app

import kotlin.math.*

/** Adjustable prototype gates, not validated population fall thresholds. */
data class FallCandidateConfig(val impactMetersPerSecond2: Double=26.0,val lowGravity: Double=4.0,
    val tiltRadiansPerSecond: Double=1.0,val changedDegrees: Double=45.0,
    val quietMs: Long=1_500,val eventWindowMs: Long=4_000)
data class FallCandidate(val timeMs: Long,val impact: Double,val lowGravityBeforeImpact: Boolean,
    val changedDegrees: Double,val peakTilt: Double)

data class FallDetectorStatus(val reason: String="unavailable",val rawPeakMs2: Double=0.0,
    val changedDegrees: Double=0.0,val tiltRadS: Double=0.0,val quietMs: Long=0) {
    val displayReason get()=when(reason) {
        "impact_below_threshold"->"冲击未达门槛"
        "angle_below_threshold"->"姿态变化不足"
        "tilt_below_threshold"->"倾斜转动不足"
        "pre_pose_unconfirmed"->"事件前姿态证据不足"
        "post_activity"->"事件后仍在活动"
        "post_gravity_unavailable"->"事后重力方向不可用"
        "quiet_confirming"->"确认事后低活动"
        "waiting_upright_rearm"->"等待恢复佩戴姿态"
        "event_window_expired"->"候选窗口已结束"
        "candidate_emitted"->"已触发疑似事件"
        "collecting","waiting_post_window"->"采集时序证据"
        else->"数据不可用"
    }
}

/** Bounded raw Gemini IMU history, independent of RGB inference and SHAKING debounce. */
class FallCandidateDetector(config: FallCandidateConfig=FallCandidateConfig()) {
    @Volatile var config=config;private set
    @Synchronized fun configure(value:FallCandidateConfig) {
        if(config==value) return
        config=value;resetEvent();accel.clear();gyro.clear();lastAccel=0;lastGyro=0
        status=FallDetectorStatus()
    }
    private val accel=ArrayDeque<Pair<Long,DoubleArray>>()
    private val gyro=ArrayDeque<Pair<Long,DoubleArray>>()
    private var impactAt=0L;private var peak=0.0;private var lowBefore=false
    private var quietAt=0L;private var lastAccel=0L;private var lastGyro=0L
    private var armed=true;private var recoveredAt=0L
    @Volatile var status=FallDetectorStatus();private set
    @Synchronized fun acceleration(now: Long,v: DoubleArray) {
        if(v.size!=3 || v.any { !it.isFinite() } || !norm(v).isFinite() || now<lastAccel) return
        if(lastAccel>0 && now-lastAccel>300) resetEvent()
        lastAccel=now;accel.addLast(now to v.copyOf());trim(now)
        val magnitude=norm(v)
        if(armed && impactAt==0L && magnitude>=config.impactMetersPerSecond2) {
            impactAt=now;peak=magnitude;quietAt=0
            lowBefore=accel.any {now-it.first in 20..800 && norm(it.second)<config.lowGravity}
        } else if(impactAt>0 && now-impactAt<300) peak=maxOf(peak,magnitude)
    }
    @Synchronized fun rotation(now: Long,v: DoubleArray) {
        if(v.size!=3 || v.any { !it.isFinite() } || !norm(v).isFinite() || now<lastGyro) return
        if(lastGyro>0 && now-lastGyro>300) resetEvent()
        lastGyro=now;gyro.addLast(now to v.copyOf());trim(now)
    }
    @Synchronized fun poll(now: Long,gravity: List<Double>,enabled: Boolean): FallCandidate? {
        if(!enabled || gravity.size!=3 || gravity.any { !it.isFinite() } || norm(gravity.toDoubleArray()) !in 8.5..10.8 ||
            now-lastAccel !in 0..300 || now-lastGyro !in 0..300) {
            status=FallDetectorStatus("unavailable");resetEvent();accel.clear();gyro.clear();return null
        }
        val reference=gravity.toDoubleArray();val recent=accel.filter {now-it.first in 0..500}
        val rotations=gyro.filter {now-it.first in 0..500}
        if(recent.size<20 || rotations.size<20 || recent.last().first-recent.first().first<300) {status=FallDetectorStatus("collecting");return null}
        val mean=DoubleArray(3) {axis -> recent.map {it.second[axis]}.average()}
        val g=norm(mean)
        val angle=if(g>0.000001 && g.isFinite()) Math.toDegrees(acos((dot(reference,mean)/(norm(reference)*g)).coerceIn(-1.0,1.0))) else 0.0
        val quiet=g in 8.5..10.8 && recent.map {norm(DoubleArray(3) {axis->it.second[axis]-mean[axis]})}.average()<0.6 &&
            rotations.map {norm(it.second)}.average()<0.2
        val rawPeak=accel.filter {now-it.first in 0..1_500}.maxOfOrNull {norm(it.second)} ?: 0.0
        val recentTilt=gyro.filter {now-it.first in 0..1_500}.maxOfOrNull {
            val parallel=dot(it.second,reference)/norm(reference)
            sqrt(max(0.0,norm(it.second).pow(2)-parallel*parallel))
        } ?: 0.0
        status=FallDetectorStatus(if(g<=0.000001) "post_gravity_unavailable" else "impact_below_threshold",rawPeak,angle,recentTilt)
        if(!armed) {
            status=status.copy(reason="waiting_upright_rearm")
            if(quiet && angle<20) {
                if(recoveredAt==0L) recoveredAt=now
                if(now-recoveredAt>=5_000) {armed=true;recoveredAt=0}
            } else recoveredAt=0
            return null
        }
        if(impactAt==0L) return null
        if(now-impactAt>config.eventWindowMs) {status=status.copy(reason="event_window_expired");resetEvent();return null}
        val pre=accel.filter {impactAt-it.first in 100..800}
        val preMean=if(pre.size>=20) DoubleArray(3) {axis->pre.map {it.second[axis]}.average()} else null
        // Do not infer an abrupt change from a camera that was already lying down.
        val uprightBefore=preMean!=null && norm(preMean) in 8.5..10.8 &&
            dot(reference,preMean)/(norm(reference)*norm(preMean))>cos(Math.toRadians(20.0))
        val tilt=gyro.filter {it.first-impactAt in -800..500}.maxOfOrNull {
            val p=dot(it.second,reference)/norm(reference);sqrt(max(0.0,norm(it.second).pow(2)-p*p))
        } ?: 0.0
        status=FallDetectorStatus(when {
            g<=0.000001 -> "post_gravity_unavailable"
            now-impactAt<300 -> "waiting_post_window"
            !uprightBefore -> "pre_pose_unconfirmed"
            angle<config.changedDegrees -> "angle_below_threshold"
            tilt<config.tiltRadiansPerSecond -> "tilt_below_threshold"
            !quiet -> "post_activity"
            else -> "quiet_confirming"
        },peak,angle,tilt,if(quietAt>0) now-quietAt else 0)
        if(now-impactAt<300 || !uprightBefore || angle<config.changedDegrees || tilt<config.tiltRadiansPerSecond) {quietAt=0;return null}
        if(!quiet) {quietAt=0;return null}
        if(quietAt==0L) quietAt=now
        if(now-quietAt<config.quietMs) return null
        val candidate=FallCandidate(now,peak,lowBefore,angle,tilt)
        status=status.copy(reason="candidate_emitted",quietMs=now-quietAt)
        armed=false;resetEvent();return candidate
    }
    @Synchronized fun rawSample(now: Long): Pair<List<Double>,List<Double>>? {
        if(now-lastAccel !in 0..300 || now-lastGyro !in 0..300) return null
        return (accel.lastOrNull()?.second?.toList() ?: return null) to (gyro.lastOrNull()?.second?.toList() ?: return null)
    }
    @Synchronized fun reset() {accel.clear();gyro.clear();resetEvent();lastAccel=0;lastGyro=0;armed=true;recoveredAt=0;status=FallDetectorStatus()}
    private fun resetEvent() {impactAt=0;peak=0.0;quietAt=0;lowBefore=false}
    private fun trim(now: Long) {
        while(accel.isNotEmpty() && (now-accel.first().first>6_000 || accel.size>1_500)) accel.removeFirst()
        while(gyro.isNotEmpty() && (now-gyro.first().first>6_000 || gyro.size>1_500)) gyro.removeFirst()
    }
    private fun norm(v: DoubleArray)=sqrt(v.sumOf {it*it})
    private fun dot(a: DoubleArray,b: DoubleArray)=a.indices.sumOf {a[it]*b[it]}
}
object FallSignals {val detector=FallCandidateDetector()}
