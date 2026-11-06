package com.helios.app
import org.junit.Assert.*
import org.junit.Test

class UniversalWakeTest {
    @Test fun startupRunningAndPausedSessionsAcceptWakeDuringEverySpeechPriority() {
        for(session in listOf(SessionState.STARTING,SessionState.ACTIVE,SessionState.PAUSED)) {
            for(priority in SpeechPriority.entries) {
                val speech=SpeechRequest("id","any:$priority","message",priority,0,10_000)
                assertTrue(WakeListeningPolicy.allowed(false,true,priority==SpeechPriority.FALL,speech,session=session))
            }
        }
    }
    @Test fun finishedAndFailedSessionsDoNotReopenTheMicrophone() {
        for(session in listOf(SessionState.OFF,SessionState.STOPPING,SessionState.ERROR))
            assertFalse(WakeListeningPolicy.allowed(false,true,false,null,session=session))
    }
    @Test fun chineseCommandInputRetainsExclusiveMicrophoneOwnership() {
        for(priority in SpeechPriority.entries) assertFalse(WakeListeningPolicy.allowed(false,true,false,
            SpeechRequest("id","key","text",priority,0,10_000),commandListening=true))
    }
    @Test fun spokenWakeInstructionsCannotSpeakTheirOwnTriggerPhrase() {
        assertEquals("可以说英文唤醒词。",WakeListeningPolicy.spokenText("可以说Hi Helios。"))
        assertEquals("请说英文唤醒词，提示音后提问。",WakeListeningPolicy.spokenText("请说HI HELIOS，提示音后提问。"))
        assertEquals("已经找到杯子。",WakeListeningPolicy.spokenText("已经找到杯子。"))
    }
    @Test fun interruptedStartupContinuesOnceAfterTheUserCommandFinishes() {
        val continuation=StartupVoiceContinuation();var calibrations=0
        continuation.hold(7) {calibrations++}
        assertNull(continuation.take(7,false));assertEquals(0,calibrations)
        continuation.take(7,true)?.invoke();assertEquals(1,calibrations)
        assertNull(continuation.take(7,true))
    }
    @Test fun oldStartupCannotResumeAfterStoppingOrRestartingAssistance() {
        val continuation=StartupVoiceContinuation();var activations=0
        continuation.hold(7) {activations++}
        assertNull(continuation.take(8,false));assertNull(continuation.take(8,true));assertEquals(0,activations)
    }
    @Test fun wakeDropsQueuedSpeechAndLateTtsCompletionCannotRestartAnOldWindow() {
        val arbiter=SpeechArbiter()
        val active=SpeechRequest("risk","risk:1","risk",SpeechPriority.URGENT_SAFETY,0,10_000)
        val queued=SpeechRequest("confirm","find:confirm","confirm",SpeechPriority.ORDINARY,0,10_000)
        arbiter.submit(active,0);arbiter.submit(queued,1)
        arbiter.clear()
        assertNull(arbiter.complete(active.id,2));assertNull(arbiter.resume(2));assertTrue(arbiter.retainedIds().isEmpty())
    }
}
