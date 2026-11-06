package com.helios.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class TurnNavigationTest {
    private val camera=HorizontalProjection(160,120,90f,79.5f,90f,59.5f)
    private val still=MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false)
    private val nav=ObstacleNavigation(NavigationConfig(assumeMissingForwardClear=false),useCorridorPlanner=true,useImageHeightLayers=true)
    private fun frame()=ByteArray(160*120*2).also {fill(it,0..159,0..119,3500)}
    private fun fill(b: ByteArray,xs: IntRange,ys: IntRange,z: Int) {
        for(y in ys) for(x in xs) {val i=(y*160+x)*2;b[i]=z.toByte();b[i+1]=(z shr 8).toByte()}
    }
    private fun obstacle(z: Int=800)=frame().also {fill(it,60..99,40..79,z)}
    private fun step(now: Long,b: ByteArray,m: MotionSnapshot=still,angle: Double=0.0,fresh: Boolean=true,active: Boolean=true,
        boxes: List<TargetBox> = emptyList())=nav.evaluate(now,b,160,120,1f,boxes,m,
        VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),active,fresh,
        doubleArrayOf(cos(angle/2),0.0,sin(angle/2),0.0),camera)
    private fun confirm(b: ByteArray): NavigationEvaluation {step(1000,b);step(1200,b);return step(1600,b)}

    @Test fun outsidePointSixMeterEnvelopeDoesNotStartAnEpisodeOrPreventClear() {
        // At Z=1m these points lie at X=.45..+.65m: previously central, now lateral.
        val b=frame();fill(b,120..139,0..119,1000)
        val bed=TargetBox(59,0.95f,120f,0f,139f,119f,DepthEstimate(1f,1f,"side"))
        for(now in 1000L..2400L step 200) {
            val r=step(now,b,boxes=listOf(bed));assertNull(r.event)
            assertEquals(SideTurnStatus.UNKNOWN,r.guidance.turnRight!!.status)
        }
        nav.reset();confirm(obstacle())
        var clear: LocalRiskEvent?=null
        for(now in 1800L..3400L step 200) step(now,b,boxes=listOf(bed)).event
            ?.takeIf {it.kind==RiskEventKind.HEADING_CLEAR}?.let {clear=it}
        assertNotNull(clear)
    }

    @Test fun centralObstacleExtendingBeyondEnvelopeIsStillTracedToItsEdge() {
        val b=frame()
        // One continuous face starts centrally and extends to the right view boundary.
        fill(b,72..159,40..79,800)
        val result=confirm(b)
        assertNotNull(result.episodeId)
        assertEquals(SideTurnStatus.BLOCKED,result.guidance.turnRight!!.status)
        assertEquals(SideTurnStatus.POSSIBLE,result.guidance.turnLeft!!.status)
        assertEquals(AvoidanceDirection.LEFT,result.guidance.reobserveDirection)
    }

    @Test fun missingDepthAfterUrgentObstacleIsSilentWithoutClearingOrInheritingUrgency() {
        confirm(obstacle(450))
        val b=frame();fill(b,0..159,0..119,0)
        val reminders=mutableListOf<LocalRiskEvent>()
        for(now in 1800L..22000L step 200) {
            val r=step(now,b);assertNotEquals(RiskEventKind.HEADING_CLEAR,r.event?.kind)
            r.event?.let {reminders.add(it)}
            assertNull(r.guidance.reobserveDirection)
        }
        assertTrue(reminders.isEmpty())
        assertEquals(RiskLevel.CAUTION,NavigationStateStore.current.level)
        // A new measured urgent obstacle must immediately regain its risk cadence.
        step(22200,obstacle(450));val urgent=step(22400,obstacle(450))
        assertEquals(RiskLevel.URGENT,urgent.event?.level)
        assertNotEquals(-4,urgent.event?.classId)
    }

    @Test fun measuredFarDepthClearsWithoutAnUnconfirmedReminder() {
        confirm(obstacle())
        val events=mutableListOf<LocalRiskEvent>()
        for(now in 1800L..4000L step 200) step(now,frame()).event?.let {events.add(it)}
        assertEquals(1,events.size)
        assertEquals(RiskEventKind.HEADING_CLEAR,events.single().kind)
    }

    @Test fun validFarDepthThroughTenMetersCountsAsObservedAndCanClear() {
        confirm(obstacle());val b=frame();fill(b,0..159,0..119,9000)
        val events=mutableListOf<LocalRiskEvent>()
        for(now in 1800L..4000L step 200) step(now,b).event?.let {events.add(it)}
        assertEquals(1,events.size)
        assertEquals(RiskEventKind.HEADING_CLEAR,events.single().kind)
    }

    @Test fun uncertaintyVoiceCanBeEnabledButOnlyOncePerMissingPeriod() {
        val engine=TurnNavigation(NavigationConfig(announceDepthUncertainty=true,assumeMissingForwardClear=false))
        fun evaluate(now: Long,b: ByteArray)=engine.evaluate(now,b,160,120,1f,emptyList(),still,
            VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,
            doubleArrayOf(1.0,0.0,0.0,0.0),camera)
        for(now in 1000L..1600L step 200) evaluate(now,obstacle(450))
        val b=frame();fill(b,0..159,0..119,0)
        val events=mutableListOf<LocalRiskEvent>()
        for(now in 1800L..12000L step 200) evaluate(now,b).event?.let {events.add(it)}
        assertEquals(1,events.size)
        assertEquals(-4,events.single().classId)
        assertEquals(RiskLevel.CAUTION,events.single().level)
    }

    @Test fun missingMiddleDepthDoesNotSilenceMeasuredGroundObstacle() {
        val b=frame();fill(b,0..159,40..79,0);fill(b,60..99,80..119,450)
        step(1000,b);val result=step(1200,b)
        assertEquals(RiskLevel.URGENT,result.event?.level)
        assertEquals(ImageHeightBand.GROUND,result.event?.heightBand)
        assertNotEquals(-4,result.event?.classId)
        val text=LocalRiskSpeech.text(result.event!!.level,result.event.classId,emptyList(),
            result.event.kind,result.event.avoidanceDirection,heightBand=result.event.heightBand)
        assertTrue(text.contains("立即停下"))
        assertTrue(text.contains("下方"))
    }

    @Test fun outOfRangeDepthStaysUnknownButDoesNotRepeatAsAnObstacle() {
        confirm(obstacle());val b=frame();fill(b,0..159,0..119,12000)
        val events=mutableListOf<LocalRiskEvent>()
        for(now in 1800L..12000L step 200) step(now,b).event?.let {events.add(it)}
        assertTrue(events.isEmpty())
        assertFalse(TurnSpace.centralClear(ImageHeightScene(b,160,120,1f,camera),2.3f))
    }

    @Test fun fieldTestAssumptionAllowsMissingDepthOnlyWhenNoMeasuredNearObstacleRemains() {
        val engine=TurnNavigation(NavigationConfig(assumeMissingForwardClear=true))
        fun evaluate(now: Long,b: ByteArray,fresh: Boolean=true)=engine.evaluate(now,b,160,120,1f,emptyList(),still,
            VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,fresh,
            doubleArrayOf(1.0,0.0,0.0,0.0),camera)
        for(now in 1000L..1600L step 200) evaluate(now,obstacle(450))
        val b=frame();fill(b,0..159,0..119,0)
        // Even a mostly missing image cannot clear a measured near ground patch.
        fill(b,60..99,80..119,450)
        for(now in 1800L..3400L step 200) assertNotEquals(RiskEventKind.HEADING_CLEAR,evaluate(now,b).event?.kind)
        fill(b,0..159,0..119,0)
        for(now in 3600L..5200L step 200) assertNotEquals(RiskEventKind.HEADING_CLEAR,evaluate(now,b,false).event?.kind)
        val events=mutableListOf<LocalRiskEvent>()
        for(now in 5400L..7200L step 200) evaluate(now,b).event?.let {events.add(it)}
        assertEquals(1,events.count {it.kind==RiskEventKind.HEADING_CLEAR})
        assertEquals(-5,events.single {it.kind==RiskEventKind.HEADING_CLEAR}.classId)
    }

    @Test fun clearDuringSlowRotationIsPromptAndIgnoresOneIsolatedReturn() {
        confirm(obstacle())
        val b=frame();fill(b,112..112,60..60,400)
        val m=still.copy(rotationRate=0.45)
        var event: LocalRiskEvent?=null;var clearedAt=0L
        for(now in 1800L..3000L step 200) {
            val r=step(now,b,m,angle=(now-1800)*0.00045)
            r.event?.takeIf {it.kind==RiskEventKind.HEADING_CLEAR}?.let {event=it;clearedAt=now}
        }
        assertNotNull(event)
        assertTrue(clearedAt in 2800L..3000L)
    }

    @Test fun briefEmptyViewAndFastTurnCannotTriggerClear() {
        confirm(obstacle());val b=frame()
        for(now in 1800L..2200L step 200) assertNotEquals(RiskEventKind.HEADING_CLEAR,step(now,b).event?.kind)
        assertTrue(step(2400,obstacle()).heightLayersStatus.contains("clear_blocked_near"))
        for(now in 2600L..4000L step 200) {
            val r=step(now,b,still.copy(rotationRate=0.7))
            assertNotEquals(RiskEventKind.HEADING_CLEAR,r.event?.kind)
            assertTrue(r.heightLayersStatus.contains("turning_too_fast"))
        }
    }

    @Test fun blockedLeftPrioritizesRightWithoutCertifyingAWidth() {
        val b=obstacle();fill(b,0..47,0..119,700)
        val result=confirm(b)
        assertEquals("turn_priority",result.guidance.geometryMode)
        assertEquals(AvoidanceDirection.RIGHT,result.guidance.reobserveDirection)
        assertEquals(SideTurnStatus.BLOCKED,result.guidance.turnLeft!!.status)
        assertNull(result.guidance.direction)
    }
    @Test fun missingMiddleWithUpperLowerSupportIsObservationOnly() {
        val b=obstacle();fill(b,0..47,0..119,700);fill(b,104..159,40..79,0)
        val result=confirm(b)
        assertEquals(AvoidanceDirection.RIGHT,result.guidance.reobserveDirection)
        assertEquals(SideTurnStatus.POSSIBLE,result.guidance.turnRight!!.status)
        assertTrue(result.guidance.turnRight.coverage[1]<0.1f)
        assertNull(result.guidance.direction)
    }
    @Test fun bothBlockedOrMissingNeverInstructInPlaceTurning() {
        for(missing in listOf(false,true)) {
            nav.reset();val b=obstacle();fill(b,0..51,0..119,if(missing) 0 else 800);fill(b,104..159,0..119,if(missing) 0 else 800)
            step(1000,b);val result=step(1200,b)
            assertNull(result.event!!.avoidanceDirection)
            assertTrue(LocalRiskSpeech.text(result.event.level,result.event.classId,emptyList(),result.event.kind).contains("空间暂不能确认"))
            assertNull(step(1600,b).guidance.reobserveDirection)
        }
    }
    @Test fun closeSideBedNeverStartsAnEpisode() {
        val b=frame();fill(b,132..159,0..119,1000)
        val bed=TargetBox(59,0.95f,0f,0f,159f,119f,DepthEstimate(1f,1f,"side"))
        for(now in 1000L..2400L step 200) assertNull(step(now,b,boxes=listOf(bed)).event)
    }
    @Test fun slowRotationContinuesDetectionAndClearsAfterAllThirdsAreObserved() {
        confirm(obstacle())
        val b=frame();var clear: LocalRiskEvent?=null;var clearAngle=0.0
        for(now in 1800L..3000L step 200) {
            val angle=if(clear==null) (now-1800)*0.0002 else clearAngle
            val result=step(now,b,still.copy(rotationRate=if(clear==null) 0.2 else 0.0),angle=angle)
            result.event?.takeIf {it.kind==RiskEventKind.HEADING_CLEAR}?.let {clear=it;clearAngle=angle}
        }
        assertNotNull(clear)
        assertTrue(NavigationStateStore.valid(clear!!.key,3000))
        assertNull(step(3200,b,angle=clearAngle).event)
        assertTrue(NavigationStateStore.valid(clear.key,3200))
    }
    @Test fun missingGroundOrUpperBandCannotClearEvenWhenBoxDisappears() {
        for(band in listOf(0..39,80..119)) {
            nav.reset();confirm(obstacle());val b=frame();fill(b,0..159,band,0)
            for(now in 1800L..4200L step 200) assertNotEquals(RiskEventKind.HEADING_CLEAR,step(now,b).event?.kind)
        }
    }
    @Test fun newGroundAndHangingObstaclesKeepTheTurnEpisode() {
        for(band in listOf(0..39,80..119)) {
            nav.reset();val first=confirm(obstacle());val b=frame();fill(b,60..99,band,450)
            var urgent: LocalRiskEvent?=null
            for(now in 1800L..3000L step 200) {
                val r=step(now,b);assertNotEquals(RiskEventKind.HEADING_CLEAR,r.event?.kind)
                r.event?.takeIf {it.level==RiskLevel.URGENT}?.let {urgent=it}
                assertEquals(first.episodeId,r.episodeId)
            }
            assertNotNull(urgent)
        }
    }
    @Test fun fastTurnAndSensorLossRevokeQueuedOldHeading() {
        val b=obstacle();fill(b,0..47,0..119,700);confirm(b)
        for(now in 1800L..3000L step 200) step(now,b)
        nav.requestSpeechRefresh()
        val event=step(3200,b).event!!
        assertEquals(AvoidanceDirection.RIGHT,event.avoidanceDirection)
        assertTrue(NavigationStateStore.valid(event.key,3200,event.avoidanceDirection,true))
        step(3400,b,still.copy(rotationRate=1.6),angle=0.4)
        assertFalse(NavigationStateStore.valid(event.key,3400,event.avoidanceDirection,true))
        for(now in 3600L..4800L step 200) assertNotEquals(RiskEventKind.HEADING_CLEAR,step(now,frame(),still.copy(rotationRate=1.6)).event?.kind)
        step(5000,b,fresh=false)
        assertFalse(NavigationStateStore.current.available)
    }
    @Test fun measuredNormalFloorCountsAsClearEvidence() {
        val c=HorizontalProjection(640,360,345f,319.5f,200f,179.5f)
        val bytes=ByteArray(640*360*2)
        for(y in 0 until 360) for(x in 0 until 640) {
            val z=if(y>=240) 1.4f*c.fy/(y-c.cy) else 3.5f
            val raw=(z*1000).toInt();val i=(y*640+x)*2;bytes[i]=raw.toByte();bytes[i+1]=(raw shr 8).toByte()
        }
        val scene=ImageHeightScene(bytes,640,360,1f,c)
        assertNotNull(scene.ground)
        assertTrue(TurnSpace.centralClear(scene,2.3f))
    }

    @Test fun nearbyReturnOutsideFarProjectionStillBlocksCentralClear() {
        val b=frame();fill(b,112..119,42..78,400)
        // Outside the far-plane ROI, but within the actual metre at 0.4m.
        assertTrue(camera.lateralMeters(115f,2.3f)>0.5f)
        assertFalse(TurnSpace.centralClear(ImageHeightScene(b,160,120,1f,camera),2.3f))
    }
    @Test fun realRightOpeningRemainsAnObservationAndLeftCabinetIsNotAnOpening() {
        val bytes=javaClass.getResourceAsStream("/corridor/right-open-0628.bin")!!.readBytes()
        val c=HorizontalProjection(640,360,344.39072f,319.1861f,344.47714f,180.21915f)
        val scene=ImageHeightScene(bytes,640,360,1f,c)
        val right=TurnSpace.side(scene,AvoidanceDirection.RIGHT,1.338f)
        val left=TurnSpace.side(scene,AvoidanceDirection.LEFT,1.338f)
        assertEquals(right.toString(),SideTurnStatus.POSSIBLE,right.status)
        assertEquals(left.toString(),SideTurnStatus.BLOCKED,left.status)
    }
    @Test fun ordinarySmallDepthHolesAreToleratedButConcentratedHolesAreUnknown() {
        val b=frame();fill(b,80..83,0..119,0)
        assertTrue(TurnSpace.centralClear(ImageHeightScene(b,160,120,1f,camera),2.3f))
        fill(b,84..87,0..119,0)
        assertFalse(TurnSpace.centralClear(ImageHeightScene(b,160,120,1f,camera),2.3f))
    }
    @Test fun aNewHeightIsAnnouncedOnceWithinTheSameEpisode() {
        val b=frame();fill(b,0..159,0..119,3500);fill(b,60..99,0..39,1800)
        var events=0;var id: Long?=null
        for(now in 1000L..3000L step 200) {
            if(now>=1600) {fill(b,60..99,0..39,3500);fill(b,60..99,40..79,1800)}
            val r=step(now,b);if(r.event!=null) events++
            if(r.episodeId!=null) {if(id==null) id=r.episodeId else assertEquals(id,r.episodeId)}
        }
        assertEquals(2,events)
    }
    @Test fun chosenDirectionIsImmediatelyRevokedWhenNewNearSpaceAppears() {
        val b=obstacle();fill(b,0..47,0..119,700);confirm(b)
        for(now in 1800L..3000L step 200) step(now,b)
        nav.requestSpeechRefresh()
        val queued=step(3200,b).event!!
        assertEquals(AvoidanceDirection.RIGHT,queued.avoidanceDirection)
        fill(b,100..159,0..119,800)
        val blocked=step(3400,b)
        assertNull(blocked.guidance.reobserveDirection)
        assertEquals(RiskEventKind.TURN_GUIDANCE,blocked.event?.kind)
        assertNull(blocked.event!!.avoidanceDirection)
        assertFalse(NavigationStateStore.valid(queued.key,3400,queued.avoidanceDirection,true))
    }
    @Test fun headingClearAndDirectionRevocationWaitForTheCoreSentence() {
        val arbiter=SpeechArbiter()
        val right=SpeechRequest("right","risk:nav:1:TURN_GUIDANCE:WARNING:1:h0","right",
            SpeechPriority.WARNING_SAFETY,1000,2500,riskMeters=0.8f,riskDirection=AvoidanceDirection.RIGHT)
        assertEquals(SpeechDecision.START,arbiter.submit(right,1000))
        val neutral=right.copy(id="neutral",key="risk:nav:1:TURN_GUIDANCE:WARNING:2:h0",riskDirection=null)
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(neutral,1100))
        val clear=right.copy(id="clear",key="risk:nav:1:HEADING_CLEAR:WARNING:3:h0",priority=SpeechPriority.ORDINARY,riskDirection=null)
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(clear,1200))
        assertEquals("right",arbiter.active!!.id)
        assertEquals(SpeechDecision.START,arbiter.submit(right.copy(id="fall",key="fall",priority=SpeechPriority.FALL),1300))
        assertEquals(SpeechDecision.QUEUED,arbiter.submit(clear.copy(id="clear2",key="risk:nav:2:HEADING_CLEAR:WARNING:4:h0"),1400))
    }
    @Test fun defaultMissingDepthNeverIssuesPermissionToMoveForward() {
        val engine=TurnNavigation(NavigationConfig())
        fun evaluate(now:Long,bytes:ByteArray)=engine.evaluate(now,bytes,160,120,1f,emptyList(),still,
            VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,null,camera)
        for(now in 1000L..1600L step 200) evaluate(now,obstacle())
        repeat(20) {assertNotEquals(RiskEventKind.HEADING_CLEAR,evaluate(1800L+it*200,ByteArray(160*120*2)).event?.kind)}
    }
    @Test fun currentSpaceIsAvailableWithoutAnyUserTurnAndDoesNotBecomeATurnTask() {
        val result=confirm(obstacle())
        assertEquals(SpatialHint.BOTH,result.guidance.spaceHint)
        assertEquals(TurnState.BLOCKED,result.guidance.turnState)
        assertEquals(SpatialHint.BOTH,result.event?.spaceHint)
        assertTrue(result.event!!.confirmationMs!!>=150)
    }
    @Test fun walkingAfterStandingStillDoesNotRestartTheSameUnchangedAlert() {
        val bytes=obstacle();confirm(bytes)
        assertNull(step(1800,bytes,still.copy(state=MotionState.MOVING)).event)
        assertNull(step(2000,bytes).event)
        assertNull(step(2200,bytes,still.copy(state=MotionState.MOVING)).event)
    }
    @Test fun dialogueRefreshUsesLatestMeasuredObstacleWithoutWaitingForRepeatClock() {
        val bytes=obstacle();confirm(bytes)
        nav.requestSpeechRefresh()
        val refreshed=step(1800,bytes)
        assertNotNull(refreshed.event)
        assertEquals(.8f,refreshed.event!!.distanceMeters,.001f)
        assertEquals(SpatialHint.BOTH,refreshed.event.spaceHint)
        assertNull(step(2000,bytes).event)
    }
    @Test fun stationaryClearanceRequiresOneFullSecondAndDoesNotRepeat() {
        confirm(obstacle())
        for(now in 1800L..2600L step 200) {
            assertNotEquals(RiskEventKind.HEADING_CLEAR,step(now,frame()).event?.kind)
            assertEquals(false,NavigationStateStore.current.forwardClear)
        }
        val clear=step(2800,frame()).event!!
        assertEquals(RiskEventKind.HEADING_CLEAR,clear.kind)
        assertEquals(true,NavigationStateStore.current.forwardClear)
        for(now in 3000L..4800L step 200) {
            assertNull(step(now,frame()).event)
            assertEquals(true,NavigationStateStore.current.forwardClear)
        }
    }
    @Test fun missingDepthDuringTheSecondRestartsClearanceConfirmation() {
        confirm(obstacle())
        for(now in 1800L..2400L step 200) assertNotEquals(RiskEventKind.HEADING_CLEAR,step(now,frame()).event?.kind)
        assertNotEquals(RiskEventKind.HEADING_CLEAR,step(2600,ByteArray(160*120*2)).event?.kind)
        for(now in 2800L..3600L step 200) assertNotEquals(RiskEventKind.HEADING_CLEAR,step(now,frame()).event?.kind)
        assertEquals(RiskEventKind.HEADING_CLEAR,step(3800,frame()).event?.kind)
    }
    @Test fun aSurfaceInsideReleaseDistanceRevokesDelayedClearanceEvenAboveAlertDistance() {
        confirm(obstacle())
        for(now in 1800L..2800L step 200) step(now,frame())
        assertEquals(true,NavigationStateStore.current.forwardClear)
        val surface=obstacle(2200)
        val box=TargetBox(0,.95f,70f,40f,90f,79f,DepthEstimate(2.2f,1f,"test"))
        step(3000,surface,boxes=listOf(box))
        assertEquals(false,NavigationStateStore.current.forwardClear)
    }
    @Test fun initialOpenSceneCanBeConfirmedWithoutAHistoricalObstacle() {
        for(now in 1000L..1800L step 200) {
            assertNull(step(now,frame()).event)
            assertEquals(false,NavigationStateStore.current.forwardClear)
        }
        assertNull(step(2000,frame()).event)
        assertEquals(true,NavigationStateStore.current.forwardClear)
        assertNull(NavigationStateStore.current.clearedId)
        assertTrue(MovingClearanceReminder().take(2000,NavigationStateStore.current,true,true,6000))
    }
    @Test fun initiallyMissingDepthCannotBecomeWalkingPermissionWithoutMeasuredCoverage() {
        for(now in 1000L..4000L step 200) step(now,ByteArray(160*120*2))
        assertEquals(false,NavigationStateStore.current.forwardClear)
        assertFalse(MovingClearanceReminder().take(4000,NavigationStateStore.current,true,true,6000))
    }
}
