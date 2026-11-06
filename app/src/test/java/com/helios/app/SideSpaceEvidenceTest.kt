package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class SideSpaceEvidenceTest {
    private val camera=HorizontalProjection(160,120,90f,79.5f,90f,59.5f)
    private fun scene(middleMissing: Boolean=false,allMissing: Boolean=false,nearMiddle: Boolean=false,
        shifted: Boolean=false,obstacleMm: Int=700): ImageHeightScene {
        val bytes=ByteArray(160*120*2)
        for(y in 0 until 120) for(x in 0 until 160) {
            var raw=if(x<96) obstacleMm else 3500
            if(x>=100 && y in 40 until 80 && middleMissing) raw=0
            if(x>=96 && allMissing) raw=0
            if(x>=132 && y in 40 until 80 && nearMiddle) raw=500
            if(shifted && x>=96 && ((y<40 && x>=124) || (y>=80 && x<124))) raw=700
            val i=(y*160+x)*2;bytes[i]=raw.toByte();bytes[i+1]=(raw shr 8).toByte()
        }
        return ImageHeightScene(bytes,160,120,1f,camera)
    }
    private fun evidence(scene: ImageHeightScene)=SideSpaceAnalyzer.evaluate(scene,24,AvoidanceDirection.RIGHT,0.7f,4)

    @Test fun nearViewEdgeSupportsReobservationButNeverAConfirmedRoute() {
        val scene=scene();val result=LocalCorridorPlanner().evaluate(scene.bytes,160,120,1f,camera,0.7f,scene)
        assertFalse(result.right.accepted)
        assertTrue(result.right.toString(),result.right.sideSpace!!.eligible)
        assertTrue(result.right.sideSpace!!.extendsToViewEdge)
        assertTrue(result.right.sideSpace!!.visibleWidthMeters<1f)
        val evaluator=CorridorDirectionEvaluator()
        var snapshot=AvoidanceSnapshot()
        for(now in listOf(1000L,1200L,1400L)) snapshot=evaluator.evaluate(now,scene.bytes,160,120,1f,camera,0.7f,
            MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false),doubleArrayOf(1.0,0.0,0.0,0.0),scene)
        assertNull(snapshot.direction)
        assertEquals(AvoidanceDirection.RIGHT,snapshot.reobserveDirection)
        val turning=evaluator.evaluate(1500,scene.bytes,160,120,1f,camera,0.7f,
            MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false,rotationRate=0.8),
            doubleArrayOf(1.0,0.0,0.0,0.0),scene)
        assertNull(turning.reobserveDirection)
    }
    @Test fun isolatedMiddleSupportAllowsOnlyObservationOfMissingSpace() {
        val result=evidence(scene(middleMissing=true))
        assertTrue(result.toString(),result.eligible)
        assertEquals("missing_depth_reobserve",result.reason)
        assertTrue(result.middle!!.zeroRatio>0.8f)
        assertTrue(result.middle.largestUnknownMeters>0f)
    }
    @Test fun allMissingAndNearMiddleAreNotOpeningEvidence() {
        assertFalse(evidence(scene(allMissing=true)).eligible)
        assertFalse(evidence(scene(nearMiddle=true)).eligible)
        assertEquals("middle_near_surface",evidence(scene(nearMiddle=true)).reason)
    }
    @Test fun UpperAndLowerIntervalsMustOverlapInPosition() {
        assertFalse(evidence(scene(shifted=true)).eligible)
        assertEquals("upper_lower_openings_unaligned",evidence(scene(shifted=true)).reason)
    }
    @Test fun unconfirmedSideUsesSpatialUncertaintyAndFreshMatchingEvidence() {
        val text=LocalRiskSpeech.text(RiskLevel.CAUTION,-1,emptyList(),
            kind=RiskEventKind.REOBSERVE,direction=AvoidanceDirection.RIGHT)
        assertTrue(text.contains("减速留意"));assertTrue(text.contains("空间暂不能确认"));assertFalse(text.contains("停下"))
        assertFalse(text.contains("向右避让"));assertFalse(text.contains("可以通行"))
        val key="risk:nav:7:REOBSERVE:WARNING:1:live"
        NavigationStateStore.update(NavigationValidity(1000,true,7,RiskLevel.WARNING,reobserveDirection=AvoidanceDirection.RIGHT))
        assertTrue(NavigationStateStore.valid(key,1100,AvoidanceDirection.RIGHT))
        assertFalse(NavigationStateStore.valid(key,1800,AvoidanceDirection.RIGHT))
        assertFalse(NavigationStateStore.valid(key,1100,AvoidanceDirection.LEFT))
        NavigationStateStore.update(NavigationValidity(1100,true,7,RiskLevel.WARNING,direction=AvoidanceDirection.RIGHT))
        assertFalse(NavigationStateStore.valid(key,1100,AvoidanceDirection.RIGHT))
    }

    @Test fun firstReobservationRestartsProximityRepeatClock() {
        for((millimeters,interval) in listOf(700 to 4000L,450 to 2000L)) {
            val scene=scene(obstacleMm=millimeters)
            val nav=ObstacleNavigation(useCorridorPlanner=true,useImageHeightLayers=true)
            val events=mutableListOf<LocalRiskEvent>()
            for(now in 1000L..11000L step 200) {
                val result=nav.evaluate(now,scene.bytes,160,120,1f,emptyList(),
                    MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false),
                    VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,
                    doubleArrayOf(1.0,0.0,0.0,0.0),camera)
                result.event?.let { events.add(it) }
            }
            val scans=events.filter { it.kind==RiskEventKind.TURN_GUIDANCE && it.avoidanceDirection!=null }
            assertTrue(events.toString(),scans.size>=2)
            for(pair in scans.zipWithNext()) assertTrue(pair.toString(),pair.second.observedMs-pair.first.observedMs>=interval)
        }
    }
}
