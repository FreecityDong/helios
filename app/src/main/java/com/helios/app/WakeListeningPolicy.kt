package com.helios.app

object WakeListeningPolicy {
    // Speech priority and physical motion never disable a user wake request.
    @Suppress("UNUSED_PARAMETER")
    fun allowed(released:Boolean,ready:Boolean,fallActive:Boolean,speech:SpeechRequest?,commandListening:Boolean=false,
        session:SessionState=SessionState.ACTIVE):Boolean = !released && ready && !commandListening &&
        session in setOf(SessionState.STARTING,SessionState.ACTIVE,SessionState.PAUSED)

    fun spokenText(text:String):String = text.replace(Regex("hi\\s+helios",RegexOption.IGNORE_CASE),"英文唤醒词")
}
