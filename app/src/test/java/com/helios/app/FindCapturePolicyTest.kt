package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class FindCapturePolicyTest {
    private fun risk(priority:SpeechPriority)=SpeechRequest("r","risk:nav:1:ALERT","风险",priority,0,10_000)
    @Test fun everyObstacleLevelAllowsAStationaryObservationWhileItSpeaks() {
        for(priority in listOf(SpeechPriority.SAFETY,SpeechPriority.WARNING_SAFETY,SpeechPriority.URGENT_SAFETY))
            assertTrue(FindCapturePolicy.audioAllowsCapture(risk(priority),VoiceState.PROCESSING,false,false))
    }
    @Test fun userInputStartupAndFallStillBlockCapture() {
        val urgent=risk(SpeechPriority.URGENT_SAFETY)
        assertFalse(FindCapturePolicy.audioAllowsCapture(urgent,VoiceState.PROCESSING,true,false))
        assertFalse(FindCapturePolicy.audioAllowsCapture(urgent,VoiceState.PROCESSING,false,true))
        assertFalse(FindCapturePolicy.audioAllowsCapture(risk(SpeechPriority.FALL),VoiceState.PROCESSING,false,false))
        assertFalse(FindCapturePolicy.audioAllowsCapture(urgent.copy(key="startup:1"),VoiceState.PROCESSING,false,false))
        assertFalse(FindCapturePolicy.audioAllowsCapture(null,VoiceState.COMMAND_LISTENING,false,false))
    }
    @Test fun urgentAudioCannotTurnASingleStillEpisodeIntoRepeatedUploads() {
        val flow=FindFlow();flow.start(FindTarget.supported.first(),0)
        assertNull(flow.sample(0,true))
        val sample=flow.sample(1_500,true)!!
        repeat(10) {
            assertTrue(FindCapturePolicy.audioAllowsCapture(risk(SpeechPriority.URGENT_SAFETY),VoiceState.PROCESSING,false,false))
            assertNull(flow.sample(2_000L+it*100,true))
            assertEquals(sample,flow.pending)
        }
        val observation=FindObservation(true,.95,FindRegion(.4,.4,.6,.6),.4f,true)
        assertEquals("reached",flow.accept(sample,observation,1_500,3_000))
        flow.invalidate("new urgent audio")
        assertNotNull(flow.completion);assertNull(flow.sample(30_000,true))
    }
    @Test fun nearTargetAtTheEdgeStillNeedsAlignmentBeforeCompleting() {
        for(region in listOf(FindRegion(.1,.4,.3,.6),FindRegion(.7,.4,.9,.6),
            FindRegion(.4,.05,.6,.25),FindRegion(.4,.75,.6,.95))) {
            val flow=FindFlow();flow.start(FindTarget.supported.first(),0);flow.sample(0,true)
            val sample=flow.sample(1_500,true)!!
            assertEquals("found",flow.accept(sample,FindObservation(true,.95,region,.4f,true),1_500,2_000))
            assertNull(flow.completion)
        }
    }
    @Test fun oneCenteredVerifiedObservationWithinFiftyCentimetersCompletes() {
        for(meters in listOf(.2f,.4f,.5f)) {
            val flow=FindFlow();flow.start(FindTarget.supported.first(),0);flow.sample(0,true)
            val sample=flow.sample(1_500,true)!!
            assertEquals("reached",flow.accept(sample,FindObservation(true,.95,FindRegion(.4,.4,.6,.6),meters,true),1_500,2_000))
            assertNotNull(flow.completion)
        }
    }
}
