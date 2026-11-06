package com.helios.app

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AssistanceCaptionTest {
    @After fun reset() { AssistanceStateStore.update(voice=VoiceState.UNAVAILABLE) }
    @Test fun cancellationSpeechAndWakePreparationDoNotReuseThePreviousWakeAcknowledgement() {
        AssistanceStateStore.update(voice=VoiceState.COMMAND_LISTENING,wakeAcknowledged=true,liveTranscript="取消")
        AssistanceStateStore.update(voice=VoiceState.PROCESSING,message="找物已取消。")
        AssistanceStateStore.update(voice=VoiceState.PROMPTING,message="正在准备本地唤醒监听")
        assertFalse(AssistanceStateStore.status.value.wakeAcknowledged)
        assertEquals("",AssistanceStateStore.status.value.liveTranscript)
    }
    @Test fun genuineWakeAcknowledgementRemainsUntilTheCommandWindowFinishes() {
        AssistanceStateStore.update(voice=VoiceState.PROMPTING,wakeAcknowledged=true,liveTranscript="")
        AssistanceStateStore.update(voice=VoiceState.COMMAND_LISTENING)
        AssistanceStateStore.update(liveTranscript="找杯子")
        assertTrue(AssistanceStateStore.status.value.wakeAcknowledged)
        assertEquals("找杯子",AssistanceStateStore.status.value.liveTranscript)
        AssistanceStateStore.update(voice=VoiceState.PROCESSING)
        assertFalse(AssistanceStateStore.status.value.wakeAcknowledged)
    }
    @Test fun restoringWakeListeningClearsTheOldCommand() {
        AssistanceStateStore.update(voice=VoiceState.COMMAND_LISTENING,wakeAcknowledged=true,liveTranscript="取消")
        AssistanceStateStore.update(voice=VoiceState.WAKE_LISTENING)
        AssistanceStateStore.update(voice=VoiceState.PROMPTING)
        assertFalse(AssistanceStateStore.status.value.wakeAcknowledged)
        assertEquals("",AssistanceStateStore.status.value.liveTranscript)
    }
}
