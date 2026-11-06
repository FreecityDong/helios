package com.helios.app

/** Local interaction policy; a candidate is never a diagnosis. Times use elapsedRealtime. */
data class FallConfig(val responseMs: Long=20_000,val wallMs: Long=40_000,
    val repeatHelpMs: Long=10_000,val helpGapMs: Long=6_000,val recoveryMs: Long=30_000)
enum class FallReply { OK, HELP, CANCEL, UNKNOWN }
enum class FallEffectKind { ASK, WARN, HELP, RELEASE }
data class FallEffect(val episode: Long,val sequence: Int,val kind: FallEffectKind,val text: String)

class FallFlow(config: FallConfig=FallConfig()) {
    var config=config;private set
    fun configure(value:FallConfig) {check(!active);config=value}
    var state=FallState.IDLE;private set
    var episode=0L;private set
    var listenedMs=0L;private set
    var reason="idle";private set
    private var wasListening=false
    private var started=0L;private var lastTick=0L;private var sequence=0
    private var warned=false;private var lastHelp=0L;private var helpGap=0L;private var recoveryStarted=0L
    val active get()=state==FallState.CONFIRMING || state==FallState.HELPING
    fun trigger(now: Long,source: String): FallEffect? {
        if(state!=FallState.IDLE) return null
        episode=maxOf(episode+1,now);started=now;lastTick=now;listenedMs=0;warned=false;sequence=0;wasListening=false
        state=FallState.CONFIRMING;reason=source
        return effect(FallEffectKind.ASK,"检测到疑似跌倒，你还好吗？请说我没事或需要帮助。")
    }
    fun tick(now: Long,listening: Boolean,sensorRecovered: Boolean): FallEffect? {
        val delta=(now-lastTick).coerceIn(0,500);lastTick=now
        // Do not charge the interval before the first recorder-ready callback.
        val inputDelta=if(listening && wasListening) delta else 0L
        wasListening=listening
        if(state==FallState.RECOVERING) {
            if(now-recoveryStarted>=config.recoveryMs && sensorRecovered) {state=FallState.IDLE;reason="rearmed"}
            return null
        }
        if(state==FallState.CONFIRMING) {
            if(listening) listenedMs+=inputDelta
            // Always announce the transition before help, even if audio/input fails.
            if(!warned && (listenedMs>=config.responseMs/2 || now-started>=config.wallMs-7_000)) {
                warned=true;return effect(FallEffectKind.WARN,"仍未收到回应。稍后开始本地求助。请说我没事或需要帮助。")
            }
            if(warned && (listenedMs>=config.responseMs || now-started>=config.wallMs)) return help(now,"no_response")
        }
        if(state==FallState.HELPING && listening) helpGap+=inputDelta
        if(state==FallState.HELPING && now-lastHelp>=config.repeatHelpMs && helpGap>=config.helpGapMs) {
            lastHelp=now;helpGap=0;return effect(FallEffectKind.HELP,"有人可能需要帮助。请查看。取消提醒请说我没事或取消求助。")
        }
        return null
    }
    fun reply(now: Long,reply: FallReply): FallEffect? {
        if(!active) return null
        return when(reply) {
            FallReply.HELP -> if(state==FallState.CONFIRMING) help(now,"user_help") else null
            FallReply.OK,FallReply.CANCEL -> {
                state=FallState.RECOVERING;recoveryStarted=now;reason=if(reply==FallReply.OK) "user_ok" else "user_cancel"
                effect(FallEffectKind.RELEASE,"已停止本次求助提醒。恢复可用监测。")
            }
            FallReply.UNKNOWN -> null
        }
    }
    fun cancel(now: Long) {if(active) {state=FallState.RECOVERING;recoveryStarted=now;reason="session_paused"}}
    fun reset() {state=FallState.IDLE;reason="idle";listenedMs=0;lastTick=0}
    private fun help(now: Long,why: String): FallEffect {
        state=FallState.HELPING;reason=why;lastHelp=now;helpGap=0
        return effect(FallEffectKind.HELP,"有人可能需要帮助。请查看。取消提醒请说我没事或取消求助。")
    }
    private fun effect(kind: FallEffectKind,text: String)=FallEffect(episode,++sequence,kind,text)
    companion object {
        fun parse(text: String)=when(CommandIntentParser.normalize(text)) {
            "我没事","没事","我没有事" -> FallReply.OK
            "需要帮助","我需要帮助","帮帮我" -> FallReply.HELP
            "取消求助","停止求助","取消帮助" -> FallReply.CANCEL
            else -> FallReply.UNKNOWN
        }
    }
}
