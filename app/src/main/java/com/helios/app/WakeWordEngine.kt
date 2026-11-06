package com.helios.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.concurrent.atomic.AtomicBoolean

interface WakeWordEngine {
    val isReady: Boolean
    val isListening: Boolean
    fun load(onReady: () -> Unit, onError: (Throwable) -> Unit)
    fun start(listener: Listener)
    fun stop()
    fun release()
    interface Listener {
        fun onWakeWord(hypothesis: String)
        fun onError(error: Throwable)
        fun onHypothesis(text: String, confidence: Double, accepted: Boolean, reason: String) {}
    }
}

class VoskWakeWordEngine(private val context: Context) : WakeWordEngine {
    private var model: Model? = null
    private val modelGeneration=java.util.concurrent.atomic.AtomicLong()
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var listener: WakeWordEngine.Listener? = null
    private val session = AudioInputSession()
    private val triggered = AtomicBoolean(false)
    private var phrasePolicy = WakePhrasePolicy()
    private var lastDiagnostic = ""
    private var lastDiagnosticMs = 0L
    override val isReady get() = model != null
    override val isListening get() = session.running && speechService != null && !triggered.get()

    override fun load(onReady: () -> Unit, onError: (Throwable) -> Unit) {
        if (model != null) { onReady(); return }
        val generation=modelGeneration.incrementAndGet()
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        StorageService.unpack(context, MODEL_ASSET, MODEL_DIRECTORY,
            { loaded -> if(generation!=modelGeneration.get()) loaded.close() else {model=loaded;onReady()} }, { error -> if(generation==modelGeneration.get()) onError(error) })
    }
    override fun start(listener: WakeWordEngine.Listener) {
        if (isListening) { this.listener = listener; return }
        stop()
        val activeModel = checkNotNull(model) { "Wake-word model is not loaded" }
        val token = session.begin()!!
        this.listener = listener; triggered.set(false)
        phrasePolicy = WakePhrasePolicy(); lastDiagnostic = ""; lastDiagnosticMs = 0L
        try {
            val decoder = Recognizer(activeModel, SAMPLE_RATE, WAKE_GRAMMAR).apply { setWords(true); setPartialWords(true) }
            recognizer = decoder
            val service = SpeechService(decoder, SAMPLE_RATE)
            speechService = service
            check(service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) { if (session.current(token)) inspect(hypothesis, partial = true) }
                override fun onResult(hypothesis: String?) { if (session.current(token)) inspect(hypothesis) }
                override fun onFinalResult(hypothesis: String?) { if (session.current(token)) inspect(hypothesis) }
                override fun onError(exception: Exception?) {
                    if (session.current(token)) this@VoskWakeWordEngine.listener?.onError(exception ?: IllegalStateException("Unknown Vosk error"))
                }
                override fun onTimeout() {
                    if (session.current(token)) this@VoskWakeWordEngine.listener?.onError(IllegalStateException("Wake-word listener timed out"))
                }
            })) { "Wake-word recorder did not start" }
        } catch (error: Throwable) { stop(); listener.onError(error) }
    }
    override fun stop() {
        session.end()
        speechService?.stop(); speechService?.shutdown(); speechService = null
        recognizer?.close(); recognizer = null; listener = null
    }
    override fun release() { modelGeneration.incrementAndGet();stop(); model?.close(); model = null }

    private fun inspect(payload: String?, partial: Boolean = false) {
        if (payload.isNullOrBlank() || triggered.get()) return
        val parsed = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val text = parsed.optString(if (partial) "partial" else "text").lowercase().trim().replace(Regex("\\s+"), " ")
        val metadata = parsed.optJSONArray(if (partial) "partial_result" else "result")
        val words = (0 until (metadata?.length() ?: 0)).map { index ->
            val word = metadata?.optJSONObject(index)
            WakePhrasePolicy.Word(word?.optString("word") ?: "", word?.optDouble("conf", 0.0) ?: 0.0)
        }
        val now = SystemClock.elapsedRealtime()
        val decision = phrasePolicy.inspect(text, words, partial, now)
        if (!text.split(" ").any { it == "hi" || it == "helios" }) return
        val signature = "$text:${decision.reason}"
        if (decision.accepted || signature != lastDiagnostic || now - lastDiagnosticMs >= 1_000L) {
            lastDiagnostic = signature; lastDiagnosticMs = now
            listener?.onHypothesis(text.take(48), decision.confidence, decision.accepted, decision.reason)
        }
        if (decision.accepted && triggered.compareAndSet(false, true)) {
            listener?.onWakeWord("$WAKE_PHRASE (confidence=${decision.confidence}, source=${if (partial) "partial" else "final"})")
        }
    }
    companion object {
        const val ENGINE_VERSION = "0.3.75"
        const val MODEL_VERSION = "vosk-model-small-en-us-0.15"
        const val WAKE_PHRASE = "hi helios"
        private const val MODEL_ASSET = "model-en-us"
        private const val MODEL_DIRECTORY = "wake-model-en-us"
        private const val SAMPLE_RATE = 16_000.0f
        private const val WAKE_GRAMMAR = "[\"hi helios\", \"[unk]\"]"
    }
}
