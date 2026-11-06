package com.helios.app

/** Risk audio can delay guidance, but cannot prevent a stationary observation. */
internal object FindCapturePolicy {
    fun audioAllowsCapture(speech:SpeechRequest?,voice:VoiceState,commandListening:Boolean,handoffPending:Boolean):Boolean {
        if(commandListening || handoffPending) return false
        if(speech==null) return voice==VoiceState.WAKE_LISTENING
        return speech.key.startsWith("risk:") && speech.priority!=SpeechPriority.FALL
    }
}
