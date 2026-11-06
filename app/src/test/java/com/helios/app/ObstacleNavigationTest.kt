package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class ObstacleNavigationTest {
    private val width=160
    private val height=120
    private val moving=MotionSnapshot(state=MotionState.MOVING,stale=false,calibrated=true)
    private val still=moving.copy(state=MotionState.STILL)
    private val facing=doubleArrayOf(1.0,0.0,0.0,0.0)
    private fun frame(left: Int=3000,center: Int=1950,right: Int=1000) =
        ByteArray(width*height*2).also { bytes ->
            for (y in 0 until height) for (x in 0 until width) {
                val value=when { x < 54 -> left; x < 106 -> center; else -> right }
                val i=(y*width+x)*2; bytes[i]=value.toByte(); bytes[i+1]=(value shr 8).toByte()
            }
        }
    private fun box(meters: Float?,classId: Int=56) = TargetBox(classId,0.9f,58f,30f,102f,100f,
        DepthEstimate(meters,if (meters == null) 0f else 1f,"test"))
    private fun visual(now: Long,unknown: Boolean=false) = VisualMotionEvidence(
        if (unknown) VisualMotionKind.UNKNOWN else VisualMotionKind.STABLE,now,12,10)
    private fun step(nav: ObstacleNavigation,now: Long,meters: Float=1.95f,
        left: Int=3000,right: Int=1000,known: Boolean=true,motion: MotionSnapshot=moving,
        active: Boolean=true,fresh: Boolean=true,unknownVisual: Boolean=false,
        boxes: List<TargetBox>?=null,pose: DoubleArray?=facing,center: Int=(meters*1000).toInt()) =
        nav.evaluate(now,frame(left,center,right),width,height,1f,
            boxes ?: if (known) listOf(box(meters)) else emptyList(),motion,visual(now,unknownVisual),active,fresh,pose)
    private fun confirmed(nav: ObstacleNavigation,meters: Float=1.95f,known: Boolean=true): NavigationEvaluation {
        step(nav,1000,meters,known=known); step(nav,1250,meters,known=known)
        return step(nav,1500,meters,known=known)
    }

    @Test fun twoMeterKnownAndUnknownObstaclesBothStartCaution() {
        for (known in listOf(true,false)) {
            val result=confirmed(ObstacleNavigation(),known=known)
            assertEquals(RiskLevel.CAUTION,result.event?.level)
            assertEquals(if (known) 56 else -1,result.event?.classId)
            assertTrue(result.nearestMeters!! in 1.9f..2f)
        }
    }
    @Test fun unrelatedOrBadYoloBoxDoesNotBlockUnknownDepth() {
        val nav=ObstacleNavigation()
        val bad=listOf(box(null))
        step(nav,1000,boxes=bad);step(nav,1250,boxes=bad)
        assertEquals(-1,step(nav,1500,boxes=bad).event?.classId)
    }
    @Test fun oneEpisodeEscalatesAtOneMeterThenHalfMeter() {
        val nav=ObstacleNavigation();val first=confirmed(nav)
        step(nav,1750,1.5f)
        assertNull(step(nav,2000,0.95f).event?.takeIf { it.level==RiskLevel.WARNING })
        val warning=step(nav,2250,0.9f)
        assertEquals(RiskLevel.WARNING,warning.event?.level)
        assertEquals(first.episodeId,warning.episodeId)
        step(nav,2500,0.5f)
        val urgent=step(nav,2750,0.45f)
        assertEquals(RiskLevel.URGENT,urgent.event?.level)
        assertEquals(first.episodeId,urgent.episodeId)
    }
    @Test fun directionArrivesWhileStoppedForKnownAndUnknownTargets() {
        for (known in listOf(true,false)) for (leftOpen in listOf(true,false)) {
            val nav=ObstacleNavigation()
            for (now in 1000L..1250L step 250L)
                step(nav,now,known=known,left=if (leftOpen) 3000 else 1000,
                    right=if (leftOpen) 1000 else 3000,motion=still,unknownVisual=true)
            val result=step(nav,1500,known=known,left=if (leftOpen) 3000 else 1000,
                right=if (leftOpen) 1000 else 3000,motion=still,unknownVisual=true)
            assertEquals(if (leftOpen) AvoidanceDirection.LEFT else AvoidanceDirection.RIGHT,result.event?.avoidanceDirection)
            assertEquals(RiskEventKind.OBSTACLE,result.event?.kind)
            assertTrue(NavigationStateStore.valid(result.event!!.key,1500,result.event.avoidanceDirection))
        }
    }
    @Test fun repeatsHaveDifferentIntervalsAndDoNotRestartEveryFrame() {
        for ((meters,level,interval,start) in listOf(
            listOf(1.95f,RiskLevel.CAUTION,8000L,1500L),
            listOf(0.9f,RiskLevel.WARNING,4000L,1250L),
            listOf(0.45f,RiskLevel.URGENT,2000L,1250L))) {
            val nav=ObstacleNavigation()
            val events=mutableListOf<LocalRiskEvent>()
            for (now in 1000L..(start as Long)+(interval as Long) step 250L) {
                step(nav,now,meters as Float,left=3000,right=3000).event?.takeIf { it.kind==RiskEventKind.OBSTACLE }?.let { events.add(it) }
            }
            assertEquals(2,events.size)
            assertEquals(level,events[0].level)
            assertEquals(interval,events[1].observedMs-events[0].observedMs)
        }
    }
    @Test fun stoppedUserGetsOneDepartureAfterSustainedReliableCentralClearance() {
        val nav=ObstacleNavigation();confirmed(nav)
        val emitted=mutableListOf<LocalRiskEvent>()
        for (now in 1750L..4000L step 250L)
            step(nav,now,3f,left=3000,right=3000,known=false,motion=still).event?.let { emitted.add(it) }
        assertEquals(1,emitted.size)
        assertEquals(RiskEventKind.AREA_CLEARED,emitted.single().kind)
        assertEquals(2750L,emitted.single().observedMs)
    }
    @Test fun missingDetectionWithNearDepthIsNotDeparture() {
        val nav=ObstacleNavigation();confirmed(nav)
        for (now in 1750L..3500L step 250L)
            assertNotEquals(RiskEventKind.AREA_CLEARED,step(nav,now,known=false).event?.kind)
    }
    @Test fun invalidDepthPauseDisconnectTurnAndNearUnknownPreventDeparture() {
        for (mode in listOf("invalid","paused","stale","turn","near","unknown_box")) {
            val nav=ObstacleNavigation();confirmed(nav)
            for (now in 1750L..3500L step 250L) {
                val result=step(nav,now,3f,known=false,center=if (mode=="invalid") 0 else if (mode=="near") 500 else 3000,
                    left=3000,right=3000,active=mode!="paused",fresh=mode!="stale",
                    motion=if (mode=="turn") moving.copy(rotationRate=0.8) else still,
                    boxes=if (mode=="unknown_box") listOf(box(null)) else emptyList())
                assertNotEquals("mode=$mode",RiskEventKind.AREA_CLEARED,result.event?.kind)
            }
        }
    }
    @Test fun clearingDoesNotBridgeTurnOrInvalidFrame() {
        val nav=ObstacleNavigation();confirmed(nav)
        for (now in 1750L..2250L step 250L) step(nav,now,3f,known=false,left=3000,right=3000)
        step(nav,2500,3f,known=false,left=3000,right=3000,fresh=false)
        for (now in 2750L..3500L step 250L)
            assertNull(step(nav,now,3f,known=false,left=3000,right=3000).event)
        assertEquals(RiskEventKind.AREA_CLEARED,step(nav,3750,3f,known=false,left=3000,right=3000).event?.kind)
    }
    @Test fun pendingSpeechIsInvalidAfterEscalationDepartureOrDataLoss() {
        val nav=ObstacleNavigation();val first=confirmed(nav).event!!
        assertTrue(NavigationStateStore.valid(first.key,1500))
        step(nav,1750,1.5f);step(nav,2000,0.95f);step(nav,2250,0.9f)
        assertFalse(NavigationStateStore.valid(first.key,2250))
        val current=NavigationStateStore.current
        val key="risk:nav:${current.episodeId}:OBSTACLE:WARNING:20:live"
        assertTrue(NavigationStateStore.valid(key,2250))
        step(nav,2500,fresh=false)
        assertFalse(NavigationStateStore.valid(key,2500))
    }
    @Test fun expiredSceneAndChangedDirectionInvalidateQueuedDirection() {
        val key="risk:nav:1:DIRECTION:CAUTION:1:live"
        NavigationStateStore.update(NavigationValidity(1000,true,1,RiskLevel.CAUTION,AvoidanceDirection.LEFT))
        assertTrue(NavigationStateStore.valid(key,1100,AvoidanceDirection.LEFT))
        assertFalse(NavigationStateStore.valid(key,1100,AvoidanceDirection.RIGHT))
        assertFalse(NavigationStateStore.valid(key,1800,AvoidanceDirection.LEFT))
    }
    @Test fun shortDropoutKeepsEpisodeWithoutRepeatingCachedRisk() {
        val nav=ObstacleNavigation();val first=confirmed(nav)
        val lost=step(nav,1750,known=false,center=0)
        assertNull(lost.event)
        assertEquals(first.episodeId,lost.episodeId)
        val restored=step(nav,2000)
        assertEquals(first.episodeId,restored.episodeId)
        assertNotEquals(RiskEventKind.AREA_CLEARED,restored.event?.kind)
    }
    @Test fun recentStableSpaceSurvivesOcclusionButNewNearSpaceRevokesIt() {
        val nav=ObstacleNavigation()
        for (now in 1000L..2000L step 250L) step(nav,now,unknownVisual=true)
        step(nav,2250,1.5f,unknownVisual=true)
        step(nav,2500,0.9f,left=0,unknownVisual=true)
        val warning=step(nav,2750,0.85f,left=0,unknownVisual=true)
        val event=warning.event!!
        assertEquals(RiskLevel.WARNING,event.level)
        assertEquals(AvoidanceDirection.LEFT,event.avoidanceDirection)
        assertTrue(event.rememberedDirection)
        assertEquals(3450L,event.expiresMs)
        assertTrue(LocalRiskSpeech.text(event.level,event.classId,listOf("物体"),event.kind,
            event.avoidanceDirection,event.rememberedDirection).contains("空间暂不能确认"))
        step(nav,3000,0.85f,left=500,unknownVisual=true)
        assertFalse(NavigationStateStore.valid(event.key,3000,event.avoidanceDirection))
        assertNull(step(nav,3250,0.85f,left=0,unknownVisual=true).guidance.direction)
    }
    @Test fun expiredHistoricalSpaceAndTurnCannotProvideDirection() {
        for (turn in listOf(true,false)) {
            val nav=ObstacleNavigation()
            for (now in 1000L..2000L step 250L) step(nav,now,unknownVisual=true)
            val pose=if (turn) doubleArrayOf(kotlin.math.cos(0.1),0.0,kotlin.math.sin(0.1),0.0) else facing
            val time=if (turn) 2250L else 3750L
            assertNull(step(nav,time,0.9f,left=0,unknownVisual=true,pose=pose).guidance.direction)
        }
    }
    @Test fun gapThenFartherMeasurementDoesNotRepeatPreviousUrgentLevel() {
        val nav=ObstacleNavigation()
        step(nav,1000,0.4f)
        val urgent=step(nav,1250,0.4f).event!!
        step(nav,1500,0.4f)
        val uncertain=step(nav,3250,0.93f)
        assertNull(uncertain.event)
        assertEquals("confirming_zone_transition",uncertain.reason)
        assertFalse(NavigationStateStore.valid(urgent.key,3250))
        assertEquals(RiskLevel.WARNING,step(nav,3500,0.93f).event?.level)
    }
    @Test fun framesBetweenSamplesKeepDirectionDisplayWithoutDuplicatingEvents() {
        val nav=ObstacleNavigation()
        for (now in 1000L..2000L step 250L) step(nav,now)
        val cached=step(nav,2050)
        assertEquals("sample_interval",cached.reason)
        assertEquals(AvoidanceDirection.LEFT,cached.guidance.direction)
        assertNull(cached.event)
    }
}
