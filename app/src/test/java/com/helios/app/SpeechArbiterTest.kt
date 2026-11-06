package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class SpeechArbiterTest {
    private fun nav(id: String,level: RiskLevel,now: Long,episode: Long=1,
        direction: AvoidanceDirection?=null,clear: Boolean=false) = SpeechRequest(id,
        "risk:nav:$episode:${if (clear) "AREA_CLEARED" else "OBSTACLE"}:${level.name}:$id:live",
        id,if (clear) SpeechPriority.ORDINARY else when(level) {
            RiskLevel.CAUTION -> SpeechPriority.SAFETY
            RiskLevel.WARNING -> SpeechPriority.WARNING_SAFETY
            RiskLevel.URGENT -> SpeechPriority.URGENT_SAFETY
        },now,now+1500,riskDirection=direction)

    @Test fun navigationEscalationInterruptsBothLowerLevelsImmediately() {
        val rule=SpeechArbiter()
        assertEquals(SpeechDecision.START,rule.submit(nav("c",RiskLevel.CAUTION,100),100))
        assertEquals(SpeechDecision.START,rule.submit(nav("w",RiskLevel.WARNING,200),200))
        assertEquals(SpeechDecision.START,rule.submit(nav("u",RiskLevel.URGENT,300),300))
        assertEquals(SpeechDecision.START,rule.submit(request("fall",SpeechPriority.FALL,400),400))
        assertEquals(SpeechDecision.QUEUED,rule.submit(nav("u2",RiskLevel.URGENT,500),500))
    }
    @Test fun repeatDoesNotRestartAnIdenticalSentenceAndDirectionWaitsForItsEnd() {
        val rule=SpeechArbiter()
        assertEquals(SpeechDecision.START,rule.submit(nav("w",RiskLevel.WARNING,100),100))
        assertEquals(SpeechDecision.DUPLICATE,rule.submit(nav("repeat",RiskLevel.WARNING,500),500))
        assertEquals(SpeechDecision.QUEUED,rule.submit(nav("dir",RiskLevel.WARNING,600,
            direction=AvoidanceDirection.LEFT),600))
        assertEquals("w", rule.active?.id)
        assertEquals("dir", rule.complete("w", 700)?.id)
    }
    @Test fun confirmedDepartureQueuesAfterCoreAndDropsPendingRisk() {
        val rule=SpeechArbiter()
        rule.submit(nav("u",RiskLevel.URGENT,100),100)
        rule.submit(nav("c",RiskLevel.CAUTION,200),200)
        assertEquals(SpeechDecision.QUEUED,rule.submit(nav("clear",RiskLevel.CAUTION,300,clear=true),300))
        assertEquals("clear",rule.complete("u",400)?.id)
        assertNull(rule.complete("clear",500))
    }
    private fun request(id: String, priority: SpeechPriority, now: Long, expires: Long = now + 5_000) =
        SpeechRequest(id, id, id, priority, now, expires)

    @Test fun highPriorityInterruptsAndEqualWaits() {
        val arbiter = SpeechArbiter()
        assertEquals(SpeechDecision.START, arbiter.submit(request("ordinary", SpeechPriority.ORDINARY, 100), 100))
        assertEquals(SpeechDecision.START, arbiter.submit(request("safety", SpeechPriority.SAFETY, 200), 200))
        assertEquals("safety", arbiter.active?.id)
        assertEquals(SpeechDecision.QUEUED, arbiter.submit(request("secondSafety", SpeechPriority.SAFETY, 300), 300))
        assertEquals("secondSafety", arbiter.complete("safety", 400)?.id)
        assertNull(arbiter.complete("ordinary", 400))
    }

    @Test fun expiredAndDuplicateEventsDoNotSpeak() {
        val arbiter = SpeechArbiter()
        assertEquals(SpeechDecision.EXPIRED, arbiter.submit(request("old", SpeechPriority.SAFETY, 100, 200), 200))
        val first = request("first", SpeechPriority.ORDINARY, 200)
        assertEquals(SpeechDecision.START, arbiter.submit(first, 200))
        assertEquals(SpeechDecision.DUPLICATE, arbiter.submit(first.copy(id = "another"), 300))
        assertEquals(SpeechDecision.QUEUED, arbiter.submit(request("queued", SpeechPriority.ORDINARY, 300, 350), 300))
        assertNull(arbiter.complete("first", 400))
    }

    @Test fun backlogIsBounded() {
        val arbiter = SpeechArbiter()
        arbiter.submit(request("active", SpeechPriority.SAFETY, 100), 100)
        for (i in 1..8) assertEquals(SpeechDecision.QUEUED,
            arbiter.submit(request("queued$i", SpeechPriority.ORDINARY, 100L + i), 100L + i))
        assertEquals(SpeechDecision.OVERFLOW,
            arbiter.submit(request("extra", SpeechPriority.ORDINARY, 200), 200))
    }

    @Test fun responseWindowHoldsQueuedSpeech() {
        val arbiter = SpeechArbiter()
        arbiter.submit(request("response", SpeechPriority.SAFETY, 100), 100)
        arbiter.submit(request("ordinary", SpeechPriority.ORDINARY, 200), 200)
        assertNull(arbiter.complete("response", 300, advance = false))
        assertNull(arbiter.active)
        assertEquals("ordinary", arbiter.resume(500)?.id)
    }

    @Test fun urgentRiskInterruptsCautionButFallRemainsHigher() {
        val arbiter = SpeechArbiter()
        assertEquals(SpeechDecision.START, arbiter.submit(request("caution",SpeechPriority.SAFETY,100),100))
        assertEquals(SpeechDecision.START, arbiter.submit(request("urgent",SpeechPriority.URGENT_SAFETY,200),200))
        assertEquals("urgent",arbiter.active?.id)
        assertEquals(SpeechDecision.START, arbiter.submit(request("fall",SpeechPriority.FALL,300),300))
        assertEquals(SpeechDecision.QUEUED, arbiter.submit(request("urgent2",SpeechPriority.URGENT_SAFETY,400),400))
    }

    @Test fun materiallyCloserUrgentRiskInterruptsSamePriorityOnce() {
        val arbiter = SpeechArbiter()
        val first = SpeechRequest("first","risk:chair:1:URGENT","chair",SpeechPriority.URGENT_SAFETY,
            100,1_600,riskMeters=0.70f)
        val closer = SpeechRequest("closer","risk:unknown:1:URGENT","unknown",SpeechPriority.URGENT_SAFETY,
            200,1_700,riskMeters=0.40f)
        assertEquals(SpeechDecision.START,arbiter.submit(first,100))
        assertEquals(SpeechDecision.START,arbiter.submit(closer,200))
        assertEquals("closer",arbiter.active?.id)
        val similar = SpeechRequest("similar","risk:phone:1:URGENT","phone",SpeechPriority.URGENT_SAFETY,
            300,1_800,riskMeters=0.35f)
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(similar,300))
        assertEquals("closer",arbiter.active?.id)
    }

    @Test fun urgentDirectionQueuesWithoutRestartingObstacleAndFallStillPreempts() {
        val arbiter=SpeechArbiter()
        val obstacle=SpeechRequest("obstacle","risk:chair:1:URGENT","chair",
            SpeechPriority.URGENT_SAFETY,100,1_600,riskMeters=0.5f)
        val direction=SpeechRequest("direction","risk:direction:LEFT","left",
            SpeechPriority.URGENT_SAFETY,200,1_700,riskMeters=0.5f)
        assertEquals(SpeechDecision.START,arbiter.submit(obstacle,100))
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(direction,200))
        assertEquals(SpeechDecision.DUPLICATE,
            arbiter.submit(direction.copy(id="again"),250))
        assertEquals(SpeechDecision.START,
            arbiter.submit(request("fall",SpeechPriority.FALL,300),300))
        assertEquals(SpeechDecision.QUEUED,
            arbiter.submit(direction.copy(id="later",key="risk:direction:RIGHT"),400))
    }
    @Test fun cautionDirectionWaitsUntilTheObstacleSentenceFinishes() {
        val arbiter=SpeechArbiter()
        val obstacle=SpeechRequest("obstacle","risk:chair:1:CAUTION","chair",
            SpeechPriority.SAFETY,100,1_600,riskMeters=1.5f)
        val direction=SpeechRequest("direction","risk:direction:LEFT","left",
            SpeechPriority.SAFETY,200,1_700,riskMeters=1.5f)
        assertEquals(SpeechDecision.START,arbiter.submit(obstacle,100))
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(direction,200))
        assertEquals("obstacle",arbiter.active?.id)
        assertEquals("direction", arbiter.complete("obstacle", 300)?.id)
    }
    @Test fun foundConfirmationWaitsForTheCurrentUrgentSentence() {
        val arbiter=SpeechArbiter();arbiter.submit(nav("urgent",RiskLevel.URGENT,100),100)
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(request("found",SpeechPriority.FIND_SUCCESS,200),200))
        assertEquals("urgent",arbiter.active?.id)
        assertEquals("found",arbiter.complete("urgent",800)?.id)
    }
    @Test fun freshUrgentInterruptsFoundAndThenReplaysTheConfirmation() {
        val arbiter=SpeechArbiter();arbiter.submit(request("found",SpeechPriority.FIND_SUCCESS,100),100)
        assertEquals(SpeechDecision.START,arbiter.submit(nav("u1",RiskLevel.URGENT,200),200))
        assertTrue("found" in arbiter.retainedIds())
        assertEquals("found",arbiter.complete("u1",800)?.id)
    }
    @Test fun repeatedUrgentAndDirectionUpdatesCannotInterruptFoundAgain() {
        for(mode in listOf(RiskSpeechMode.REPEAT,RiskSpeechMode.DIRECTION_UPDATE,RiskSpeechMode.CLEAR)) {
            val arbiter=SpeechArbiter();arbiter.submit(request("found",SpeechPriority.FIND_SUCCESS,100),100)
            assertEquals(SpeechDecision.QUEUED,arbiter.submit(nav("u",RiskLevel.URGENT,200).copy(riskMode=mode),200))
            assertEquals("found",arbiter.active?.id)
        }
    }
    @Test fun fallStillPreemptsFoundConfirmation() {
        val arbiter=SpeechArbiter();arbiter.submit(request("found",SpeechPriority.FIND_SUCCESS,100),100)
        assertEquals(SpeechDecision.START,arbiter.submit(request("fall",SpeechPriority.FALL,200),200))
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(request("successPending",SpeechPriority.FIND_SUCCESS,300),300))
    }
    @Test fun ordinaryFindGuidanceDoesNotBlockEmergencyWarnings() {
        val arbiter=SpeechArbiter();arbiter.submit(request("guidance",SpeechPriority.ORDINARY,100),100)
        assertEquals(SpeechDecision.START,arbiter.submit(nav("urgent",RiskLevel.URGENT,200),200))
    }
    @Test fun foundIsRetainedWhenUrgentIsSpeakingAndTheQueueIsFull() {
        val arbiter=SpeechArbiter();arbiter.submit(nav("urgent",RiskLevel.URGENT,100),100)
        repeat(8) {arbiter.submit(request("queued$it",SpeechPriority.ORDINARY,110L+it),110L+it)}
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(request("found",SpeechPriority.FIND_SUCCESS,200),200))
        assertEquals("urgent",arbiter.active?.id)
        assertEquals("found",arbiter.complete("urgent",800)?.id)
    }
    @Test fun interruptedFoundCoreAndDetailsWaitForUrgentThenReplayInOrder() {
        val arbiter=SpeechArbiter();arbiter.submit(request("success",SpeechPriority.FIND_SUCCESS,100),100)
        arbiter.submit(nav("urgent",RiskLevel.URGENT,200),200)
        val detail=SpeechRequest("detail","find:success-info:1","右侧约40厘米",SpeechPriority.ORDINARY,300,8_000)
        arbiter.submit(detail,300,queueOnly=true)
        assertEquals("success",arbiter.complete("urgent",1_000)?.id)
        assertEquals("detail",arbiter.complete("success",1_500)?.id)
    }
    @Test fun interruptedFreshSuccessDetailCanResumeWithoutRepeatingFoundSentence() {
        val arbiter=SpeechArbiter()
        val detail=SpeechRequest("detail","find:success-info:1","右侧约40厘米",SpeechPriority.ORDINARY,100,8_000)
        arbiter.submit(detail,100);arbiter.submit(nav("urgent",RiskLevel.URGENT,200),200)
        assertTrue("detail" in arbiter.retainedIds())
        assertEquals("detail",arbiter.complete("urgent",1_000)?.id)
    }

}
