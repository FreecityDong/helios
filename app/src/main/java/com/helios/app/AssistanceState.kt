package com.helios.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionState { OFF, STARTING, ACTIVE, PAUSED, STOPPING, ERROR }

enum class TtsState { IDLE, INITIALIZING, READY, SPEAKING, COMPLETED, UNAVAILABLE, ERROR }

enum class VoiceState {
    UNAVAILABLE,
    MODEL_LOADING,
    WAKE_LISTENING,
    PROMPTING,
    COMMAND_LISTENING,
    PROCESSING,
    CONFIRMING_EXIT,
    PAUSED,
    ERROR,
}

data class AssistanceStatus(
    val session: SessionState = SessionState.OFF,
    val tts: TtsState = TtsState.IDLE,
    val voice: VoiceState = VoiceState.UNAVAILABLE,
    val message: String = "辅助模式未开启",
    val lastCommand: String? = null,
    val liveTranscript: String = "",
    val wakeAcknowledged: Boolean = false,
)

object AssistanceStateStore {
    private val mutableStatus = MutableStateFlow(AssistanceStatus())
    val status = mutableStatus.asStateFlow()

    fun update(
        session: SessionState = mutableStatus.value.session,
        tts: TtsState = mutableStatus.value.tts,
        voice: VoiceState = mutableStatus.value.voice,
        message: String = mutableStatus.value.message,
        lastCommand: String? = mutableStatus.value.lastCommand,
        liveTranscript: String = mutableStatus.value.liveTranscript,
        wakeAcknowledged: Boolean = mutableStatus.value.wakeAcknowledged,
    ) {
        val keepCaption=voice in setOf(VoiceState.PROMPTING,VoiceState.COMMAND_LISTENING,VoiceState.CONFIRMING_EXIT)
        mutableStatus.value = AssistanceStatus(session, tts, voice, message, lastCommand,
            if(keepCaption) liveTranscript else "",if(keepCaption) wakeAcknowledged else false)
    }
}
