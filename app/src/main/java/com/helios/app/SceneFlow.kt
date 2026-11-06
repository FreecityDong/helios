package com.helios.app

data class SceneTicket(val id: Long, val detailed: Boolean, val createdMs: Long, val expiresMs: Long)

/** Main-thread task validity, independent of network and Android. A new detail request always takes a new frame. */
class SceneFlow(private val stableMs: Long = 1_500, private val lifetimeMs: Long = 25_000,
    private val queueMs: Long = 8_000, private val speechWatchdogMs: Long = 90_000) {
    var ticket: SceneTicket? = null
        private set
    var pending = false
        private set
    var speaking = false
        private set
    var playByMs = 0L
        private set
    private var speechDeadlineMs = 0L
    private var sequence = 0L
    private var stillSince: Long? = null
    private var movingSince: Long? = null
    private var autoUsed = false
    var rearmReason: String? = null
        private set
    var autoTriggerReason = "initial_still"
        private set
    private var ready = false

    fun observe(now: Long, safe: Boolean, moving: Boolean, viewChanged: Boolean = false): Boolean {
        rearmReason = null
        if (viewChanged && autoUsed) { autoUsed = false; rearmReason = "view_changed"; autoTriggerReason = "view_changed" }
        ready = safe
        if (moving) {
            if (movingSince == null) movingSince = now
            if (now - movingSince!! >= 1_000 && autoUsed) {
                autoUsed = false; rearmReason = "movement_confirmed"; autoTriggerReason = "movement_stopped"
            }
        } else movingSince = null
        if (!safe) { stillSince = null; invalidate(); return false }
        if (stillSince == null) stillSince = now
        if (ticket != null && !valid(ticket!!.id, now)) invalidate()
        return now - stillSince!! >= stableMs && !autoUsed && !pending
    }
    fun begin(now: Long, detailed: Boolean, requireStable: Boolean = true): SceneTicket? {
        if (!ready || requireStable && stillSince?.let { now - it >= stableMs } != true) return null
        autoUsed = true
        return SceneTicket(++sequence, detailed, now, now + lifetimeMs).also { ticket = it; pending = true; speaking = false; playByMs = 0L }
    }
    fun valid(id: Long, now: Long): Boolean = ready && ticket?.let {
        it.id == id && now < when { pending -> it.expiresMs; speaking -> speechDeadlineMs; else -> playByMs }
    } == true
    fun finish(id: Long, now: Long): Boolean {
        if (!pending || !valid(id, now)) return false
        pending = false
        playByMs = now + queueMs
        return true
    }
    fun admitSpeech(id: Long, now: Long): Boolean {
        if (pending || speaking || !valid(id, now)) return false
        speaking = true
        speechDeadlineMs = now + speechWatchdogMs
        return true
    }
    fun complete(id: Long) { if (ticket?.id == id) invalidate() }
    fun cancel() { autoUsed = true; invalidate() }
    private fun invalidate() { ticket = null; pending = false; speaking = false; playByMs = 0L; speechDeadlineMs = 0L }
}


/** Debug recognition tests may ignore motion, but still require a live camera and no fall episode. */
object SceneGate {
    fun ready(state: OperationalState, now: Long, lastFrameMs: Long, testMode: Boolean, blockedUntil: Long): Boolean {
        if (state.device != DeviceState.STREAMING || state.fall != FallState.IDLE ||
            lastFrameMs <= 0 || now - lastFrameMs !in 0..1_000) return false
        if (testMode) return true
        return state.motion.calibrated && !state.motion.stale && state.motion.state == MotionState.STILL &&
            state.motion.rotationRate < 0.55 && state.motion.tiltRotationRate < 0.55 && now >= blockedUntil
    }
}
