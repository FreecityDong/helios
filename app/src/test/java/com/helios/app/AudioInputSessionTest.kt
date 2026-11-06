package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class AudioInputSessionTest {
    @Test fun repeatedStartKeepsTheExistingRecordingInsteadOfCreatingAnErrorLoop() {
        val session = AudioInputSession()
        val first = session.begin()!!
        repeat(20) { assertNull(session.begin()); assertTrue(session.current(first)) }
    }
    @Test fun anOldWakeResultCannotTriggerAfterCommandRecordingTakesOver() {
        val session = AudioInputSession(); val wake = session.begin()!!
        session.end(); val command = session.begin()!!
        assertFalse(session.current(wake)); assertTrue(session.current(command))
    }
    @Test fun aLateOldReadyOrErrorCallbackCannotAffectASecondCommandWindow() {
        val session = AudioInputSession(); val first = session.begin()!!
        session.end(); val second = session.begin()!!
        assertNotEquals(first, second); assertFalse(session.current(first)); assertTrue(session.current(second))
    }
    @Test fun everySpeechPriorityAllowsUserWakeOnceModelsAreReady() {
        fun speech(key: String, priority: SpeechPriority) = SpeechRequest(key, key, "text", priority, 0, 10_000)
        assertTrue(WakeListeningPolicy.allowed(false, true, false, speech("scene:1", SpeechPriority.ENVIRONMENT)))
        assertTrue(WakeListeningPolicy.allowed(false, true, false, speech("find:result:1", SpeechPriority.ORDINARY)))
        assertTrue(WakeListeningPolicy.allowed(false, true, false, speech("risk:nav:1", SpeechPriority.URGENT_SAFETY)))
        assertTrue(WakeListeningPolicy.allowed(false, true, true, speech("fall:1", SpeechPriority.FALL)))
        assertTrue(WakeListeningPolicy.allowed(false, true, false, speech("startup", SpeechPriority.ORDINARY)))
    }
    @Test fun finishingAnEnvironmentSentenceCanKeepWakeReadyForTheNextRound() {
        val session = AudioInputSession(); val token = session.begin()!!
        assertTrue(WakeListeningPolicy.allowed(false, true, false, SpeechRequest("s", "scene:1", "text", SpeechPriority.ENVIRONMENT, 0, 10_000)))
        assertTrue(WakeListeningPolicy.allowed(false, true, false, null))
        assertNull(session.begin()); assertTrue(session.current(token))
        session.end(); assertFalse(session.current(token)); assertNotNull(session.begin())
    }
    @Test fun releasedOrUnreadyServicesNeverStartWakeRecording() {
        assertFalse(WakeListeningPolicy.allowed(true, true, false, null))
        assertFalse(WakeListeningPolicy.allowed(false, false, false, null))
    }
    @Test fun aLateWakeResumeCannotStopAnActiveChineseCommandWindow() {
        assertFalse(WakeListeningPolicy.allowed(false, true, false, null, commandListening = true))
        assertTrue(WakeListeningPolicy.allowed(false, true, false, null, commandListening = false))
    }
    @Test fun userWakeCanInterruptTheBriefFindSuccessSentence() {
        val speech=SpeechRequest("done","find:success:1","已经找到手机。",SpeechPriority.FIND_SUCCESS,0,30_000)
        assertTrue(WakeListeningPolicy.allowed(false,true,false,speech))
        assertTrue(WakeListeningPolicy.allowed(false,true,false,null))
    }

}
