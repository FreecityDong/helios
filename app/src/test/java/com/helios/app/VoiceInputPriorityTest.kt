package com.helios.app
import org.junit.Assert.*
import org.junit.Test

class VoiceInputPriorityTest {
    private fun risk(priority:SpeechPriority=SpeechPriority.WARNING_SAFETY,now:Long=0)=
        SpeechRequest("warning","risk:nav:1:TURN_GUIDANCE:WARNING:1:h0","风险",priority,now,now+1_500)
    @Test fun warningsCannotTakeTheRecorderDuringWakeHandoffOrACommand() {
        for(priority in listOf(SpeechPriority.ORDINARY,SpeechPriority.SAFETY,SpeechPriority.WARNING_SAFETY)) {
            assertTrue(VoiceInputPriority.defer(risk(priority),true,false))
            assertTrue(VoiceInputPriority.defer(risk(priority),false,true))
            assertFalse(VoiceInputPriority.defer(risk(priority),false,false))
        }
    }
    @Test fun freshUrgentCoreTakesTheChannelDuringHandoffAndEveryInputWindow() {
        val urgent=risk(SpeechPriority.URGENT_SAFETY).copy(riskMode=RiskSpeechMode.ALERT)
        assertTrue(VoiceInputPriority.urgentTakesInput(urgent))
        for((handoff,listening) in listOf(true to false,false to true,true to true)) {
            assertFalse(VoiceInputPriority.defer(urgent,handoff,listening))
            val arbiter=SpeechArbiter()
            assertEquals(SpeechDecision.START,arbiter.submit(urgent,0,VoiceInputPriority.defer(urgent,handoff,listening)))
        }
    }
    @Test fun repeatedUrgentRiskAndDirectionDetailsStillWaitRatherThanInterruptRepeatedly() {
        for(mode in listOf(RiskSpeechMode.REPEAT,RiskSpeechMode.DIRECTION_UPDATE,RiskSpeechMode.CLEAR)) {
            val r=risk(SpeechPriority.URGENT_SAFETY).copy(riskMode=mode)
            assertFalse(VoiceInputPriority.urgentTakesInput(r))
            assertTrue(VoiceInputPriority.defer(r,true,true))
        }
        assertFalse(VoiceInputPriority.urgentTakesInput(risk(SpeechPriority.URGENT_SAFETY).copy(riskDetail=true)))
    }
    @Test fun endedInputCannotDeliverItsQueuedResultAfterUrgentTakeover() {
        val input=AudioInputSession();val token=input.begin()!!
        assertTrue(input.current(token))
        input.end()
        assertFalse(input.current(token))
        assertFalse(input.running)
    }
    @Test fun newerUrgentCoreCanDiscardAnOlderRememberedWakeBeforeReturningToWakeListening() {
        val pending=DeferredUrgentWake()
        val old=risk(SpeechPriority.URGENT_SAFETY)
        pending.hold(old,"hi helios")
        assertNotNull(pending.speechId)
        assertTrue(VoiceInputPriority.urgentTakesInput(old.copy(id="new",riskMode=RiskSpeechMode.ALERT)))
        pending.clear()
        assertNull(pending.take())
    }
    @Test fun fallAndExplicitUserResponsesKeepTheirOwnPriority() {
        assertFalse(VoiceInputPriority.defer(risk(SpeechPriority.FALL),true,true))
        assertFalse(VoiceInputPriority.defer(risk().copy(key="fall:1"),true,true))
        assertFalse(VoiceInputPriority.defer(risk().copy(key="reply:status"),true,true))
    }
    @Test fun queuedWarningDoesNotStartSpeechOrInterruptCommandInput() {
        val arbiter=SpeechArbiter();val warning=risk()
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(warning,0,
            VoiceInputPriority.defer(warning,false,true)))
        assertNull(arbiter.active)
        assertEquals(warning,arbiter.resume(500))
    }
    @Test fun staleWarningIsDiscardedAfterTheUserFinishesTalking() {
        val arbiter=SpeechArbiter();val warning=risk()
        arbiter.submit(warning,0,queueOnly=true)
        assertNull(arbiter.resume(3_000))
    }
    @Test fun aFallCanStillTakeTheChannelWhileBackgroundWarningsAreDeferred() {
        val arbiter=SpeechArbiter();arbiter.submit(risk(),0,queueOnly=true)
        val fall=SpeechRequest("fall","fall:1","跌倒询问",SpeechPriority.FALL,100,10_000)
        assertEquals(SpeechDecision.START,arbiter.submit(fall,100))
        assertEquals(fall,arbiter.active)
    }
    @Test fun consecutiveSpeechDoesNotResetAnExistingWakeRecorder() {
        val session=AudioInputSession();val token=session.begin()!!
        repeat(36) {
            assertTrue(VoiceInputPriority.reuseWake(session.running,true,false))
            assertTrue(session.current(token))
        }
        assertFalse(VoiceInputPriority.reuseWake(true,true,true))
        assertFalse(VoiceInputPriority.reuseWake(true,false,false))
        assertFalse(VoiceInputPriority.reuseWake(false,true,false))
    }
    @Test fun aQueuedWarningResumedAtTheSameTimeAsRecordingCanYieldAgain() {
        val arbiter=SpeechArbiter();val warning=risk()
        arbiter.submit(warning,0,queueOnly=true);assertEquals(warning,arbiter.resume(100))
        assertTrue(arbiter.deferActiveForVoice(warning.id,100))
        assertNull(arbiter.active)
        assertEquals(warning,arbiter.resume(500))
        arbiter.deferActiveForVoice(warning.id,600)
        assertNull(arbiter.resume(3_000))
    }
}
