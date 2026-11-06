package com.helios.app
import org.junit.Assert.*
import org.junit.Test

class SpeechInteractionRulesTest {
    private fun risk(id:String="r",priority:SpeechPriority=SpeechPriority.WARNING_SAFETY,episode:Int=1,
        band:ImageHeightBand=ImageHeightBand.FRONT,meters:Float=.8f,mode:RiskSpeechMode=RiskSpeechMode.ALERT)=
        SpeechRequest(id,"risk:nav:$episode:TURN_GUIDANCE:${if(priority==SpeechPriority.URGENT_SAFETY) "URGENT" else "WARNING"}:$id:h1",
            "警告，前方有障碍，请停下。保持原地向左缓慢转动。",priority,0,1_500,riskMeters=meters,
            riskDirection=AvoidanceDirection.LEFT,riskBand=band,riskMode=mode,riskNavigating=true)
    private fun scene()=SpeechRequest("scene","scene:1","环境描述",SpeechPriority.ENVIRONMENT,0,20_000)
    @Test fun lowerRisksCannotInterruptAnAnswerButFreshUrgentRiskCan() {
        val arbiter=SpeechArbiter();arbiter.submit(scene(),0)
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(risk(),100))
        assertEquals(SpeechDecision.START,arbiter.submit(risk("u",SpeechPriority.URGENT_SAFETY,meters=.4f),200))
    }
    @Test fun repeatedUrgentRiskAndDirectionSupplementNeverCutAnAnswer() {
        for(r in listOf(risk(priority=SpeechPriority.URGENT_SAFETY,mode=RiskSpeechMode.REPEAT),
            risk(priority=SpeechPriority.URGENT_SAFETY).copy(riskDetail=true,riskMode=RiskSpeechMode.DIRECTION_UPDATE))) {
            val arbiter=SpeechArbiter();arbiter.submit(scene(),0)
            assertEquals(SpeechDecision.QUEUED,arbiter.submit(r,100));assertEquals("scene",arbiter.active?.id)
        }
    }
    @Test fun sameLevelUpdatesQueueWhileEscalationStillInterruptsTheCore() {
        val arbiter=SpeechArbiter();arbiter.submit(risk("first"),0)
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(risk("closer",meters=.5f),100))
        assertEquals("first",arbiter.active?.id)
        assertEquals(SpeechDecision.START,arbiter.submit(risk("urgent",SpeechPriority.URGENT_SAFETY,meters=.4f),200))
    }
    @Test fun simultaneousHazardsCoalesceIndependentlyByHeightAndEpisode() {
        val arbiter=SpeechArbiter();arbiter.submit(scene(),0)
        arbiter.submit(risk("front"),100);arbiter.submit(risk("ground",band=ImageHeightBand.GROUND),200)
        arbiter.submit(risk("front-latest"),300)
        assertFalse("front" in arbiter.retainedIds());assertTrue("ground" in arbiter.retainedIds())
        assertTrue("front-latest" in arbiter.retainedIds())
    }
    @Test fun imageAnalysisIsProtectedBeforeItsAnswerExists() {
        assertTrue(SpeechInterruptionPolicy.deferToVisualTask(risk(),true))
        assertFalse(SpeechInterruptionPolicy.deferToVisualTask(risk(priority=SpeechPriority.URGENT_SAFETY),true))
        assertTrue(SpeechInterruptionPolicy.deferToVisualTask(risk(priority=SpeechPriority.URGENT_SAFETY,mode=RiskSpeechMode.REPEAT),true))
        assertFalse(SpeechInterruptionPolicy.deferToVisualTask(risk(),false))
    }
    @Test fun firstSentenceAndDirectionAreSeparate() {
        val parts=RiskDetailBuffer.parts(risk().text)
        assertEquals("警告，前方有障碍，请停下。",parts.first)
        assertEquals("保持原地向左缓慢转动。",parts.second)
    }
    @Test fun latestDirectionIsRevalidatedAtTheEndOfTheCore() {
        val buffer=RiskDetailBuffer();val original=risk();buffer.offer(original)
        val changed=risk("new").copy(riskDirection=AvoidanceDirection.RIGHT,text="前方有障碍。保持原地向右缓慢转动。",createdMs=500)
        buffer.offer(changed)
        val detail=buffer.take(original,3_000) {it.riskDirection==AvoidanceDirection.RIGHT}!!
        assertTrue(detail.riskDetail);assertEquals("保持原地向右缓慢转动。",detail.text)
        assertEquals(4_500L,detail.expiresMs);assertNull(buffer.take(original,3_001) {true})
    }
    @Test fun changedHeadingExpiredCandidateOrClearedHazardDropsTheDirection() {
        val buffer=RiskDetailBuffer();val r=risk();buffer.offer(r)
        assertNull(buffer.take(r,3_000) {false})
        buffer.offer(r);assertNull(buffer.take(r,9_000) {true})
        buffer.offer(r);buffer.offer(r.copy(riskMode=RiskSpeechMode.CLEAR))
        assertNull(buffer.take(r,500) {true})
    }
    @Test fun confirmationInputGetsTheChannelBeforeQueuedWarnings() {
        val arbiter=SpeechArbiter()
        val confirm=SpeechRequest("confirm","confirm:exit","请确认",SpeechPriority.ORDINARY,0,10_000,inputAfterSpeech=true)
        arbiter.submit(confirm,0);arbiter.submit(risk(),100)
        assertNull(arbiter.complete(confirm.id,200,advance=!confirm.inputAfterSpeech))
        assertNull(arbiter.active);assertEquals("r",arbiter.resume(500)?.id)
    }
    @Test fun queuedHeightWarningMustStillMatchTheCurrentLiveEvidence() {
        val r=risk()
        try {
            NavigationStateStore.update(NavigationValidity(1_000,true,1,RiskLevel.WARNING,
                reobserveDirection=AvoidanceDirection.LEFT,evidenceRevision=1,heightBand=ImageHeightBand.GROUND))
            assertFalse(NavigationStateStore.valid(r.key,1_000,AvoidanceDirection.LEFT,true,ImageHeightBand.FRONT))
            assertTrue(NavigationStateStore.valid(r.key,1_000,AvoidanceDirection.LEFT,true,ImageHeightBand.GROUND))
        } finally {NavigationStateStore.update(NavigationValidity())}
    }
}
