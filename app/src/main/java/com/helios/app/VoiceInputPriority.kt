package com.helios.app

/** Ordinary risks wait for input; a fresh urgent core takes the voice channel. */
internal object VoiceInputPriority {
    fun urgentTakesInput(request:SpeechRequest):Boolean = request.key.startsWith("risk:") &&
        SpeechInterruptionPolicy.freshUrgent(request)

    fun defer(request:SpeechRequest,handoffPending:Boolean,commandListening:Boolean):Boolean =
        (handoffPending || commandListening) && request.key.startsWith("risk:") &&
            request.priority!=SpeechPriority.FALL && !urgentTakesInput(request)

    fun reuseWake(listening:Boolean,allowed:Boolean,commandListening:Boolean)=listening && allowed && !commandListening
}
