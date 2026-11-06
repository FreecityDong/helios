package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class LocalRiskEvaluatorTest {
    private val moving = MotionSnapshot(state=MotionState.MOVING,stale=false,calibrated=true)
    private fun box(meters: Float?, left: Float=230f, right: Float=410f, classId: Int=0,
        quality: Float=0.8f) = TargetBox(classId,0.8f,left,160f,right,460f,
        DepthEstimate(meters,quality,if (meters == null) "unknown" else "ok"))
    private fun step(rule: LocalRiskEvaluator, time: Long, boxes: List<TargetBox>,
        motion: MotionSnapshot=moving, session: SessionState=SessionState.ACTIVE,
        fresh: Boolean=true) = rule.evaluate(time,boxes,640,480,motion,session,fresh)

    @Test fun approachingTargetTriggersShortLivedCaution() {
        val rule=LocalRiskEvaluator()
        assertNull(step(rule,1_000,listOf(box(2.2f))).event)
        assertNull(step(rule,1_250,listOf(box(1.9f))).event)
        val result=step(rule,1_500,listOf(box(1.5f)))
        assertEquals(RiskLevel.CAUTION,result.event?.level)
        assertEquals(3_000L,result.event?.expiresMs)
        assertTrue(result.event!!.closingMetersPerSecond >= 0.2f)
    }

    @Test fun slowApproachTriggersCautionAtOnePointEightMetersWithoutShortTtc() {
        val rule=LocalRiskEvaluator()
        assertNull(step(rule,1_000,listOf(box(1.95f))).event)
        assertNull(step(rule,1_250,listOf(box(1.88f))).event)
        val result=step(rule,1_500,listOf(box(1.80f)))
        assertEquals(RiskLevel.CAUTION,result.event?.level)
        assertTrue(result.event!!.timeToContactSeconds!! > 3.5f)
    }

    @Test fun recedingAndCrossingDoNotWarn() {
        val receding=LocalRiskEvaluator()
        for ((t,d) in listOf(1_000L to 1.2f,1_250L to 1.4f,1_500L to 1.6f)) {
            assertNull(step(receding,t,listOf(box(d))).event)
        }
        assertEquals("receding_or_crossing",step(receding,1_750,listOf(box(1.8f))).reason)
        val crossing=LocalRiskEvaluator()
        for (t in 1_000L..1_750L step 250L) assertNull(step(crossing,t,listOf(box(1.2f))).event)
    }

    @Test fun closeTargetWarnsAfterTwoObservationsAndCoolsDown() {
        val rule=LocalRiskEvaluator()
        assertNull(step(rule,1_000,listOf(box(0.65f))).event)
        val alert=step(rule,1_250,listOf(box(0.64f))).event
        assertEquals(RiskLevel.URGENT,alert?.level)
        assertEquals("already_alerted",step(rule,1_500,listOf(box(0.63f))).reason)
        assertNull(step(rule,2_000,emptyList()).event)
    }

    @Test fun sustainedSameHazardDoesNotRepeatAfterCooldown() {
        val rule=LocalRiskEvaluator()
        step(rule,1_000,listOf(box(0.6f)))
        assertNotNull(step(rule,1_250,listOf(box(0.6f))).event)
        for (time in 1_500L..13_500L step 250L) {
            assertNull(step(rule,time,listOf(box(0.6f))).event)
        }
    }

    @Test fun briefTrackingDropoutDoesNotRearmContinuousHazardAfterCooldown() {
        val rule=LocalRiskEvaluator()
        step(rule,1_000,listOf(box(0.6f)))
        assertNotNull(step(rule,1_250,listOf(box(0.6f))).event)
        for (time in 1_500L..10_000L step 250L) assertNull(step(rule,time,listOf(box(0.6f))).event)
        step(rule,10_250,emptyList())
        step(rule,11_250,listOf(box(0.6f)))
        assertEquals("continuous_hazard",step(rule,11_500,listOf(box(0.6f))).reason)
    }

    @Test fun nonClosestHazardStaysContinuousWhileAnotherIsCloser() {
        val rule=LocalRiskEvaluator()
        val chair=box(0.60f,classId=56)
        val person=box(0.70f,classId=0)
        step(rule,1_000,listOf(chair,person))
        assertEquals(56,step(rule,1_250,listOf(chair,person)).event?.classId)
        for (time in 1_500L..10_000L step 250L)
            step(rule,time,listOf(chair,person.copy(distance=DepthEstimate(0.40f,0.8f,"ok"))))
        step(rule,10_250,emptyList())
        step(rule,11_250,listOf(chair))
        assertEquals("continuous_hazard",step(rule,11_500,listOf(chair)).reason)
    }

    @Test fun longContinuousHazardDoesNotRearmAfterThirtySeconds() {
        val rule=LocalRiskEvaluator()
        step(rule,1_000,listOf(box(0.6f)))
        assertNotNull(step(rule,1_250,listOf(box(0.6f))).event)
        for (time in 1_500L..34_000L step 250L) assertNull(step(rule,time,listOf(box(0.6f))).event)
        step(rule,34_250,emptyList())
        step(rule,35_250,listOf(box(0.6f)))
        assertEquals("continuous_hazard",step(rule,35_500,listOf(box(0.6f))).reason)
    }

    @Test fun hazardCanRearmAfterLongQuietGap() {
        val rule=LocalRiskEvaluator()
        step(rule,1_000,listOf(box(0.6f)))
        assertNotNull(step(rule,1_250,listOf(box(0.6f))).event)
        step(rule,2_000,emptyList())
        assertNull(step(rule,11_000,listOf(box(0.6f))).event)
        assertEquals(RiskLevel.URGENT,step(rule,11_250,listOf(box(0.6f))).event?.level)
    }

    @Test fun cautionCanEscalateToUrgentWithoutWaitingForCooldown() {
        val rule=LocalRiskEvaluator()
        step(rule,1_000,listOf(box(2.1f)))
        step(rule,1_250,listOf(box(1.6f)))
        val caution=step(rule,1_500,listOf(box(1.2f))).event!!
        val urgent=step(rule,1_750,listOf(box(0.7f))).event!!
        assertEquals(RiskLevel.CAUTION,caution.level)
        assertEquals(RiskLevel.URGENT,urgent.level)
        assertNotEquals(caution.key,urgent.key)
    }

    @Test fun sharplyWorseningUrgentRiskCanUpdateOnceDuringCooldown() {
        val rule=LocalRiskEvaluator()
        step(rule,1_000,listOf(box(0.70f)))
        assertEquals(RiskLevel.URGENT,step(rule,1_250,listOf(box(0.69f))).event?.level)
        assertNull(step(rule,1_500,listOf(box(0.42f))).event)
        val worsened=step(rule,1_750,listOf(box(0.40f))).event
        assertEquals(RiskLevel.URGENT,worsened?.level)
        assertTrue(worsened!!.key.endsWith(":WORSENED"))
        assertNull(step(rule,2_000,listOf(box(0.29f))).event)
        assertNull(step(rule,2_250,listOf(box(0.28f))).event)
    }

    @Test fun singleDepthDropCannotInterruptUrgentSpeech() {
        val rule=LocalRiskEvaluator()
        step(rule,1_000,listOf(box(0.70f)))
        assertNotNull(step(rule,1_250,listOf(box(0.69f))).event)
        assertNull(step(rule,1_500,listOf(box(0.32f))).event)
        assertNull(step(rule,1_750,listOf(box(0.68f))).event)
    }

    @Test fun sideTargetAndInvalidDepthNeverWarn() {
        val rule=LocalRiskEvaluator()
        assertEquals("outside_forward_area",step(rule,1_000,listOf(box(0.5f,0f,130f))).reason)
        assertEquals("depth_unreliable",step(rule,1_250,listOf(box(null))).reason)
        assertEquals("depth_unreliable",step(rule,1_500,listOf(box(0.5f,quality=0.2f))).reason)
    }

    @Test fun inactiveUnknownAndStaleSensorsSuppressAndClearTrend() {
        val rule=LocalRiskEvaluator()
        step(rule,1_000,listOf(box(1.5f)))
        assertEquals("session_inactive",step(rule,1_250,listOf(box(1.3f)),session=SessionState.PAUSED).reason)
        assertEquals("motion_unknown",step(rule,1_500,listOf(box(1.2f)),motion=MotionSnapshot()).reason)
        assertEquals("sensor_unavailable",step(rule,1_750,listOf(box(1f)),fresh=false).reason)
        assertNull(step(rule,2_000,listOf(box(0.6f))).event)
    }

    @Test fun targetsOfSameClassRemainSeparateByLocation() {
        val rule=LocalRiskEvaluator()
        step(rule,1_000,listOf(box(1.5f,180f,310f),box(2.0f,360f,490f)))
        step(rule,1_250,listOf(box(1.3f,180f,310f),box(2.1f,360f,490f)))
        val result=step(rule,1_500,listOf(box(1.1f,180f,310f),box(2.2f,360f,490f)))
        assertEquals(RiskLevel.CAUTION,result.event?.level)
        assertEquals(1.1f,result.event!!.distanceMeters,0.01f)
    }

    @Test fun unknownNearObstacleNeedsTwoObservationsAndNoYoloBox() {
        val rule=LocalRiskEvaluator()
        val near=UnknownObstacleObservation(0.5f,1)
        assertNull(rule.evaluate(1_000,emptyList(),640,480,moving,SessionState.ACTIVE,true,near).event)
        val alert=rule.evaluate(1_250,emptyList(),640,480,moving,SessionState.ACTIVE,true,near).event
        assertEquals(RiskLevel.URGENT,alert?.level)
        assertEquals(-1,alert?.classId)
        assertNull(rule.evaluate(1_500,emptyList(),640,480,moving,SessionState.ACTIVE,true,near).event)
        assertNull(rule.evaluate(1_750,listOf(box(1.4f)),640,480,moving,SessionState.ACTIVE,true,near).event)
    }

    @Test fun unknownNearObstacleDoesNotTriggerOnStaleOrUnstableDepth() {
        val rule=LocalRiskEvaluator()
        val near=UnknownObstacleObservation(0.5f,1)
        rule.evaluate(1_000,emptyList(),640,480,moving,SessionState.ACTIVE,true,near)
        assertNull(rule.evaluate(1_250,emptyList(),640,480,moving,SessionState.ACTIVE,false,near).event)
        assertNull(rule.evaluate(1_500,emptyList(),640,480,moving,SessionState.ACTIVE,true,near).event)
        assertNull(rule.evaluate(1_750,emptyList(),640,480,moving,SessionState.ACTIVE,true,
            UnknownObstacleObservation(0.3f,1)).event)
    }

    @Test fun briefYoloDropoutDoesNotRepeatNamedWarningAsUnknown() {
        val rule=LocalRiskEvaluator()
        step(rule,1_000,listOf(box(0.55f)))
        assertNotNull(step(rule,1_250,listOf(box(0.55f))).event)
        val near=UnknownObstacleObservation(0.53f,1)
        rule.evaluate(1_500,emptyList(),640,480,moving,SessionState.ACTIVE,true,near)
        assertEquals("recent_named_target",rule.evaluate(1_750,emptyList(),640,480,
            moving,SessionState.ACTIVE,true,near).reason)
    }

    @Test fun dispatchRejectsPausedDisconnectedStaleAndExpiredEvents() {
        fun reason(now: Long=1_000, expires: Long=2_000, session: SessionState=SessionState.ACTIVE,
            device: DeviceState=DeviceState.STREAMING, motion: MotionSnapshot=moving,
            meters: Float=0.6f) = LocalRiskDispatchPolicy.reason(now,expires,RiskLevel.URGENT,
            "risk:0:1",meters,session,device,motion)
        assertEquals("ready",reason())
        assertEquals("session_inactive",reason(session=SessionState.PAUSED))
        assertEquals("device_unavailable",reason(device=DeviceState.DISCONNECTED))
        assertEquals("motion_unavailable",reason(motion=MotionSnapshot()))
        assertEquals("expired",reason(now=2_000))
        assertEquals("invalid_event",reason(meters=Float.NaN))
    }
}
