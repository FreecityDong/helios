package com.helios.app

/** Stateless questions; local control and confirmation grammars always take precedence. */
object OpenQuestionPolicy {
    const val WINDOW_MS = 12_000L
    const val FOLLOW_UP_MS = 8_000L
    fun clean(raw: String): String? {
        val text=raw.replace("[unk]", "").trim().replace(Regex("\\s+"), "")
        if(text.length !in 2..160 || text.any(Char::isISOControl)) return null
        if(text.trim('。','，','！','？') in setOf("好的","谢谢","嗯嗯","好吧","没事","不用了")) return null
        return text
    }
    fun resolve(raw: String, confirmingExit: Boolean = false, constrained: Boolean = false): VoiceIntent {
        val local=CommandIntentParser.parse(raw,confirmingExit)
        return if(local==VoiceIntent.UNKNOWN && !confirmingExit && !constrained && clean(raw)!=null) VoiceIntent.ASK_SCENE else local
    }
}
