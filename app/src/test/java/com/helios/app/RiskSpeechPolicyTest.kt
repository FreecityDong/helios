package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class RiskSpeechPolicyTest {
    private fun risk(id: String, now: Long, meters: Float = 0.8f,
        priority: SpeechPriority = SpeechPriority.WARNING_SAFETY,
        direction: AvoidanceDirection? = null, episode: Int = 1,
        band: ImageHeightBand = ImageHeightBand.FRONT) = SpeechRequest(id,
        "risk:nav:$episode:TURN_GUIDANCE:${when(priority) {
            SpeechPriority.URGENT_SAFETY -> "URGENT"; SpeechPriority.SAFETY -> "CAUTION"; else -> "WARNING"
        }}:$id:h0", "risk", priority, now, now + 1_500,
        riskMeters = meters, riskDirection = direction, riskBand = band)
    private fun commit(p: RiskSpeechPolicy, request: SpeechRequest, now: Long, moving: Boolean = false) {
        val assessment = p.assess(request, now, moving = moving, occupied = false)
        assertTrue(assessment.allowed)
        p.started(request.copy(riskMode = assessment.mode), now, moving)
    }

    @Test fun unchangedStationaryObstacleDoesNotKeepPreemptingScene() {
        val p = RiskSpeechPolicy(); commit(p, risk("a", 0), 0)
        for (now in 2_000L..24_000L step 2_000) {
            val assessment = p.assess(risk("r$now", now), now, moving = false, occupied = true)
            assertFalse(assessment.allowed)
            assertEquals("stationary_unchanged_hazard", assessment.reason)
        }
    }
    @Test fun aNewEpisodeOrHeightInterruptsEvenWhenStationaryAndSceneIsBusy() {
        val p = RiskSpeechPolicy(); commit(p, risk("a", 0), 0)
        assertEquals(RiskSpeechMode.ALERT, p.assess(risk("b", 100, episode = 2), 100, false, true).mode)
        assertEquals(RiskSpeechMode.ALERT, p.assess(risk("c", 100, band = ImageHeightBand.HIGH), 100, false, true).mode)
    }
    @Test fun escalationAndClosingDistanceInterruptSceneWithoutUserMovement() {
        val p = RiskSpeechPolicy(); commit(p, risk("a", 0), 0)
        assertEquals(RiskSpeechMode.ALERT, p.assess(risk("b", 100, 0.45f, SpeechPriority.URGENT_SAFETY), 100, false, true).mode)
        val q = RiskSpeechPolicy(); commit(q, risk("a", 0, 0.9f), 0)
        assertEquals("materially_closer", q.assess(risk("b", 100, 0.65f), 100, false, true).reason)
    }
    @Test fun cumulativeApproachIsComparedWithLastAnnouncedDistance() {
        val p = RiskSpeechPolicy(); commit(p, risk("a", 0, 0.95f), 0)
        assertFalse(p.assess(risk("b", 100, 0.85f), 100, false, true).allowed)
        assertEquals(RiskSpeechMode.ALERT, p.assess(risk("c", 200, 0.72f), 200, false, true).mode)
    }
    @Test fun aPersonRecedingThenApproachingIsNotHiddenByAnOldUrgentAlert() {
        val p = RiskSpeechPolicy(); commit(p, risk("a", 0, 0.4f, SpeechPriority.URGENT_SAFETY), 0)
        assertFalse(p.assess(risk("b", 100, 1.4f, SpeechPriority.SAFETY), 100, false, true).allowed)
        assertEquals(RiskSpeechMode.ALERT, p.assess(risk("c", 200, 0.8f), 200, false, true).mode)
    }
    @Test fun directionOnlyChangesNeverInterruptAnEnvironmentTask() {
        val p = RiskSpeechPolicy(); commit(p, risk("a", 0, direction = AvoidanceDirection.RIGHT), 0, moving = true)
        assertEquals("direction_update_deferred", p.assess(risk("b", 200, direction = AvoidanceDirection.LEFT), 200, true, true).reason)
        assertEquals(RiskSpeechMode.DIRECTION_UPDATE, p.assess(risk("c", 300, direction = AvoidanceDirection.LEFT), 300, true, false).mode)
    }
    @Test fun directionUpdatesPreserveTheDistanceReferenceForApproachDetection() {
        val p = RiskSpeechPolicy(); commit(p, risk("a", 0, 0.9f), 0, moving = true)
        commit(p, risk("b", 100, 0.8f, direction = AvoidanceDirection.LEFT), 100, moving = true)
        assertEquals("materially_closer", p.assess(risk("c", 200, 0.68f, direction = AvoidanceDirection.LEFT), 200, true, true).reason)
    }
    @Test fun movingRepeatsKeepOriginalIntervalsAndYieldToAUserTask() {
        for ((priority, interval) in listOf(SpeechPriority.SAFETY to 8_000L,
            SpeechPriority.WARNING_SAFETY to 4_000L, SpeechPriority.URGENT_SAFETY to 2_000L)) {
            val p = RiskSpeechPolicy(); commit(p, risk("a", 0, priority = priority), 0, moving = true)
            assertFalse(p.assess(risk("b", interval - 1, priority = priority), interval - 1, true, false).allowed)
            assertFalse(p.assess(risk("c", interval, priority = priority), interval, true, true).allowed)
            assertEquals(RiskSpeechMode.REPEAT, p.assess(risk("d", interval + 1, priority = priority), interval + 1, true, false).mode)
        }
    }
    @Test fun queueingAnAlertDoesNotPretendTheUserHasHeardIt() {
        val p = RiskSpeechPolicy()
        val request = risk("a", 100)
        assertEquals(RiskSpeechMode.ALERT, p.assess(request, 100, false, false).mode)
        assertEquals(RiskSpeechMode.ALERT, p.assess(risk("b", 200), 200, false, true).mode)
    }
    @Test fun aPlaybackErrorRearmsTheHazard() {
        val p = RiskSpeechPolicy(); val request = risk("a", 0); commit(p, request, 0)
        p.failed(request)
        assertEquals(RiskSpeechMode.ALERT, p.assess(risk("b", 100), 100, false, false).mode)
    }
    @Test fun expiryDoesNotConsumeAnUnannouncedHazard() {
        val p = RiskSpeechPolicy()
        assertFalse(p.assess(risk("a", 0), 1_500, false, false).allowed)
        assertEquals(RiskSpeechMode.ALERT, p.assess(risk("b", 2_000), 2_000, false, false).mode)
    }
    @Test fun clearDoesNotActAsANewRiskAndDoesNotInterruptSceneSpeech() {
        val p = RiskSpeechPolicy(); val arbiter = SpeechArbiter()
        commit(p, risk("a", 0), 0)
        val scene = SpeechRequest("s", "scene:1", "scene", SpeechPriority.ENVIRONMENT, 100, 20_000)
        arbiter.submit(scene, 100)
        val clear = risk("clear", 200).copy(key = "risk:nav:1:HEADING_CLEAR:WARNING:2:h0", priority = SpeechPriority.ORDINARY)
        val assessment = p.assess(clear, 200, false, true)
        assertEquals(RiskSpeechMode.CLEAR, assessment.mode)
        assertEquals(SpeechDecision.QUEUED, arbiter.submit(clear.copy(riskMode = assessment.mode), 200))
        assertEquals("s", arbiter.active?.id)
    }
    @Test fun newUrgentRiskPreemptsSceneButFallStillOwnsTheChannel() {
        val arbiter = SpeechArbiter()
        arbiter.submit(SpeechRequest("s", "scene:1", "scene", SpeechPriority.ENVIRONMENT, 0, 20_000), 0)
        assertEquals(SpeechDecision.START, arbiter.submit(risk("a", 100,priority=SpeechPriority.URGENT_SAFETY).copy(riskMode = RiskSpeechMode.ALERT), 100))
        assertEquals(SpeechDecision.START, arbiter.submit(SpeechRequest("f", "fall:1", "fall", SpeechPriority.FALL, 200, 10_000), 200))
        assertEquals(SpeechDecision.QUEUED, arbiter.submit(risk("b", 300, 0.3f, SpeechPriority.URGENT_SAFETY).copy(riskMode = RiskSpeechMode.ALERT), 300))
        assertEquals("f", arbiter.active?.id)
    }
    @Test fun latestDirectionReplacesQueuedDirectionWithoutCuttingCurrentSentence() {
        val arbiter = SpeechArbiter()
        val first = risk("a", 0, direction = AvoidanceDirection.RIGHT).copy(riskMode = RiskSpeechMode.ALERT)
        arbiter.submit(first, 0)
        assertEquals(SpeechDecision.QUEUED, arbiter.submit(risk("b", 100, direction = AvoidanceDirection.LEFT).copy(riskMode = RiskSpeechMode.DIRECTION_UPDATE), 100))
        val latest = risk("c", 200, direction = AvoidanceDirection.LEFT).copy(riskMode = RiskSpeechMode.DIRECTION_UPDATE)
        assertEquals(SpeechDecision.QUEUED, arbiter.submit(latest, 200))
        assertEquals("a", arbiter.active?.id)
        assertEquals("c", arbiter.complete("a", 300)?.id)
    }
    @Test fun closerNavigationRiskWaitsForItsOwnSameSeveritySentence() {
        val arbiter = SpeechArbiter()
        arbiter.submit(risk("a", 0, 0.95f).copy(riskMode = RiskSpeechMode.ALERT), 0)
        assertEquals(SpeechDecision.QUEUED, arbiter.submit(risk("b", 100, 0.70f).copy(riskMode = RiskSpeechMode.ALERT), 100))
    }
    @Test fun stationaryDirectionChangesDoNotBecomeNavigationPrompts() {
        val p = RiskSpeechPolicy(); commit(p, risk("a", 0), 0)
        assertFalse(p.assess(risk("b", 200, direction = AvoidanceDirection.LEFT), 200, false, false).allowed)
        assertFalse(p.assess(risk("c", 300, direction = AvoidanceDirection.RIGHT), 300, false, false).allowed)
    }
    @Test fun walkingWithChangedSpaceOnlyUpdatesSpaceInsteadOfStartingARound() {
        val p = RiskSpeechPolicy(); commit(p, risk("a", 0), 0)
        val r = risk("b", 1_000, direction = AvoidanceDirection.RIGHT)
        val decision = p.assess(r, 1_000, true, false)
        assertEquals("direction_changed", decision.reason)
        p.started(r.copy(riskMode = decision.mode), 1_000, moving = true)
        assertFalse(p.assess(risk("c", 1_100, direction = AvoidanceDirection.RIGHT), 1_100, true, false).allowed)
    }
    @Test fun stationaryWarningHasNoTurnOrGoForwardInstruction() {
        for (level in RiskLevel.entries) {
            val text = LocalRiskSpeech.stationary(level, 0, listOf("人"), ImageHeightBand.FRONT)
            assertTrue(text.contains("人"))
            assertFalse(text.contains("转")); assertFalse(text.contains("前进")); assertFalse(text.contains("停下"))
        }
    }
    @Test fun stoppingCancelsNavigationButPreservesStationaryRiskAndSceneQueue() {
        val arbiter = SpeechArbiter()
        val nav = risk("nav", 0).copy(riskNavigating = true, riskMode = RiskSpeechMode.ALERT)
        arbiter.submit(nav, 0)
        val stationary = risk("still", 100, band = ImageHeightBand.HIGH).copy(riskNavigating = false, riskMode = RiskSpeechMode.ALERT)
        arbiter.submit(stationary, 100)
        val scene = SpeechRequest("s", "scene:1", "scene", SpeechPriority.ENVIRONMENT, 100, 10_000)
        arbiter.submit(scene, 100)
        val removed = arbiter.cancelWhere { it.riskNavigating == true }
        assertEquals("nav", removed?.id)
        assertEquals("still", arbiter.resume(200)?.id)
        assertEquals("s", arbiter.complete("still", 300)?.id)
    }
    @Test fun walkingAgainWithoutChangedRiskDoesNotRestartANavigationRound() {
        val p = RiskSpeechPolicy(); commit(p, risk("a", 0), 0, moving = true)
        p.stoppedMoving()
        assertFalse(p.assess(risk("b", 200), 200, false, false).allowed)
        assertEquals("repeat_interval", p.assess(risk("c", 300), 300, true, false).reason)
    }
}
