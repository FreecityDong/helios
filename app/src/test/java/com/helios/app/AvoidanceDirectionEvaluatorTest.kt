package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class AvoidanceDirectionEvaluatorTest {
    private val moving = MotionSnapshot(state=MotionState.MOVING,stale=false,calibrated=true)
    private fun visual(time: Long, shiftX: Int=0) = VisualMotionEvidence(
        if (shiftX == 0) VisualMotionKind.STABLE else VisualMotionKind.GLOBAL_SHIFT,
        time,12,10,kotlin.math.abs(shiftX).toDouble(),shiftX,0)
    private fun depth(left: Int, center: Int, right: Int): ByteArray {
        val width=120; val height=100
        return ByteArray(width*height*2).also { bytes ->
            for (y in 0 until height) for (x in 0 until width) {
                val mm = when { x < 41 -> left; x < 79 -> center; else -> right }
                val i=(y*width+x)*2
                bytes[i]=mm.toByte(); bytes[i+1]=(mm shr 8).toByte()
            }
        }
    }
    private val alert = LocalRiskEvent(1,"risk:0:1:URGENT",RiskLevel.URGENT,0,
        0.7f,0f,null,1_000,2_500)
    private val facing = doubleArrayOf(1.0,0.0,0.0,0.0)
    private val forwardBox = TargetBox(0,0.8f,40f,20f,80f,90f,
        DepthEstimate(1.5f,0.8f,"ok"))
    private fun step(rule: AvoidanceDirectionEvaluator, time: Long, frame: ByteArray,
        event: LocalRiskEvent?=null, visual: VisualMotionEvidence=visual(time),
        motion: MotionSnapshot=moving, unknownPresent: Boolean=false,
        orientation: DoubleArray?=null, boxes: List<TargetBox> = emptyList()) =
        rule.evaluate(time,frame,120,100,1f,boxes,
            motion,visual,true,true,event,unknownPresent,orientation)

    @Test fun recommendsOnlyStableRelativeOpening() {
        val rule=AvoidanceDirectionEvaluator()
        val frame=depth(2500,700,800)
        assertNull(step(rule,1_000,frame,alert).direction)
        assertNull(step(rule,1_250,frame).direction)
        val ready=step(rule,1_500,frame)
        assertEquals(AvoidanceDirection.LEFT,ready.direction)
        assertTrue(ready.directionReady)
        assertEquals(RiskLevel.URGENT,ready.guidanceLevel)
        assertFalse(step(rule,1_550,frame).directionReady)
        assertFalse(step(rule,1_750,frame).directionReady)
    }

    @Test fun remembersThreeLanesAndResetsOnSceneShift() {
        val rule=AvoidanceDirectionEvaluator()
        val frame=depth(800,700,2600)
        step(rule,1_000,frame,alert); step(rule,1_250,frame)
        assertEquals(AvoidanceDirection.RIGHT,step(rule,1_500,frame).direction)
        assertEquals("scene_shift",step(rule,1_750,frame,visual=visual(1_750,3)).reason)
        assertNull(step(rule,2_000,frame).direction)
    }

    @Test fun bothOpenSidesGiveStableChoiceAndInvalidCenterStillRejects() {
        val rule=AvoidanceDirectionEvaluator()
        val frame=depth(2500,700,2600)
        for (time in 1_000L..1_250L step 250L) assertNull(step(rule,time,frame).direction)
        assertEquals(AvoidanceDirection.RIGHT,step(rule,1_500,frame).direction)
        assertNull(step(rule,1_750,depth(2500,0,2600)).direction)
    }

    @Test fun announcesDepartureOnceAfterSustainedCentralClearance() {
        val rule=AvoidanceDirectionEvaluator()
        val blocked=depth(2500,700,800)
        val clear=depth(2500,2500,2600)
        step(rule,1_000,blocked,alert)
        assertFalse(step(rule,1_500,clear).obstacleCleared)
        assertFalse(step(rule,1_750,clear).obstacleCleared)
        assertFalse(step(rule,2_000,clear).obstacleCleared)
        assertFalse(step(rule,2_250,clear).obstacleCleared)
        assertFalse(step(rule,2_500,clear).obstacleCleared)
        assertTrue(step(rule,2_750,clear).obstacleCleared)
        assertFalse(step(rule,3_000,clear).obstacleCleared)
    }

    @Test fun noDepartureOnSingleClearFrameOrUnreliableVisualMotion() {
        val rule=AvoidanceDirectionEvaluator()
        val blocked=depth(2500,700,800)
        step(rule,1_000,blocked,alert)
        step(rule,1_500,depth(2500,2500,2600))
        step(rule,1_750,blocked)
        assertFalse(step(rule,2_000,depth(2500,2500,2600)).obstacleCleared)
        assertEquals("visual_unavailable",step(rule,2_250,depth(2500,2500,2600),
            visual=VisualMotionEvidence(timeMs=2_250)).reason)
        assertFalse(step(rule,2_500,depth(2500,2500,2600)).obstacleCleared)
    }

    @Test fun unknownObstacleStillDetectedPreventsFalseDeparture() {
        val rule=AvoidanceDirectionEvaluator()
        step(rule,1_000,depth(2500,700,800),alert)
        for (time in 1_500L..3_000L step 250L)
            assertFalse(step(rule,time,depth(2500,2500,2600),
                unknownPresent=true).obstacleCleared)
        assertFalse(step(rule,3_250,depth(2500,2500,2600)).obstacleCleared)
    }

    @Test fun candidateSideWorksWhenOppositeSideHasInvalidDepth() {
        val rule=AvoidanceDirectionEvaluator()
        val frame=depth(0,700,2500)
        step(rule,1_000,frame,alert)
        step(rule,1_250,frame)
        assertEquals(AvoidanceDirection.RIGHT,step(rule,1_500,frame).direction)
    }

    @Test fun briefUncertainVisualFramePausesButDoesNotEraseDirectionHistory() {
        val rule=AvoidanceDirectionEvaluator()
        val frame=depth(2500,700,800)
        step(rule,1_000,frame,alert)
        assertEquals("visual_unavailable",step(rule,1_250,frame,
            visual=VisualMotionEvidence(timeMs=1_250)).reason)
        step(rule,1_500,frame)
        assertEquals(AvoidanceDirection.LEFT,step(rule,1_750,frame).direction)
    }

    @Test fun temporaryShakeDoesNotErasePendingClearance() {
        val rule=AvoidanceDirectionEvaluator()
        step(rule,1_000,depth(2500,700,800),alert)
        assertEquals("motion_or_depth_unavailable",step(rule,1_500,depth(2500,2500,2600),
            motion=MotionSnapshot(state=MotionState.SHAKING,stale=false,calibrated=true)).reason)
        val clear=depth(2500,2500,2600)
        for (time in 1_750L..2_500L step 250L) assertFalse(step(rule,time,clear).obstacleCleared)
        assertTrue(step(rule,2_750,clear).obstacleCleared)
    }

    @Test fun recallsEarlierStableSideWhenNearViewLosesSideDepth() {
        val rule=AvoidanceDirectionEvaluator()
        val open=depth(2500,900,800)
        step(rule,1_000,open,orientation=facing)
        step(rule,1_250,open,orientation=facing)
        assertEquals(AvoidanceDirection.LEFT,
            step(rule,1_500,open,orientation=facing).direction)
        val near=step(rule,1_750,depth(0,500,800),alert,orientation=facing)
        assertEquals(AvoidanceDirection.LEFT,near.direction)
        assertTrue(near.remembered)
        assertFalse(near.directionReady)
        assertEquals("remembered_left",near.reason)
    }

    @Test fun recallExpiresAndIsRevokedByTurnOrNewNearSide() {
        fun prepared(): AvoidanceDirectionEvaluator = AvoidanceDirectionEvaluator().also { rule ->
            val open=depth(2500,900,800)
            for (time in 1_000L..1_500L step 250L)
                step(rule,time,open,orientation=facing)
        }
        val turned=doubleArrayOf(kotlin.math.cos(0.2),0.0,kotlin.math.sin(0.2),0.0)
        assertNull(step(prepared(),1_750,depth(0,500,800),alert,orientation=turned).direction)
        assertNull(step(prepared(),1_750,depth(500,500,800),alert,orientation=facing).direction)
        assertNull(step(prepared(),3_100,depth(0,500,800),alert,orientation=facing).direction)
        assertNull(step(prepared(),1_750,depth(0,500,800),alert,
            visual=visual(1_750,3),orientation=facing).direction)
    }

    @Test fun givesCautionDirectionAtOnePointFiveMetersBeforeRiskEvent() {
        val rule=AvoidanceDirectionEvaluator()
        val frame=depth(2500,1500,800)
        assertFalse(step(rule,1_000,frame,boxes=listOf(forwardBox)).directionReady)
        assertFalse(step(rule,1_250,frame,boxes=listOf(forwardBox)).directionReady)
        val early=step(rule,1_500,frame,boxes=listOf(forwardBox))
        assertEquals(AvoidanceDirection.LEFT,early.direction)
        assertTrue(early.directionReady)
        assertEquals(RiskLevel.CAUTION,early.guidanceLevel)
        assertEquals(0,early.forwardClassId)
        assertFalse(step(rule,1_750,frame,boxes=listOf(forwardBox)).directionReady)
        val caution=alert.copy(level=RiskLevel.CAUTION,distanceMeters=1.5f)
        assertFalse(step(rule,2_000,frame,caution,boxes=listOf(forwardBox)).attachDirectionToRisk)
    }

    @Test fun earlyDirectionRequiresDetectedForwardTargetAndStableSide() {
        val frame=depth(2500,1500,800)
        val noTarget=AvoidanceDirectionEvaluator()
        for (time in 1_000L..1_750L step 250L)
            assertFalse(step(noTarget,time,frame).directionReady)
        val intermittent=AvoidanceDirectionEvaluator()
        step(intermittent,1_000,frame,boxes=listOf(forwardBox))
        step(intermittent,1_250,frame)
        assertFalse(step(intermittent,1_500,frame,boxes=listOf(forwardBox)).directionReady)
        val sideBlocked=AvoidanceDirectionEvaluator()
        for (time in 1_000L..1_750L step 250L)
            assertFalse(step(sideBlocked,time,depth(800,1500,800),boxes=listOf(forwardBox)).directionReady)
    }

    @Test fun uncertainRgbCanGiveDirectionFromStablePoseAndCurrentDepth() {
        for (leftOpen in listOf(true,false)) {
            val rule=AvoidanceDirectionEvaluator()
            val frame=if (leftOpen) depth(2500,1500,800) else depth(800,1500,2500)
            for (time in listOf(1_000L,1_250L)) {
                assertFalse(step(rule,time,frame,visual=VisualMotionEvidence(timeMs=time),
                    orientation=facing,boxes=listOf(forwardBox)).directionReady)
            }
            val ready=step(rule,1_500,frame,visual=VisualMotionEvidence(timeMs=1_500),
                orientation=facing,boxes=listOf(forwardBox))
            assertEquals(if (leftOpen) AvoidanceDirection.LEFT else AvoidanceDirection.RIGHT,ready.direction)
            assertTrue(ready.directionReady)
            assertTrue(ready.reason.startsWith("stable_depth_pose_"))
            assertFalse(step(rule,1_550,frame,visual=VisualMotionEvidence(timeMs=1_550),
                orientation=facing,boxes=listOf(forwardBox)).directionReady)
        }
    }

    @Test fun uncertainRgbRequiresValidPoseAndFreshVisualSample() {
        for (pose in listOf(null,doubleArrayOf(0.0,0.0,0.0,0.0),doubleArrayOf(2.0,0.0,0.0,0.0))) {
            val rule=AvoidanceDirectionEvaluator()
            for (time in 1_000L..1_750L step 250L) {
                assertNull(step(rule,time,depth(2500,700,800),alert,
                    visual=VisualMotionEvidence(timeMs=time),orientation=pose).direction)
            }
        }
        val stale=step(AvoidanceDirectionEvaluator(),1_000,depth(2500,700,800),alert,
            visual=VisualMotionEvidence(timeMs=100),orientation=facing)
        assertEquals("visual_unavailable",stale.reason)
    }

    @Test fun gradualTurnRestartsDepthPoseDirectionEvidence() {
        val rule=AvoidanceDirectionEvaluator()
        for ((time,angle) in listOf(1_000L to 0.0,1_250L to 0.12,1_500L to 0.24,1_750L to 0.36)) {
            val pose=doubleArrayOf(kotlin.math.cos(angle/2),0.0,kotlin.math.sin(angle/2),0.0)
            assertNull(step(rule,time,depth(2500,700,800),if (time==1_000L) alert else null,
                visual=VisualMotionEvidence(timeMs=time),orientation=pose).direction)
        }
    }

    @Test fun changingSideDepthOrDirectionRequiresNewContinuousEvidence() {
        val rule=AvoidanceDirectionEvaluator()
        fun observe(time: Long, frame: ByteArray) = step(rule,time,frame,
            if (time==1_000L) alert else null,
            visual=VisualMotionEvidence(timeMs=time),orientation=facing)
        observe(1_000,depth(2500,700,800)); observe(1_250,depth(2500,700,800))
        assertNull(observe(1_500,depth(2100,700,800)).direction)
        assertNull(observe(1_750,depth(800,700,2500)).direction)
        assertNull(observe(2_000,depth(800,700,2500)).direction)
        assertEquals(AvoidanceDirection.RIGHT,observe(2_250,depth(800,700,2500)).direction)
    }

    @Test fun depthPoseFallbackCanRecallRecentSideButCannotAnnounceDeparture() {
        val rule=AvoidanceDirectionEvaluator()
        for (time in 1_000L..1_500L step 250L)
            step(rule,time,depth(2500,900,800),orientation=facing)
        val remembered=step(rule,1_750,depth(0,500,800),alert,
            visual=VisualMotionEvidence(timeMs=1_750),orientation=facing)
        assertEquals(AvoidanceDirection.LEFT,remembered.direction)
        assertTrue(remembered.remembered)
        assertEquals(3_000L,remembered.directionEvidenceExpiresMs)
        for (time in 2_000L..4_000L step 250L)
            assertFalse(step(rule,time,depth(2500,2500,2600),
                visual=VisualMotionEvidence(timeMs=time),orientation=facing).obstacleCleared)
    }

    @Test fun depthPoseFallbackStillRejectsBlockedOrInvalidCandidateSide() {
        for (frame in listOf(depth(800,700,800),depth(0,700,800),depth(1400,1300,1500))) {
            val rule=AvoidanceDirectionEvaluator()
            for (time in 1_000L..1_750L step 250L)
                assertNull(step(rule,time,frame,if (time==1_000L) alert else null,
                    visual=VisualMotionEvidence(timeMs=time),orientation=facing).direction)
        }
    }
}
