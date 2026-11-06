package com.helios.app

enum class SpeechPriority { ENVIRONMENT, ORDINARY, SAFETY, WARNING_SAFETY, URGENT_SAFETY, FIND_SUCCESS, FALL }
data class SpeechRequest(
    val id: String,
    val key: String,
    val text: String,
    val priority: SpeechPriority,
    val createdMs: Long,
    val expiresMs: Long,
    val riskMeters: Float? = null,
    val riskDirection: AvoidanceDirection? = null,
    val riskBand: ImageHeightBand? = null,
    val riskMode: RiskSpeechMode? = null,
    val riskNavigating: Boolean? = null,
    val riskDetail: Boolean = false,
    val inputAfterSpeech: Boolean = false,
    val riskSpace: SpatialHint? = null,
    val riskObservedMs: Long? = null,
    val clearanceFeedback: Boolean = false,
    val clearanceMovingOnly: Boolean = false,
)
enum class SpeechDecision { START, QUEUED, DUPLICATE, EXPIRED, OVERFLOW }

/** Ordered event entry; all methods are called on the service main thread. */
class SpeechArbiter {
    var active: SpeechRequest? = null
        private set
    private val pending = mutableListOf<SpeechRequest>()
    private val recentlyStarted = mutableMapOf<String, Long>()

    fun submit(request: SpeechRequest, nowMs: Long, queueOnly: Boolean = false): SpeechDecision {
        prune(nowMs)
        if (request.expiresMs <= nowMs) return SpeechDecision.EXPIRED
        val nav = request.key.startsWith("risk:nav:")
        val current = active
        val sameEpisode = nav && current?.key?.startsWith("risk:nav:") == true &&
            request.key.split(':').getOrNull(2) == current.key.split(':').getOrNull(2)
        val navigationClear = nav && request.key.split(':').getOrNull(3) in
            setOf(RiskEventKind.AREA_CLEARED.name,RiskEventKind.HEADING_CLEAR.name)
        val turnChanged = sameEpisode && request.key.split(':').getOrNull(3)==RiskEventKind.TURN_GUIDANCE.name &&
            current?.key?.split(':')?.getOrNull(3)==RiskEventKind.TURN_GUIDANCE.name && request.riskDirection!=current.riskDirection
        val newDirection = turnChanged || sameEpisode && request.riskDirection != null &&
            request.riskDirection != current?.riskDirection && request.priority == current?.priority
        if(nav) pending.removeAll {queued ->
            queued.key.startsWith("risk:nav:") && queued.key.split(':').getOrNull(2)==request.key.split(':').getOrNull(2) &&
                (navigationClear || SpeechInterruptionPolicy.sameHazard(queued,request) && queued.riskDetail==request.riskDetail)
        }
        val last = recentlyStarted[request.key]
        if (active?.key == request.key || pending.any { it.key == request.key } ||
            (last != null && nowMs - last < 4_000)) return SpeechDecision.DUPLICATE
        val closerUrgentRisk = current != null &&
            request.priority == SpeechPriority.URGENT_SAFETY && current.priority == SpeechPriority.URGENT_SAFETY &&
            request.key.startsWith("risk:") && current.key.startsWith("risk:") &&
            request.riskMeters?.let { incoming ->
                current.riskMeters?.let { active -> incoming.isFinite() && active.isFinite() &&
                    incoming <= active - 0.20f } ?: false
            } == true
        val sameHazard = sameEpisode && current?.riskBand == request.riskBand
        if (sameHazard && !navigationClear && !newDirection && !closerUrgentRisk &&
            request.riskMode != RiskSpeechMode.ALERT && current?.priority == request.priority)
            return SpeechDecision.DUPLICATE
        val mayInterrupt=SpeechInterruptionPolicy.mayInterrupt(request,current)
        if (mayInterrupt && !queueOnly) {
            // Keep interrupted success speech so it can restart after the urgent sentence.
            if(current!=null && ((request.priority==SpeechPriority.FIND_SUCCESS &&
                current.priority in setOf(SpeechPriority.SAFETY,SpeechPriority.WARNING_SAFETY,SpeechPriority.URGENT_SAFETY)) ||
                (current.priority==SpeechPriority.FIND_SUCCESS && SpeechInterruptionPolicy.freshUrgent(request)) ||
                (current.key.startsWith("find:success-info:") && request.priority in setOf(SpeechPriority.SAFETY,SpeechPriority.WARNING_SAFETY,SpeechPriority.URGENT_SAFETY))) &&
                current.expiresMs>nowMs && pending.none { it.id==current.id }) {
                if(pending.size>=8) pending.minWithOrNull(compareBy<SpeechRequest> { it.priority.ordinal }.thenBy { it.createdMs })
                    ?.takeIf { it.priority.ordinal<=current.priority.ordinal }?.let { pending.remove(it) }
                if(pending.size<8) pending.add(current)
            }
            active = request
            recentlyStarted[request.key] = nowMs
            return SpeechDecision.START
        }
        if (nav) {
            val episode = request.key.split(':').getOrNull(2)
            pending.removeAll {it.key.startsWith("risk:nav:") && it.key.split(':').getOrNull(2)==episode &&
                it.riskBand==request.riskBand && it.riskDetail==request.riskDetail}
        }
        if(pending.size>=8 && request.priority==SpeechPriority.FIND_SUCCESS) {
            pending.minByOrNull {it.priority.ordinal}?.takeIf {it.priority.ordinal<request.priority.ordinal}
                ?.let {pending.remove(it)}
        }
        if (pending.size >= 8) return SpeechDecision.OVERFLOW
        pending.add(request)
        return SpeechDecision.QUEUED
    }

    fun complete(id: String, nowMs: Long, advance: Boolean = true): SpeechRequest? {
        if (active?.id != id) return null
        active = null
        return if (advance) resume(nowMs) else null
    }

    fun resume(nowMs: Long): SpeechRequest? {
        if (active != null) return null
        prune(nowMs)
        val next = pending.maxWithOrNull(compareBy<SpeechRequest> { it.priority.ordinal }.thenBy { -it.createdMs })
        if (next != null) {
            pending.remove(next)
            active = next
            recentlyStarted[next.key] = nowMs
        }
        return next
    }

    /** A resumed background warning may race with a new command recorder. */
    fun deferActiveForVoice(id:String,nowMs:Long):Boolean {
        val request=active?.takeIf {it.id==id} ?: return false
        active=null;recentlyStarted.remove(request.key);prune(nowMs)
        if(request.expiresMs>nowMs) {
            if(pending.size>=8) pending.minWithOrNull(compareBy<SpeechRequest> {it.priority.ordinal}.thenBy {it.createdMs})?.let {pending.remove(it)}
            pending.add(request)
        }
        return true
    }

    fun cancelPrefix(prefix: String): SpeechRequest? = cancelWhere { it.key.startsWith(prefix) }

    fun cancelWhere(predicate: (SpeechRequest) -> Boolean): SpeechRequest? {
        pending.removeAll(predicate)
        val removed = active?.takeIf(predicate)
        if (removed != null) active = null
        return removed
    }

    fun retainedIds(): Set<String> = pending.map { it.id }.toSet() + listOfNotNull(active?.id)

    fun clear() { active = null; pending.clear(); recentlyStarted.clear() }

    private fun prune(nowMs: Long) {
        pending.removeAll { it.expiresMs <= nowMs }
        recentlyStarted.entries.removeAll { nowMs - it.value > 4_000 }
    }
}
