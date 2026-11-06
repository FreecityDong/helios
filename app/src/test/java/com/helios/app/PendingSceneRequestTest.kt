package com.helios.app
import org.junit.Assert.*
import org.junit.Test

class PendingSceneRequestTest {
    private fun motion(state:MotionState)=MotionSnapshot(state=state,stale=false,calibrated=true)
    @Test fun aQuestionAcceptedWhileMovingResumesExactlyOnceAfterStill() {
        val pending=PendingSceneRequest();val question=DeferredSceneRequest(false,"杯子是什么颜色")
        pending.offer(question)
        assertNull(pending.take(motion(MotionState.MOVING),true));assertTrue(pending.waiting)
        assertNull(pending.take(motion(MotionState.SHAKING),true));assertTrue(pending.waiting)
        assertEquals(question,pending.take(motion(MotionState.STILL),true))
        assertFalse(pending.waiting);assertNull(pending.take(motion(MotionState.STILL),true))
    }
    @Test fun speechAndChineseRecordingFinishBeforeTheDeferredCaptureStarts() {
        val pending=PendingSceneRequest();val request=DeferredSceneRequest(true,null);pending.offer(request)
        assertNull(pending.take(motion(MotionState.STILL),false));assertTrue(pending.waiting)
        assertEquals(request,pending.take(motion(MotionState.STILL),true))
    }
    @Test fun staleOrUncalibratedStillCannotUpload() {
        val pending=PendingSceneRequest();pending.offer(DeferredSceneRequest(false,"前方有什么"))
        assertNull(pending.take(motion(MotionState.STILL).copy(stale=true),true))
        assertNull(pending.take(motion(MotionState.STILL).copy(calibrated=false),true))
        assertNull(pending.take(motion(MotionState.UNKNOWN),true));assertTrue(pending.waiting)
    }
    @Test fun cancellationCannotUploadLaterWhenTheUserStops() {
        val pending=PendingSceneRequest();pending.offer(DeferredSceneRequest(false,"杯子颜色"))
        assertTrue(pending.clear());assertNull(pending.take(motion(MotionState.STILL),true))
    }
    @Test fun aNewQuestionReplacesTheOldDeferredQuestion() {
        val pending=PendingSceneRequest();pending.offer(DeferredSceneRequest(false,"杯子颜色"))
        val latest=DeferredSceneRequest(false,"桌上有什么");pending.offer(latest)
        assertEquals(latest,pending.take(motion(MotionState.STILL),true))
    }
    @Test fun manualCaptureUsesCurrentStillWithoutAnotherAutomaticDelay() {
        val flow=SceneFlow();flow.observe(1_000,true,false)
        assertNull(flow.begin(1_000,false))
        assertNotNull(flow.begin(1_000,false,requireStable=false))
    }
    @Test fun enteringMotionStillCancelsAnAdmittedManualAnalysis() {
        val flow=SceneFlow();flow.observe(1_000,true,false)
        val ticket=flow.begin(1_000,false,requireStable=false)!!
        flow.observe(1_100,false,true)
        assertFalse(flow.valid(ticket.id,1_100));assertNull(flow.ticket)
    }
    @Test fun enteringMotionStillInvalidatesTheSceneAlreadyBeingSpoken() {
        val flow=SceneFlow();flow.observe(1_000,true,false)
        val ticket=flow.begin(1_000,false,requireStable=false)!!
        assertTrue(flow.finish(ticket.id,1_100));assertTrue(flow.admitSpeech(ticket.id,1_100))
        flow.observe(1_200,false,true)
        assertFalse(flow.speaking);assertFalse(flow.valid(ticket.id,1_200))
    }
    @Test fun aNewImageDoesNotInheritTheOldViewTurningLatch() {
        val gate=SceneMotionGate()
        assertFalse(gate.update(1_000,motion(MotionState.STILL).copy(rotationRate=.8)))
        assertFalse(gate.update(1_100,motion(MotionState.STILL)))
        gate.reset()
        assertTrue(gate.update(1_100,motion(MotionState.STILL)))
    }
}
