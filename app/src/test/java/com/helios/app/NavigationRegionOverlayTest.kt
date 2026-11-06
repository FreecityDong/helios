package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class NavigationRegionOverlayTest {
    private val camera=HorizontalProjection(640,360,345f,319.5f)
    private fun depth(z: Float)=ByteArray(640*360*2).also { bytes ->
        val raw=(z*1000).toInt()
        for (i in bytes.indices step 2) { bytes[i]=raw.toByte();bytes[i+1]=(raw shr 8).toByte() }
    }
    private fun surface(bytes: ByteArray,left: Float,right: Float,z: Float) {
        val a=camera.pixel(left,z).toInt();val b=camera.pixel(right,z).toInt()
        for (y in 100..260) for (x in a..b) {
            val raw=(z*1000).toInt();val i=(y*640+x)*2
            bytes[i]=raw.toByte();bytes[i+1]=(raw shr 8).toByte()
        }
    }
    @Test fun pointSixMeterWidthUsesEachPointsOwnDepth() {
        assertEquals(0.6f,HorizontalProjection.HALF_FORWARD_METERS*2,0.0001f)
        for (z in listOf(0.5f,1f,2f)) {
            assertEquals(NavigationRegionOverlay.FORWARD,NavigationRegionOverlay.classify(camera.pixel(0.29f,z),z,camera))
            assertNotEquals(NavigationRegionOverlay.FORWARD,NavigationRegionOverlay.classify(camera.pixel(0.31f,z),z,camera))
        }
        assertEquals(NavigationRegionOverlay.FORWARD,NavigationRegionOverlay.classify(430f,0.8f,camera))
        assertEquals(NavigationRegionOverlay.SIDE_CLEAR,NavigationRegionOverlay.classify(430f,2f,camera))
    }
    @Test fun sideDistanceBoundaryAndUnknownAreDistinct() {
        for (x in listOf(-0.6f,0.6f)) {
            assertEquals(NavigationRegionOverlay.SIDE_TOO_CLOSE,NavigationRegionOverlay.classify(camera.pixel(x,1f),1f,camera))
            assertEquals(NavigationRegionOverlay.SIDE_CLEAR,NavigationRegionOverlay.classify(camera.pixel(x,1.01f),1.01f,camera))
        }
        assertEquals(NavigationRegionOverlay.UNKNOWN,NavigationRegionOverlay.classify(100f,null,camera))
        assertEquals(NavigationRegionOverlay.UNKNOWN,NavigationRegionOverlay.classify(100f,Float.NaN,camera))
        assertEquals(NavigationRegionOverlay.UNKNOWN,NavigationRegionOverlay.classify(100f,0.1f,camera))
    }
    @Test fun validFarDepthIsDisplayedAsMeasuredInsteadOfMissing() {
        assertEquals(NavigationRegionOverlay.FORWARD,NavigationRegionOverlay.classify(camera.cx,9f,camera))
        assertEquals(NavigationRegionOverlay.UNKNOWN,NavigationRegionOverlay.classify(camera.cx,12f,camera))
    }
    @Test fun geometryMismatchCannotMarkAnAreaAsClear() {
        for (cam in listOf(null,camera.copy(height=480),camera.copy(fx=Float.NaN))) {
            val mask=NavigationRegionOverlay.build(depth(2f),640,360,1f,cam)
            assertFalse(mask.geometryAvailable)
            assertTrue(mask.regions.all { it==NavigationRegionOverlay.UNKNOWN })
        }
    }
    @Test fun exactPixelsPreserveCloseSideAndMissingDepthWithoutHidingTheirNeighbours() {
        val bytes=depth(2f)
        for (y in 0..7) for (x in 0..7) {
            val i=(y*640+x)*2;bytes[i]=0xe8.toByte();bytes[i+1]=3 // 1m at left edge
        }
        bytes[16]=0;bytes[17]=0
        val mask=NavigationRegionOverlay.build(bytes,640,360,1f,camera)
        assertEquals(NavigationRegionOverlay.SIDE_TOO_CLOSE,mask.regions[0])
        assertEquals(NavigationRegionOverlay.UNKNOWN,mask.regions[8])
        assertEquals(NavigationRegionOverlay.FORWARD,mask.regions[mask.columns/2])
    }
    @Test fun newlyIncludedKnownAndUnknownForwardSurfaceIsDetected() {
        val bytes=depth(3f);surface(bytes,0.15f,0.29f,1.2f)
        val unknown=UnknownObstacleDetector.detect(bytes,640,360,1f,2.2f,camera)
        assertNotNull(unknown)
        val box=TargetBox(56,0.9f,camera.pixel(0.2f,1.2f),100f,camera.pixel(0.29f,1.2f),260f,
            DepthEstimate(1.2f,1f,"test"))
        val nav=ObstacleNavigation()
        var result=NavigationEvaluation("initial")
        for (now in listOf(1000L,1250L,1500L)) result=nav.evaluate(now,bytes,640,360,1f,listOf(box),
            MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false),
            VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,doubleArrayOf(1.0,0.0,0.0,0.0),camera)
        assertEquals(56,result.event?.classId)
        assertEquals(RiskLevel.CAUTION,result.event?.level)
        val outside=depth(3f);surface(outside,0.55f,0.70f,1.2f)
        assertNull(UnknownObstacleDetector.detect(outside,640,360,1f,2.2f,camera))
    }
    @Test fun newForwardEdgeNearSurfacePreventsFalseDeparture() {
        val nav=ObstacleNavigation()
        val motion=MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false)
        fun step(now: Long,bytes: ByteArray)=nav.evaluate(now,bytes,640,360,1f,emptyList(),motion,
            VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,doubleArrayOf(1.0,0.0,0.0,0.0),camera)
        for (now in listOf(1000L,1250L,1500L)) step(now,depth(1.9f))
        val bytes=depth(3f);surface(bytes,0.23f,0.25f,0.9f)
        for (now in 1750L..3500L step 250L)
            assertNotEquals(RiskEventKind.AREA_CLEARED,step(now,bytes).event?.kind)
    }
    @Test fun sideTextDoesNotCallTransparentDepthTraversable() {
        val mask=NavigationRegionOverlay.build(depth(3f),640,360,1f,camera)
        val left=CorridorEvidence(AvoidanceDirection.LEFT,false,"occupied_corridor",
            diagnosticsAvailable=true,coverageFailure="occupied",blockedHeightBand=ImageHeightBand.HIGH)
        val right=CorridorEvidence(AvoidanceDirection.RIGHT,false,"visible_opening_entry_unknown",
            observationEligible=true,diagnosticsAvailable=true,obstacleOpeningWidthMeters=1.2f,measuredSideOpeningWidthMeters=1.2f)
        val guidance=AvoidanceSnapshot(direction=AvoidanceDirection.RIGHT,corridorLeft=left,corridorRight=right)
        val result=NavigationRegionOverlay.withCorridorStatus(mask,guidance)
        assertTrue(result.sideStatus[0].contains("被挡（上方）"))
        assertTrue(result.sideStatus[1].contains("建议观察（脚下待确认）"))
        assertTrue(result.sideStatus[1].contains("已见1.20m / 需1.00m"))
        assertArrayEquals(mask.regions,result.regions)
    }
    @Test fun sideTextDistinguishesCoverageAndConfirmation() {
        val mask=NavigationRegionOverlay.build(depth(3f),640,360,1f,camera)
        fun text(e: CorridorEvidence)=NavigationRegionOverlay.withCorridorStatus(mask,
            AvoidanceSnapshot(corridorLeft=e)).sideStatus[0]
        assertTrue(text(CorridorEvidence(AvoidanceDirection.LEFT,false,"unknown",coverageFailure="out_of_view")).contains("视野不足"))
        assertTrue(text(CorridorEvidence(AvoidanceDirection.LEFT,false,"unknown",coverageFailure="invalid_depth")).contains("深度不足"))
        assertTrue(text(CorridorEvidence(AvoidanceDirection.LEFT,true,"observed_horizontal_corridor")).contains("候选待确认"))
        assertTrue(text(CorridorEvidence(AvoidanceDirection.LEFT,false,"no_current_obstacle")).contains("未评估通道"))
    }

}
