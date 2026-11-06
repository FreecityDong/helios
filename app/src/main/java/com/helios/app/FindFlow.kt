package com.helios.app

/** Supported names are explicit; a cloud guess never expands this list. */
data class FindTarget(val name: String, val aliases: List<String>, val classId: Int?) {
    companion object {
        val supported = listOf(
            FindTarget("手机", listOf("手机"), 67), FindTarget("钥匙", listOf("钥匙"), null),
            FindTarget("杯子", listOf("杯子", "水杯"), 41), FindTarget("水瓶", listOf("水瓶", "瓶子"), 39),
            FindTarget("书", listOf("书", "书本"), 73), FindTarget("遥控器", listOf("遥控器"), 65),
            FindTarget("背包", listOf("背包", "书包"), 24), FindTarget("眼镜", listOf("眼镜"), null),
            FindTarget("钱包", listOf("钱包"), null), FindTarget("椅子", listOf("椅子"), 56))
        fun extract(raw: String, bare: Boolean = false): String? {
            val text = CommandIntentParser.normalize(raw)
            val prefix = listOf("帮我找一下", "帮我找", "寻找", "找一下", "找").firstOrNull { text.startsWith(it) }
            return if (prefix != null) text.removePrefix(prefix).take(20) else if (bare) text.take(20) else null
        }
        fun resolve(name: String?) = supported.firstOrNull { name in it.aliases }
    }
}
data class FindRegion(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 } && right-left >= .01 && bottom-top >= .01
    val centered get() = valid() && (left+right)/2 in .35.. .65 && (top+bottom)/2 in .33.. .67
    fun iou(other: FindRegion): Double {
        val intersection = (minOf(right, other.right)-maxOf(left, other.left)).coerceAtLeast(0.0) *
            (minOf(bottom, other.bottom)-maxOf(top, other.top)).coerceAtLeast(0.0)
        return intersection / ((right-left)*(bottom-top)+(other.right-other.left)*(other.bottom-other.top)-intersection)
    }
    val direction get() = when { (left+right)/2 < .35 -> "画面左侧"; (left+right)/2 > .65 -> "画面右侧"; else -> "画面中间" }
}
data class FindObservation(val found: Boolean, val confidence: Double = 0.0, val region: FindRegion? = null,
    val meters: Float? = null, val depthVerified: Boolean = false, val context: String = "", val hazard: FindHazard? = null)
data class FindHazard(val name: String, val meters: Float)
data class FindCompletion(val taskId: Long, val target: FindTarget, val observation: FindObservation, val confirmedMs: Long, val capturedMs: Long)
data class FindSample(val taskId: Long, val requestId: Long, val target: FindTarget, val createdMs: Long)

/** One capture per still episode. Only observed movement or an explicit resume rearms it. */
class FindFlow {
    var target: FindTarget? = null; private set
    var taskId = 0L; private set
    var attempts = 0; private set
    var roundAttempts = 0; private set
    var round = 0; private set
    var startedMs = 0L; private set
    var roundStartedMs = 0L; private set
    var pending: FindSample? = null; private set
    var pauseReason: String? = null; private set
    var needsConfirmation = false; private set
    var waitingForAdjustment = false; private set
    var result: FindObservation? = null; private set
    var resultCapturedMs = 0L; private set
    var resultRequestId = 0L; private set
    var speechStartedMs: Long? = null; private set
    var everFound = false; private set
    var targetReached = false; private set
    var completion: FindCompletion? = null; private set
    var guidanceCount = 0; private set
    private var stableSince: Long? = null
    private var lastEndedMs: Long? = null
    private var sequence = 0L
    private var resumeRoundPending = false
    private var episodeConsumed = false
    val active get() = target != null
    fun start(value: FindTarget, now: Long) {
        cancel(); taskId++; target=value;startedMs=now;attempts=0;round=0;newRound(now)
    }
    private fun newRound(now: Long) {
        round++;roundAttempts=0;roundStartedMs=now;waitingForAdjustment=false;pauseReason=null;stableSince=null
        resumeRoundPending=false;episodeConsumed=false
    }
    fun cancel() {
        target=null;pending=null;result=null;speechStartedMs=null;stableSince=null;lastEndedMs=null
        pauseReason=null;needsConfirmation=false;waitingForAdjustment=false;everFound=false;targetReached=false;completion=null
        guidanceCount=0;resumeRoundPending=false;episodeConsumed=false
    }
    fun invalidate(reason: String, confirm: Boolean = false) {
        if(!active || completion!=null) return
        pending=null;result=null;speechStartedMs=null;stableSince=null;targetReached=false;pauseReason=reason
        needsConfirmation=needsConfirmation || confirm
    }
    fun observeMotion(moving: Boolean) { if(active && completion==null && moving) resumeRoundPending=true }
    fun resume(now: Long) { if(completion!=null) return;needsConfirmation=false;invalidate("重新观察");newRound(now) }
    fun waitForAdjustment(reason: String) { if(completion!=null) return;invalidate(reason);waitingForAdjustment=true;resumeRoundPending=false }
    fun sample(now: Long, safe: Boolean): FindSample? {
        if(!active || completion!=null || needsConfirmation || result!=null) return null
        if(!safe) { invalidate("等待停稳或安全提醒结束");return null }
        if(resumeRoundPending) newRound(now)
        if(waitingForAdjustment || episodeConsumed) return null
        if(pauseReason!=null) { pauseReason=null;stableSince=null }
        if(stableSince==null) stableSince=now
        if(now-stableSince!!<1_500 || pending!=null || lastEndedMs?.let { now-it<3_000 }==true) return null
        episodeConsumed=true;attempts++;roundAttempts++
        return FindSample(taskId,++sequence,target!!,now).also { pending=it }
    }
    fun accept(sample: FindSample, observation: FindObservation, captured: Long, now: Long): String {
        if(pending!=sample || !active || sample.taskId!=taskId) return "late"
        pending=null;lastEndedMs=now
        if(now-sample.createdMs>=25_000) return "expired"
        if(now-captured !in 0..8_000) return "stale"
        if(!observation.found || !observation.confidence.isFinite() || observation.confidence !in .80..1.0 || observation.region?.valid()!=true) return "not_found"
        result=observation;resultCapturedMs=captured;resultRequestId=sample.requestId
        targetReached=observation.region.centered && observation.depthVerified && observation.meters?.let { it.isFinite() && it in .2f..0.5f }==true
        if(targetReached) completion=FindCompletion(taskId,target!!,observation,now,captured)
        val outcome=if(targetReached) "reached" else if(everFound) "guiding" else "found"
        everFound=true
        return outcome
    }
    fun completeGuidance() { if(completion!=null) return;if(result!=null) guidanceCount++;result=null;speechStartedMs=null;targetReached=false;waitingForAdjustment=true;pauseReason="等待运动后重新停稳" }
    fun admitSpeech(now: Long): Boolean { if(!resultValid(now)) return false;speechStartedMs=now;return true }
    fun resultValid(now: Long) = active && result!=null && now-resultCapturedMs in 0..8_000 && !needsConfirmation
}

/** No search task exists until a fresh spoken confirmation is received. */
class FindSelection {
    var target: FindTarget? = null; private set
    private var deadline=0L
    fun propose(value: FindTarget, now: Long) { target=value;deadline=now+15_000 }
    fun clear() { target=null }
    fun expire(now: Long): Boolean {
        if(target!=null && now>=deadline) { clear();return true };return false
    }
    fun confirm(now: Long): FindTarget? { expire(now);val value=target;clear();return value }
}
