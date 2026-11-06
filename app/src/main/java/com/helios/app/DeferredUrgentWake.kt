package com.helios.app

/** One explicit wake waits for the current urgent core, never for its direction supplement. */
class DeferredUrgentWake {
    var speechId: String? = null
        private set
    private var hypothesis: String? = null

    fun hold(speech: SpeechRequest?, phrase: String): Boolean {
        if (speech?.priority != SpeechPriority.URGENT_SAFETY || speech.riskDetail ||
            !speech.key.startsWith("risk:")) return false
        if (hypothesis == null) { speechId = speech.id; hypothesis = phrase }
        return true
    }

    fun take(): String? = hypothesis.also { clear() }
    fun clear() { speechId = null; hypothesis = null }
}
