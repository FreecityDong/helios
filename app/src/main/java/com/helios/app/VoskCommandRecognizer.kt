package com.helios.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class VoskCommandRecognizer(private val context: Context) : CommandRecognizer {
    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private val modelGeneration=java.util.concurrent.atomic.AtomicLong()
    private var speechService: SpeechService? = null
    private var listener: CommandRecognizer.Listener? = null
    private var finished = true
    private val session = AudioInputSession()
    private var recognizer: Recognizer? = null
    private var lastPartial = ""
    private val timeout = Runnable { finish { it.onTimeout() } }

    override val isAvailable: Boolean get() = model != null
    val isListening: Boolean get() = session.running && speechService != null && !finished

    fun load(onReady: () -> Unit, onError: (Throwable) -> Unit) {
        if (model != null) {
            onReady()
            return
        }
        val generation=modelGeneration.incrementAndGet()
        StorageService.unpack(
            context,
            MODEL_ASSET,
            MODEL_DIRECTORY,
            { loaded -> if(generation!=modelGeneration.get()) loaded.close() else {model=loaded;onReady()} },
            { error -> if(generation==modelGeneration.get()) onError(error) },
        )
    }

    override fun start(windowMs: Long, listener: CommandRecognizer.Listener)=startWithGrammar(windowMs,listener,COMMAND_GRAMMAR)

    fun startOpen(windowMs: Long,listener: CommandRecognizer.Listener)=startWithGrammar(windowMs,listener,null)

    fun startSafety(windowMs: Long,listener: CommandRecognizer.Listener)=startWithGrammar(windowMs,listener,
        "[\"我 没事\",\"需要 帮助\",\"取消 求助\",\"[unk]\"]")

    private fun startWithGrammar(windowMs: Long,listener: CommandRecognizer.Listener,grammar: String?) {
        stop()
        val activeModel = model
        if (activeModel == null) {
            listener.onError(-1, "本地中文模型尚未就绪")
            return
        }
        val token = session.begin()!!
        this.listener = listener
        finished = false; lastPartial = ""
        runCatching {
            val decoder = if(grammar==null) Recognizer(activeModel,SAMPLE_RATE) else Recognizer(activeModel, SAMPLE_RATE, grammar)
            recognizer = decoder
            val service = SpeechService(decoder, SAMPLE_RATE)
            speechService = service
            check(service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = runCatching { JSONObject(hypothesis.orEmpty()).optString("partial").trim() }.getOrDefault("")
                    handler.post {
                        if (session.current(token) && text.isNotEmpty() && text != lastPartial) {
                            lastPartial = text; this@VoskCommandRecognizer.listener?.onPartial(text.take(80))
                        }
                    }
                }
                override fun onResult(hypothesis: String?) = accept(hypothesis, token, grammar == null || grammar == COMMAND_GRAMMAR)
                override fun onFinalResult(hypothesis: String?) = accept(hypothesis, token, grammar == null || grammar == COMMAND_GRAMMAR)
                override fun onError(exception: Exception?) {
                    handler.post { if (session.current(token)) finish { it.onError(-1, exception?.message ?: "本地中文识别失败") } }
                }
                override fun onTimeout() { handler.post { if (session.current(token)) finish { it.onTimeout() } } }
            })) { "本地识别未成功开启录音" }
            handler.post { if (session.current(token) && !finished) listener.onReady() }
            handler.postDelayed(timeout, windowMs)
        }.onFailure { error ->
            finish { it.onError(-1, error.message ?: "本地中文识别启动失败") }
        }
    }

    override fun stop() {
        handler.removeCallbacks(timeout)
        session.end()
        finished = true
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        recognizer?.close(); recognizer = null
        listener = null
    }

    fun release() {
        modelGeneration.incrementAndGet()
        stop()
        model?.close()
        model = null
    }

    private fun accept(payload: String?, token: Long, ordinary: Boolean) {
        val raw = runCatching { JSONObject(payload.orEmpty()).optString("text").trim() }.getOrDefault("")
        val text = if (ordinary) raw.replace("[unk]", "").trim() else raw
        if (text.isNotEmpty()) handler.post {
            if (session.current(token)) finish { it.onResult(listOf(text)) }
        }
    }

    private fun finish(callback: (CommandRecognizer.Listener) -> Unit) {
        if (finished) return
        finished = true
        session.end()
        handler.removeCallbacks(timeout)
        val current = listener
        listener = null
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        recognizer?.close(); recognizer = null
        if (current != null) callback(current)
    }

    companion object {
        const val MODEL_VERSION = "vosk-model-small-cn-0.22"
        private const val MODEL_ASSET = "model-cn"
        private const val MODEL_DIRECTORY = "command-model-cn"
        private const val SAMPLE_RATE = 16_000.0f
        private val COMMAND_GRAMMAR = org.json.JSONArray(listOf("找 到 了", "我 找 到 了", "结束 找物", "结束 查找", "不用 找 了", "确认", "确认 找", "是 的", "对", "开始 找", "不 对", "不 是", "不用 了", "当前 状态", "暂停 辅助", "恢复 辅助", "退出 辅助", "确认 退出", "继续 辅助", "取消", "不找 了", "取消 找物", "继续 找", "继续 找物", "看看 周围", "环境 描述", "再 详细 一点", "分析 环境", "找 东西", "我 没事", "需要 帮助", "取消 求助", "[unk]") +
            FindTarget.supported.flatMap { it.aliases }.flatMap { listOf(it, "找 $it", "帮 我 找 $it", "找 一下 $it", "帮 我 找 一下 $it") }).toString()

    }
}
