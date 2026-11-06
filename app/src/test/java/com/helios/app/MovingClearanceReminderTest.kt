package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class MovingClearanceReminderTest {
    private fun state(now:Long)=NavigationValidity(timeMs=now,available=true,forwardClear=true)
    @Test fun firstWalkingFeedbackNeedsMeasuredClearanceButNoPastObstacle() {
        val reminder=MovingClearanceReminder()
        assertFalse(reminder.take(1000,state(1000).copy(forwardClear=false),true,true,6000))
        assertTrue(reminder.take(2000,state(2000),true,true,6000))
    }
    @Test fun repeatIntervalCountsFromAudioStartRatherThanOnlyASubmission() {
        val reminder=MovingClearanceReminder()
        assertTrue(reminder.take(0,state(0),true,true,6000))
        assertFalse(reminder.take(500,state(500),true,true,6000))
        reminder.spoken(1000)
        assertFalse(reminder.take(6999,state(6999),true,true,6000))
        assertTrue(reminder.take(7000,state(7000),true,true,6000))
    }
    @Test fun userConversationDoesNotConsumeAFeedbackOpportunity() {
        val reminder=MovingClearanceReminder()
        assertFalse(reminder.take(1000,state(1000),true,false,6000))
        assertTrue(reminder.take(1001,state(1001),true,true,6000))
    }
    @Test fun stationaryWearerDoesNotReceivePeriodicPermission() {
        val reminder=MovingClearanceReminder()
        for(now in 1000L..60000L step 1000) assertFalse(reminder.take(now,state(now),false,true,6000))
    }
    @Test fun aRecentTransitionConfirmationPreventsAnImmediateDuplicateWalkingMessage() {
        val reminder=MovingClearanceReminder();reminder.spoken(1000)
        assertFalse(reminder.take(1500,state(1500),true,true,6000))
        assertTrue(reminder.take(7000,state(7000),true,true,6000))
    }
    @Test fun staleUnknownAndNewlyBlockedFramesNeverPermitPositiveSpeech() {
        for(invalid in listOf(state(1000).copy(available=false),state(1000).copy(forwardClear=false),
            state(1000).copy(episodeId=8),state(1000).copy(timeMs=1))) {
            assertFalse(MovingClearanceReminder().take(1000,invalid,true,true,6000))
        }
    }
    @Test fun explicitResetAllowsANewSessionWhileOrdinaryStopsKeepTheTimer() {
        val reminder=MovingClearanceReminder();reminder.spoken(1000)
        assertFalse(reminder.take(2000,state(2000),false,true,6000))
        assertFalse(reminder.take(2100,state(2100),true,true,6000))
        reminder.reset()
        assertTrue(reminder.take(2200,state(2200),true,true,6000))
    }
    @Test fun threeSecondTrialUsesActualAudioStartAndDoesNotLoseItsTurnWhileBusy() {
        val reminder=MovingClearanceReminder();reminder.spoken(1000)
        assertFalse(reminder.take(3999,state(3999),true,true,3000))
        assertFalse(reminder.take(4000,state(4000),true,false,3000))
        assertTrue(reminder.take(4100,state(4100),true,true,3000))
    }
    @Test fun threeSecondMessagesAreNotBlockedByTheArbitersFourSecondSameKeyDeduplication() {
        val arbiter=SpeechArbiter()
        fun request(id:String,now:Long)=SpeechRequest(id,"risk:current-clear:$id","当前前方未见障碍。",
            SpeechPriority.ORDINARY,now,now+1500,riskMode=RiskSpeechMode.CLEAR,clearanceFeedback=true,clearanceMovingOnly=true)
        assertEquals(SpeechDecision.START,arbiter.submit(request("first",0),0))
        arbiter.complete("first",1000)
        assertEquals(SpeechDecision.START,arbiter.submit(request("second",3000),3000))
    }
    @Test fun trialIntervalIsPersistentParameterDataWithDefaultRestoreAndValidation() {
        val defaults=DebugParameters.defaults()
        assertEquals(3.0,defaults["clearRepeat"],0.0)
        assertEquals(3000L,defaults.navigation.clearRepeatMs)
        val saved=defaults.patched(mapOf("clearRepeat" to 8.0,"forwardWidth" to 90.0))
        val loaded=DebugParameters.fromSaved(saved.asMap())
        assertEquals(8000L,loaded.navigation.clearRepeatMs)
        assertEquals(90.0,loaded["forwardWidth"],0.0)
        assertEquals(3000L,loaded.patched(mapOf("clearRepeat" to defaults["clearRepeat"])).navigation.clearRepeatMs)
        try {defaults.patched(mapOf("clearRepeat" to 2.0));fail("interval below setting range accepted")}
        catch(_:IllegalArgumentException) {}
    }
}
