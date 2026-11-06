package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class SceneFlowTest {
    private fun stationary(flow: SceneFlow, time: Long = 0) {
        flow.observe(time, safe = true, moving = false)
        flow.observe(time + 1_500, safe = true, moving = false)
    }
    @Test fun aStationaryEpisodeUploadsOnlyOnceAndRearmsAfterMovement() {
        val flow = SceneFlow()
        assertFalse(flow.observe(0, true, false))
        assertNull(flow.begin(1_499, false))
        assertTrue(flow.observe(1_500, true, false))
        val first = flow.begin(1_500, false)!!
        assertTrue(flow.finish(first.id, 2_000))
        assertFalse(flow.observe(20_000, true, false))
        flow.observe(21_000, false, true)
        flow.observe(22_000, false, true)
        assertFalse(flow.observe(23_000, true, false))
        assertTrue(flow.observe(24_500, true, false))
    }
    @Test fun movementDuringNetworkInvalidatesLateResultsEvenAfterStopping() {
        val flow = SceneFlow(); stationary(flow)
        val ticket = flow.begin(1_500, false)!!
        flow.observe(2_000, false, true)
        stationary(flow, 3_000)
        assertFalse(flow.finish(ticket.id, 4_600))
        assertFalse(flow.valid(ticket.id, 4_600))
    }
    @Test fun detailTakesNewTicketAndOldCompletionCannotFinishNewTask() {
        val flow = SceneFlow(); stationary(flow)
        val brief = flow.begin(1_500, false)!!
        val detail = flow.begin(1_600, true)!!
        assertNotEquals(brief.id, detail.id)
        assertTrue(detail.detailed)
        assertFalse(flow.finish(brief.id, 1_700))
        assertTrue(flow.pending)
        assertTrue(flow.finish(detail.id, 1_800))
    }
    @Test fun cancelledOrSafetyInterruptedRequestsNeverResumeAndDoNotRetryAutomatically() {
        val flow = SceneFlow(); stationary(flow)
        val ticket = flow.begin(1_500, false)!!
        flow.cancel()
        assertFalse(flow.finish(ticket.id, 2_000))
        assertFalse(flow.observe(30_000, true, false))
    }
    @Test fun disconnectAndReconnectDoesNotRepeatAutomaticUpload() {
        val flow = SceneFlow(); stationary(flow)
        val ticket = flow.begin(1_500, false)!!
        flow.observe(2_000, false, false)
        stationary(flow, 5_000)
        assertFalse(flow.finish(ticket.id, 7_000))
        assertFalse(flow.observe(8_000, true, false))
    }
    @Test fun expiryHasATerminalStateAndCannotBeSpokenLater() {
        val flow = SceneFlow(); stationary(flow)
        val ticket = flow.begin(1_500, false)!!
        assertFalse(flow.valid(ticket.id, ticket.expiresMs))
        flow.observe(ticket.expiresMs, true, false)
        assertFalse(flow.pending)
        assertNull(flow.ticket)
        assertFalse(flow.finish(ticket.id, ticket.expiresMs + 1))
    }
    @Test fun anUnknownMotionStateCannotStartARequest() {
        val flow = SceneFlow()
        flow.observe(0, false, false)
        assertNull(flow.begin(30_000, false))
    }
    @Test fun cancellingSceneSpeechPreservesLocalSafetyQueue() {
        val arbiter = SpeechArbiter()
        val scene = SpeechRequest("s", "scene:1", "概要", SpeechPriority.ENVIRONMENT, 0, 10_000)
        val safety = SpeechRequest("r", "risk:test", "停下", SpeechPriority.SAFETY, 1, 10_000)
        arbiter.submit(scene, 0)
        assertEquals(SpeechDecision.QUEUED, arbiter.submit(safety, 1))
        arbiter.cancelPrefix("scene:")
        assertEquals("r",arbiter.resume(2)?.id)
        assertNull(arbiter.complete("r", 2))
    }
    @Test fun detailVoiceCommandAndSpacedChineseAreRecognized() {
        assertEquals(VoiceIntent.DESCRIBE_DETAIL, CommandIntentParser.parse("再 详细 一点。"))
        assertEquals(VoiceIntent.DESCRIBE_SCENE, CommandIntentParser.parse("环境 描述"))
        assertEquals(VoiceIntent.CANCEL_TASK, CommandIntentParser.parse("取消"))
    }
    @Test fun recognitionTestCanStartWithoutMotionCalibrationButNormalModeCannot() {
        val state = OperationalState(device = DeviceState.STREAMING)
        assertTrue(SceneGate.ready(state, 2_000, 1_900, true, 30_000))
        assertFalse(SceneGate.ready(state, 2_000, 1_900, false, 0))
        val flow = SceneFlow()
        flow.observe(2_000, true, false)
        assertNotNull(flow.begin(2_000, false, requireStable = false))
    }
    @Test fun recognitionTestStillRejectsExpiredVideoAndFallEpisodes() {
        val state = OperationalState(device = DeviceState.STREAMING)
        assertFalse(SceneGate.ready(state, 2_000, 500, true, 0))
        assertFalse(SceneGate.ready(state.copy(fall = FallState.CONFIRMING), 2_000, 1_900, true, 0))
        assertFalse(SceneGate.ready(state.copy(device = DeviceState.DISCONNECTED), 2_000, 1_900, true, 0))
    }
    @Test fun productionModeStillRejectsMovementAndRecentSafetyEvents() {
        val still = OperationalState(device = DeviceState.STREAMING,
            motion = MotionSnapshot(state = MotionState.STILL, stale = false, calibrated = true))
        assertTrue(SceneGate.ready(still, 2_000, 1_900, false, 0))
        assertFalse(SceneGate.ready(still.copy(motion = still.motion.copy(state = MotionState.MOVING)), 2_000, 1_900, false, 0))
        assertFalse(SceneGate.ready(still, 2_000, 1_900, false, 3_000))
    }
    @Test fun slowSuccessfulRecognitionDoesNotConsumeSpeechTime() {
        val flow = SceneFlow(); stationary(flow)
        val ticket = flow.begin(1_500, true)!!
        assertTrue(flow.finish(ticket.id, 21_500))
        assertTrue(flow.admitSpeech(ticket.id, 22_000))
        flow.observe(50_000, true, false)
        assertTrue(flow.speaking)
        assertTrue(flow.valid(ticket.id, 50_000))
        flow.complete(ticket.id)
        assertNull(flow.ticket)
    }
    @Test fun aResponseMustStillStartBeforeItsQueueDeadline() {
        val flow = SceneFlow(); stationary(flow)
        val ticket = flow.begin(1_500, false)!!
        assertTrue(flow.finish(ticket.id, 2_000))
        assertFalse(flow.admitSpeech(ticket.id, 10_000))
        flow.observe(10_000, true, false)
        assertNull(flow.ticket)
    }
    @Test fun movementAndCancellationStillStopSpeechAfterAdmission() {
        val flow = SceneFlow(); stationary(flow)
        val ticket = flow.begin(1_500, true)!!
        flow.finish(ticket.id, 2_000); flow.admitSpeech(ticket.id, 2_100)
        flow.observe(3_000, false, true)
        assertFalse(flow.valid(ticket.id, 3_000)); assertFalse(flow.speaking)
    }
    @Test fun aStuckSpeechEngineHasItsOwnDeadline() {
        val flow = SceneFlow(); stationary(flow)
        val ticket = flow.begin(1_500, true)!!
        flow.finish(ticket.id, 2_000); flow.admitSpeech(ticket.id, 2_100)
        flow.observe(92_100, true, false)
        assertNull(flow.ticket); assertFalse(flow.speaking)
    }
}
