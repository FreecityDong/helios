package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class FindFlowTest {
    private val target=FindTarget.supported.first()
    private val found=FindObservation(true,.95,FindRegion(.4,.4,.6,.6),2f,true)
    private fun ready(flow: FindFlow, now: Long=0): FindSample {
        flow.sample(now,true);return flow.sample(now+1_500,true)!!
    }
    @Test fun targetExtractionAndUnsupportedNamesAreExplicit() {
        assertEquals("手机",FindTarget.extract("帮我找一下手机。"));assertEquals("杯子",FindTarget.resolve("水杯")?.name)
        assertNull(FindTarget.resolve("药片"));assertNull(FindTarget.extract("手机"))
    }
    @Test fun selectionDoesNotCreateTaskBeforeConfirmation() {
        val selection=FindSelection();val flow=FindFlow();selection.propose(target,0)
        assertFalse(flow.active);assertNull(flow.sample(2_000,true))
        flow.start(selection.confirm(2_000)!!,2_000);assertTrue(flow.active);assertNull(selection.confirm(2_001))
    }
    @Test fun cancelAndTimeoutOfConfirmationNeverStartSearch() {
        val selection=FindSelection();selection.propose(target,0);selection.clear();assertNull(selection.confirm(1))
        selection.propose(target,0);assertNull(selection.confirm(15_000))
    }
    @Test fun firstSampleRequiresStableStillAndOnlyOneRequestAtATime() {
        val flow=FindFlow();flow.start(target,0)
        assertNull(flow.sample(0,true));assertNull(flow.sample(1_499,true));assertNotNull(flow.sample(1_500,true))
        assertNull(flow.sample(9_000,true));assertEquals(1,flow.attempts)
    }
    @Test fun threeMinutesStillAfterGuidanceNeverCausesAnotherRequest() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow)
        flow.accept(a,found,1_500,2_000);flow.completeGuidance()
        for(now in 3_000L..180_000L step 100) { flow.observeMotion(false);assertNull(flow.sample(now,true)) }
        assertEquals(1,flow.attempts);assertTrue(flow.active)
    }
    @Test fun notFoundAlsoConsumesExactlyOneStillEpisode() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow)
        assertEquals("not_found",flow.accept(a,FindObservation(false),1_500,2_000))
        assertNull(flow.sample(5_000,true));flow.completeGuidance();assertNull(flow.sample(45_000,true))
        assertEquals(1,flow.attempts);assertTrue(flow.active)
    }
    @Test fun oneFreshNearObservationCompletesWithoutCenterOrSecondFrame() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow)
        assertEquals("reached",flow.accept(a,found.copy(meters=.5f),1_500,2_000))
        assertTrue(flow.targetReached);assertTrue(flow.resultValid(2_000));assertEquals(1,flow.attempts)
    }
    @Test fun farTargetWaitsForMotionThenGivesFreshGuidance() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow)
        assertEquals("found",flow.accept(a,found,1_500,2_000));flow.completeGuidance()
        flow.observeMotion(true);flow.sample(4_000,false)
        val b=ready(flow,6_000)
        assertEquals("guiding",flow.accept(b,found.copy(meters=1f),7_500,8_000))
        flow.completeGuidance();assertNull(flow.sample(20_000,true));assertEquals(2,flow.attempts)
    }
    @Test fun unknownOrUntrustedNearDepthCannotCompleteTask() {
        for(observation in listOf(found.copy(meters=.4f,depthVerified=false),found.copy(meters=null),found.copy(meters=.51f),found.copy(meters=Float.NaN))) {
            val flow=FindFlow();flow.start(target,0);val a=ready(flow)
            assertEquals("found",flow.accept(a,observation,1_500,2_000));assertFalse(flow.targetReached)
        }
    }
    @Test fun lowConfidenceCannotBeSpokenOrRetriedWhileStill() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow)
        assertEquals("not_found",flow.accept(a,found.copy(confidence=.79),1_500,2_000));assertNull(flow.result)
        assertNull(flow.sample(10_000,true))
    }
    @Test fun cancelledAndReplacedTasksDiscardLateResults() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow);flow.cancel()
        assertEquals("late",flow.accept(a,found,1_500,2_000));assertFalse(flow.active)
        flow.start(target,3_000);assertEquals("late",flow.accept(a,found,1_500,4_000))
    }
    @Test fun movingThenStillAutomaticallyStartsNewRoundAndNewFrame() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow)
        flow.observeMotion(true);flow.sample(1_600,false);assertNull(flow.pending)
        assertEquals("late",flow.accept(a,found,1_500,2_000))
        val b=ready(flow,4_000);assertEquals(2,flow.round);assertEquals(1,flow.roundAttempts)
        assertEquals("found",flow.accept(b,found,5_500,6_000))
    }
    @Test fun safetyOrStaleFrameDoesNotPretendThatTheWearerMoved() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow);flow.accept(a,found,1_500,2_000)
        flow.invalidate("safety");assertNull(flow.result)
        assertNull(flow.sample(6_000,true));assertNull(flow.sample(8_000,true))
        flow.observeMotion(true);assertNotNull(ready(flow,10_000))
    }
    @Test fun repeatedMotionSamplesOnlyArmOneNextStillEpisode() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow);flow.accept(a,found,1_500,2_000);flow.completeGuidance()
        repeat(20) { flow.observeMotion(true);flow.sample(3_000L+it*100,false) }
        val b=ready(flow,6_000);flow.accept(b,found,7_500,8_000);flow.completeGuidance()
        assertNull(flow.sample(12_000,true));assertEquals(2,flow.attempts)
    }
    @Test fun fallNeedsExplicitResumeAndLongPauseDoesNotEndTask() {
        val flow=FindFlow();flow.start(target,0);flow.observeMotion(true);flow.invalidate("fall",true)
        assertNull(flow.sample(90_000,true));assertTrue(flow.needsConfirmation);assertTrue(flow.active)
        flow.resume(100_000);assertNotNull(ready(flow,100_000))
    }
    @Test fun explicitContinueAllowsOneFreshObservationWithoutAutomaticRetry() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow);flow.accept(a,found,1_500,2_000);flow.completeGuidance()
        flow.resume(10_000);val b=ready(flow,10_000);flow.accept(b,found,11_500,12_000);flow.completeGuidance()
        assertNull(flow.sample(30_000,true));assertEquals(2,flow.attempts)
    }
    @Test fun staleResponseCannotBeSpokenOrAutomaticallyRetried() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow)
        assertEquals("stale",flow.accept(a,found,1_500,9_501));assertNull(flow.result);assertNull(flow.sample(20_000,true))
    }
    @Test fun admittedSpeechCanFinishAfterAdmissionDeadlineAndMovementStillRevokesIt() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow);flow.accept(a,found,1_500,2_000)
        assertTrue(flow.admitSpeech(2_000));assertFalse(flow.resultValid(10_000));assertEquals(2_000L,flow.speechStartedMs)
        flow.invalidate("moving");assertNull(flow.result);assertNull(flow.speechStartedMs)
    }
    @Test fun expiredResultCannotEnterSpeech() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow);flow.accept(a,found,1_500,2_000)
        assertFalse(flow.admitSpeech(10_000));assertNull(flow.speechStartedMs)
    }
    @Test fun reachedResultIsLockedBeforeSpeechAndSurvivesWarningsAndMotion() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow)
        assertEquals("reached",flow.accept(a,found.copy(meters=.4f),1_500,2_000))
        val completed=flow.completion!!
        flow.invalidate("urgent obstacle");flow.observeMotion(true);flow.sample(3_000,false)
        flow.waitForAdjustment("safety cooldown");flow.completeGuidance()
        assertEquals(completed,flow.completion);assertTrue(flow.targetReached)
        assertNull(flow.sample(20_000,true));assertEquals(1,flow.attempts)
    }
    @Test fun fallDoesNotRequireRestartingAnAlreadyCompletedSearch() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow)
        flow.accept(a,found.copy(meters=.5f),1_500,2_000)
        flow.invalidate("fall",true);assertFalse(flow.needsConfirmation)
        flow.resume(100_000);assertNotNull(flow.completion);assertTrue(flow.targetReached)
        assertNull(flow.sample(100_000,true))
    }
    @Test fun successFactOutlivesPositionFreshnessAndNewTaskClearsIt() {
        val flow=FindFlow();flow.start(target,0);val a=ready(flow)
        flow.accept(a,found.copy(meters=.5f),1_500,2_000)
        assertFalse(flow.resultValid(100_000));assertNotNull(flow.completion)
        flow.start(target,101_000);assertNull(flow.completion);assertFalse(flow.targetReached)
    }

}
