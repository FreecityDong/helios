package com.helios.app

enum class RiskSpeechMode { ALERT, DIRECTION_UPDATE, REPEAT, CLEAR }
data class RiskSpeechAssessment(val mode: RiskSpeechMode?, val reason: String) {
    val allowed get() = mode != null
}

/** Speech frequency only: the detector and its distance thresholds remain independent. */
class RiskSpeechPolicy(private val config: NavigationConfig = NavigationConfig()) {
    private data class Spoken(
        var seenMs: Long, val startedMs: Long, val priority: SpeechPriority,
        val meters: Float, val direction: AvoidanceDirection?, val requestId: String,
        var observedPriority: SpeechPriority = priority, var farthestMeters: Float = meters,
        var needsAlert: Boolean = false, var navigating: Boolean = false,
        var space: SpatialHint? = null,
    )
    private val spoken = linkedMapOf<String, Spoken>()

    private fun identity(request: SpeechRequest): String {
        val p = request.key.split(':')
        // Labels may flicker between known/unknown for one surface. Episode + height is more stable.
        return if (p.take(2) == listOf("risk", "nav") && p.size >= 4)
            "risk:nav:${p[2]}:${request.riskBand ?: ImageHeightBand.FRONT}"
        else request.key
    }
    private fun cleared(request: SpeechRequest) = request.key.split(':').getOrNull(3) in
        setOf(RiskEventKind.HEADING_CLEAR.name, RiskEventKind.AREA_CLEARED.name, RiskEventKind.LEFT_FORWARD_VIEW.name)

    fun assess(request: SpeechRequest, now: Long, moving: Boolean, occupied: Boolean): RiskSpeechAssessment {
        if (request.expiresMs <= now) return RiskSpeechAssessment(null, "expired")
        if (cleared(request)) {
            val prefix = request.key.split(':').take(3).joinToString(":") + ":"
            spoken.keys.removeAll { it.startsWith(prefix) }
            return RiskSpeechAssessment(RiskSpeechMode.CLEAR, "hazard_cleared")
        }
        val meters = request.riskMeters
        if (meters == null || !meters.isFinite()) return RiskSpeechAssessment(null, "invalid_distance")
        spoken.entries.removeAll { now - it.value.seenMs !in 0..30_000 }
        val previous = spoken[identity(request)]
        if (previous == null) return RiskSpeechAssessment(RiskSpeechMode.ALERT, "new_hazard")
        previous.seenMs = now
        val escalated = request.priority.ordinal > previous.observedPriority.ordinal
        previous.observedPriority = request.priority
        previous.farthestMeters = maxOf(previous.farthestMeters, meters)
        val closer = meters <= previous.farthestMeters - config.closerMeters
        if (escalated || closer) previous.needsAlert = true
        if (previous.needsAlert) return RiskSpeechAssessment(RiskSpeechMode.ALERT,
            if (escalated) "risk_escalated" else "materially_closer")
        if (request.riskSpace?.let {it!=previous.space} ?: (moving && request.riskDirection != previous.direction)) {
            return if (occupied) RiskSpeechAssessment(null, "direction_update_deferred")
                else RiskSpeechAssessment(RiskSpeechMode.DIRECTION_UPDATE,
                    if (request.riskDirection == null) "direction_revoked" else "direction_changed")
        }
        if (!moving) return RiskSpeechAssessment(null, "stationary_unchanged_hazard")
        if (occupied) return RiskSpeechAssessment(null, "repeat_yields_to_user_task")
        val interval = when (request.priority) {
            SpeechPriority.URGENT_SAFETY -> config.urgentRepeatMs
            SpeechPriority.WARNING_SAFETY -> config.warningRepeatMs
            else -> config.cautionRepeatMs
        }
        return if (now - previous.startedMs >= interval) RiskSpeechAssessment(RiskSpeechMode.REPEAT, "moving_repeat")
            else RiskSpeechAssessment(null, "repeat_interval")
    }

    /** Commit only after TTS accepted the sentence, never for dropped or queued requests. */
    fun started(request: SpeechRequest, now: Long, moving: Boolean = false) {
        if (request.clearanceFeedback || !request.key.startsWith("risk:") || cleared(request)) return
        val meters = request.riskMeters?.takeIf { it.isFinite() } ?: return
        val key = identity(request)
        val old = spoken[key]
        val directionOnly = request.riskMode == RiskSpeechMode.DIRECTION_UPDATE && old != null
        spoken[key] = if (directionOnly) old!!.copy(seenMs = now, startedMs = now,
            direction = request.riskDirection, space=request.riskSpace, requestId = request.id)
            else Spoken(now, now, request.priority, meters, request.riskDirection, request.id, navigating = moving, space=request.riskSpace)
        while (spoken.size > 32) spoken.remove(spoken.keys.first())
    }
    fun failed(request: SpeechRequest) {
        val key = identity(request)
        if (spoken[key]?.requestId == request.id) spoken.remove(key)
    }
    fun stoppedMoving() { spoken.values.forEach { it.navigating = false } }
    fun reset() { spoken.clear() }
}
