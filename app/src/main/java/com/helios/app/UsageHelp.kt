package com.helios.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

object UsageHelp {
    const val TEXT="佩戴好相机后，长按中央启动辅助按钮半秒。按提示将相机固定在自己胸前，镜头朝前，保持静止完成校准。语音准备好后，静止、运动或晃动时都可以说Hi Helios，提示音后说指令。播报时也可以唤醒打断。运动中也可先提问，听到请先停稳后，停稳会自动上传识别。再次运动会打断场景播报。主动提问回答后的提示音响起，八秒内可直接追问。找物先确认目标，移动后停稳再识别，可以说取消。需要结束时，长按中央结束辅助按钮半秒。疑似跌倒询问时，直接说我没事或需要帮助；求助只在本机发声。请留意实际周围情况，设备异常时，在更多中查看调试信息。"
}
data class UsageHelpStatus(val playing:Boolean=false,val message:String="")
object UsageHelpStatusStore {
    private val mutable=MutableStateFlow(UsageHelpStatus())
    val status=mutable.asStateFlow()
    fun update(value:UsageHelpStatus) {mutable.value=value}
}

/** OFF-state help uses only TTS; it never starts a microphone or an assistance session. */
class LocalUsageHelp(private val context:Context) {
    private val handler=Handler(Looper.getMainLooper())
    private var engine:TextToSpeech?=null
    private var generation=0L
    private var ownsPlayback=false
    fun play() = playText(UsageHelp.TEXT,true)
    fun playGreeting() = playText("Helios为您服务。",false)
    private fun playText(text:String,help:Boolean) {
        stop()
        val token=++generation
        ownsPlayback=help
        if(help) UsageHelpStatusStore.update(UsageHelpStatus(true,"正在语音介绍，再点帮助可停止"))
        EventLog(context).append(if(help) "usage_help_requested" else "app_greeting_requested",mapOf("source" to "local"))
        engine=TextToSpeech(context.applicationContext) {status -> handler.post {
            if(token!=generation) return@post
            val tts=engine ?: return@post
            if(status!=TextToSpeech.SUCCESS || tts.setLanguage(Locale.SIMPLIFIED_CHINESE)<TextToSpeech.LANG_AVAILABLE) {
                stop();UsageHelpStatusStore.update(UsageHelpStatus(message="中文语音不可用，请检查系统语音资源"));return@post
            }
            tts.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
                override fun onStart(id:String?) {EventLog(context).append(if(help) "usage_help_started" else "app_greeting_started",mapOf("source" to "local"))}
                override fun onDone(id:String?) {handler.post {if(token==generation) stop()}}
                @Deprecated("Deprecated in Java") override fun onError(id:String?) {handler.post {if(token==generation) stop()}}
            })
            if(tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"usage-help-$token")==TextToSpeech.ERROR) stop()
        }}
        handler.postDelayed({if(token==generation) stop()},90_000)
    }
    fun stop() {
        generation++;handler.removeCallbacksAndMessages(null)
        engine?.stop();engine?.shutdown();engine=null
        if(ownsPlayback) {
            EventLog(context).append("usage_help_stopped",mapOf("source" to "local"))
            UsageHelpStatusStore.update(UsageHelpStatus())
        }
        ownsPlayback=false
    }
}

/** One welcome for this application process; Activity recreation does not replay it. */
object AppOpeningGreeting {
    private val claimed=java.util.concurrent.atomic.AtomicBoolean(false)
    fun take():Boolean=claimed.compareAndSet(false,true)
}
