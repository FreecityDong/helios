package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class ImageHeightLayersTest {
    private val camera=HorizontalProjection(640,360,345f,319.5f,345f,179.5f)
    private fun frame(z: Float=3.5f)=ByteArray(640*360*2).also { bytes ->
        for (y in 0 until 360) for (x in 0 until 640) write(bytes,x,y,z)
    }
    private fun write(bytes: ByteArray,x: Int,y: Int,z: Float) {
        val raw=(z*1000).toInt();val i=(y*640+x)*2
        bytes[i]=raw.toByte();bytes[i+1]=(raw shr 8).toByte()
    }
    private fun floorFrame()=frame(0f).also { bytes ->
        for (y in 240 until 360) for (x in 0 until 640) {
            val z=1.4f*camera.fy/(y-camera.cy)
            if (z<=4f) write(bytes,x,y,z)
        }
    }
    private fun surface(bytes: ByteArray,left: Float,right: Float,z: Float,top: Int,bottom: Int) {
        for (y in top..bottom) for (x in camera.pixel(left,z).toInt().coerceAtLeast(0)..camera.pixel(right,z).toInt().coerceAtMost(639))
            write(bytes,x,y,z)
    }
    private fun navigate(bytes: ByteArray,boxes: List<TargetBox>): NavigationEvaluation {
        val nav=ObstacleNavigation(useCorridorPlanner=true,useImageHeightLayers=true)
        var result=NavigationEvaluation("initial")
        for (now in listOf(1000L,1250L,1500L)) {
            result=nav.evaluate(now,bytes,640,360,1f,boxes,
                MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false),
                VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,doubleArrayOf(1.0,0.0,0.0,0.0),camera)
            if (result.event!=null) return result
        }
        return result
    }
    @Test fun imageRegionsAreEqualThirdsWithoutBodyHeight() {
        assertEquals(0f,ImageHeightBand.HIGH.startFraction,0f)
        assertEquals(ImageHeightBand.HIGH.endFraction,ImageHeightBand.FRONT.startFraction,0f)
        assertEquals(ImageHeightBand.FRONT.endFraction,ImageHeightBand.GROUND.startFraction,0f)
        assertEquals(1f,ImageHeightBand.GROUND.endFraction,0f)
    }
    @Test fun sideBedWithBoxAcrossCenterDoesNotCreateForwardAlert() {
        val bytes=frame();surface(bytes,0.55f,0.90f,1f,80,340)
        val bed=TargetBox(59,0.95f,190f,60f,639f,350f,DepthEstimate(1f,1f,"whole box"))
        val scene=ImageHeightScene(bytes,640,360,1f,camera)
        for (band in ImageHeightBand.entries) assertNull(scene.known(bed,band))
        assertNull(navigate(bytes,listOf(bed)).event)
    }
    @Test fun actualForwardBedSurfaceRetainsCocoClass() {
        val bytes=frame();surface(bytes,0.15f,0.29f,1.2f,130,230)
        val bed=TargetBox(59,0.95f,355f,120f,410f,238f,DepthEstimate(1.2f,1f,"surface"))
        val result=navigate(bytes,listOf(bed))
        assertEquals(59,result.event?.classId)
        assertEquals(ImageHeightBand.FRONT,result.event?.heightBand)
    }
    @Test fun unclassifiedOverheadSurfaceCreatesHighBandAlert() {
        val bytes=frame();surface(bytes,-0.2f,0.2f,1.5f,10,100)
        val result=navigate(bytes,emptyList())
        assertEquals(-1,result.event?.classId)
        assertEquals(ImageHeightBand.HIGH,result.event?.heightBand)
        assertTrue(LocalRiskSpeech.text(RiskLevel.CAUTION,-1,emptyList(),heightBand=ImageHeightBand.HIGH).startsWith("上方"))
    }
    @Test fun measuredFloorIsNotAnObstacleAndMissingFloorIsUnknown() {
        val scene=ImageHeightScene(floorFrame(),640,360,1f,camera)
        assertNotNull(scene.ground)
        assertNull(scene.unknown(ImageHeightBand.GROUND,4f))
        assertNull(GroundDepthPlaneEstimator.estimate(frame(),640,360,1f,camera))
        assertNull(GroundDepthPlaneEstimator.estimate(floorFrame(),640,360,1f,camera.copy(fy=Float.NaN)))
    }
    @Test fun coherentGroundProtrusionIsDetectedAgainstMeasuredFloor() {
        val bytes=floorFrame();surface(bytes,-0.18f,0.18f,2f,310,354)
        val scene=ImageHeightScene(bytes,640,360,1f,camera)
        assertNotNull(scene.ground)
        assertNotNull(scene.unknown(ImageHeightBand.GROUND,2.2f))
        val result=navigate(bytes,emptyList())
        assertEquals(ImageHeightBand.GROUND,result.event?.heightBand)
    }
    @Test fun highSideBlockerRevokesOtherwiseClearSideCandidate() {
        val bytes=frame();surface(bytes,-0.2f,0.2f,2f,130,230)
        surface(bytes,0.35f,1.5f,1.5f,10,100)
        val ordinary=LocalCorridorPlanner().evaluate(bytes,640,360,1f,camera,2f)
        assertTrue(ordinary.right.accepted)
        val layered=LocalCorridorPlanner().evaluate(bytes,640,360,1f,camera,2f,ImageHeightScene(bytes,640,360,1f,camera))
        assertFalse(layered.right.availableForObservation)
        assertEquals(ImageHeightBand.HIGH,layered.right.blockedHeightBand)
    }
    @Test fun reliableCloseSideBedBlocksSweepWithoutBecomingFrontAlert() {
        val bytes=frame();surface(bytes,-0.2f,0.2f,2f,130,230)
        surface(bytes,0.55f,0.80f,0.85f,130,230)
        val scene=ImageHeightScene(bytes,640,360,1f,camera)
        val layered=LocalCorridorPlanner().evaluate(bytes,640,360,1f,camera,2f,scene)
        assertFalse(layered.right.availableForObservation)
        assertEquals("occupied_corridor",layered.right.reason)
    }
    @Test fun cameraIntrinsicsRetainVerticalCoordinatesAndNoBedTopBecomesFloor() {
        assertTrue(camera.verticalAvailable)
        assertEquals(0f,camera.verticalMeters(camera.cy,2f),0.001f)
        val bedTop=frame(0f)
        for (y in 240 until 360) for (x in 0 until 640) {
            val z=0.65f*camera.fy/(y-camera.cy)
            if (z<=4f) write(bedTop,x,y,z)
        }
        assertNull(GroundDepthPlaneEstimator.estimate(bedTop,640,360,1f,camera))
    }
    @Test fun missingLowerDepthCannotClaimGroundSupport() {
        val bytes=frame();surface(bytes,-0.2f,0.2f,2f,130,230)
        for (y in 240 until 360) for (x in 0 until 640) write(bytes,x,y,0f)
        val scene=ImageHeightScene(bytes,640,360,1f,camera)
        val result=LocalCorridorPlanner().evaluate(bytes,640,360,1f,camera,2f,scene)
        assertEquals("ground_unknown",result.right.groundAssessment)
        assertNull(scene.unknown(ImageHeightBand.GROUND,2.2f))
    }

    @Test fun middleWinsOverCloserUpperObstacleWithinSameSeverity() {
        val bytes=frame()
        surface(bytes,-0.2f,0.2f,0.7f,10,100)
        surface(bytes,-0.2f,0.2f,0.9f,130,230)
        val result=navigate(bytes,emptyList())
        assertEquals(ImageHeightBand.FRONT,result.event?.heightBand)
        assertEquals(0.9f,result.event!!.distanceMeters,0.01f)
    }
    @Test fun knownUpperClassCannotOverrideUnknownMiddleWithinSameSeverity() {
        val bytes=frame()
        surface(bytes,-0.2f,0.2f,0.8f,10,100)
        surface(bytes,-0.2f,0.2f,0.95f,130,230)
        val upper=TargetBox(59,0.95f,200f,0f,440f,110f,DepthEstimate(0.8f,1f,"upper"))
        val result=navigate(bytes,listOf(upper))
        assertEquals(ImageHeightBand.FRONT,result.event?.heightBand)
        assertEquals(-1,result.event?.classId)
    }
    @Test fun middleBeyondAlertRangeDoesNotSuppressUpperAlert() {
        val bytes=frame()
        surface(bytes,-0.2f,0.2f,1.5f,10,100)
        surface(bytes,-0.2f,0.2f,2.1f,130,230)
        assertEquals(ImageHeightBand.HIGH,navigate(bytes,emptyList()).event?.heightBand)
    }
    @Test fun nearSideObjectsInUpperAndMiddleNeverProduceSpeech() {
        val bytes=frame()
        surface(bytes,0.55f,0.85f,0.9f,10,230)
        surface(bytes,-0.85f,-0.55f,0.9f,10,230)
        val boxes=listOf(TargetBox(59,0.95f,0f,0f,639f,235f,DepthEstimate(0.9f,1f,"side")))
        assertNull(navigate(bytes,boxes).event)
    }
    @Test fun heightSamplingCannotLeakIntoAdjacentMiddleBand() {
        val bytes=frame();surface(bytes,-0.2f,0.2f,1f,112,119)
        assertNull(ImageHeightScene(bytes,640,360,1f,camera).unknown(ImageHeightBand.FRONT,2f))
    }
    @Test fun missingMetricIntrinsicsCannotFallBackToPixelSpeech() {
        val bytes=frame();surface(bytes,-0.2f,0.2f,0.8f,130,230)
        val nav=ObstacleNavigation(useCorridorPlanner=true,useImageHeightLayers=true)
        for (now in listOf(1000L,1250L,1500L)) {
            val result=nav.evaluate(now,bytes,640,360,1f,emptyList(),
                MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false),
                VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,
                doubleArrayOf(1.0,0.0,0.0,0.0),null)
            assertNull(result.event)
        }
    }

    @Test fun middleWinsOverMeasuredGroundHazard() {
        val bytes=floorFrame()
        surface(bytes,-0.18f,0.18f,2f,310,354)
        surface(bytes,-0.2f,0.2f,2f,130,230)
        assertEquals(ImageHeightBand.FRONT,navigate(bytes,emptyList()).event?.heightBand)
    }
    @Test fun liveMiddleReplacesUpperEpisodeAfterConfirmation() {
        val bytes=frame();surface(bytes,-0.2f,0.2f,1.8f,10,100)
        val nav=ObstacleNavigation(useCorridorPlanner=true,useImageHeightLayers=true)
        fun evaluate(now: Long)=nav.evaluate(now,bytes,640,360,1f,emptyList(),
            MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false),
            VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,
            doubleArrayOf(1.0,0.0,0.0,0.0),camera)
        evaluate(1000L);evaluate(1250L)
        assertEquals(ImageHeightBand.HIGH,evaluate(1500L).heightBand)
        surface(bytes,-0.2f,0.2f,1.7f,130,230)
        assertNull(evaluate(1750L).event)
        evaluate(2000L)
        val updated=evaluate(2250L)
        assertEquals(ImageHeightBand.FRONT,updated.heightBand)
        assertEquals(ImageHeightBand.FRONT, updated.event?.heightBand) // A confirmed new height is a new hazard, not a periodic repeat.
    }

    @Test fun warningAboveOverridesCautionInMiddle() {
        val bytes=frame();surface(bytes,-0.2f,0.2f,0.7f,10,100)
        surface(bytes,-0.2f,0.2f,1.8f,130,230)
        val event=navigate(bytes,emptyList()).event!!
        assertEquals(ImageHeightBand.HIGH,event.heightBand)
        assertEquals(RiskLevel.WARNING,event.level)
    }
    @Test fun urgentAboveOverridesWarningInMiddle() {
        val bytes=frame();surface(bytes,-0.2f,0.2f,0.4f,10,100)
        surface(bytes,-0.2f,0.2f,0.8f,130,230)
        val event=navigate(bytes,emptyList()).event!!
        assertEquals(ImageHeightBand.HIGH,event.heightBand)
        assertEquals(RiskLevel.URGENT,event.level)
    }
    @Test fun unknownUrgentSurfaceOverridesKnownWarningInSameLayer() {
        val bytes=frame();surface(bytes,-0.4f,-0.05f,0.45f,130,230)
        surface(bytes,0.10f,0.4f,0.6f,130,230)
        val box=TargetBox(59,0.95f,380f,125f,550f,235f,DepthEstimate(0.6f,1f,"known warning"))
        val event=navigate(bytes,listOf(box)).event!!
        assertEquals(-1,event.classId)
        assertEquals(RiskLevel.URGENT,event.level)
        assertEquals(0.45f,event.distanceMeters,0.01f)
    }
    @Test fun distancePriorityUsesConfiguredInclusiveBoundaries() {
        val config=NavigationConfig()
        assertEquals(3,config.distancePriority(0.5f))
        assertEquals(2,config.distancePriority(0.501f))
        assertEquals(2,config.distancePriority(1f))
        assertEquals(1,config.distancePriority(1.001f))
        assertEquals(1,config.distancePriority(2f))
        assertEquals(0,config.distancePriority(2.001f))
    }

    private fun ceilingFrame(clearance: Float)=frame().also { bytes ->
        for(y in 0 until 120) for(x in 0 until 640) {
            val z=clearance*camera.fy/(camera.cy-y)
            write(bytes,x,y,if(z<=6f) z else 0f)
        }
    }
    @Test fun supportedHighCeilingPlaneDoesNotBecomeAnObstacle() {
        val bytes=ceilingFrame(0.8f)
        val scene=ImageHeightScene(bytes,640,360,1f,camera)
        assertNotNull(scene.ceiling)
        assertNull(scene.unknown(ImageHeightBand.HIGH,2.2f))
        assertNull(navigate(bytes,emptyList()).event)
    }
    @Test fun recordedCeilingWithWardrobeAndWallDoesNotBlockForwardClearance() {
        val recordedCamera=HorizontalProjection(640,360,344.39072f,319.1861f,344.47714f,180.21915f)
        for(id in listOf("78452858","78742163","78749077","78768475")) {
            val bytes=java.util.zip.GZIPInputStream(javaClass.getResourceAsStream("/ceiling/$id.depth.gz")!!).use { it.readBytes() }
            val scene=ImageHeightScene(bytes,640,360,1f,recordedCamera)
            assertNotNull("$id ceiling",scene.ceiling)
            assertNull("$id upper hazard",scene.unknown(ImageHeightBand.HIGH,2.3f))
            assertTrue("$id forward clearance",TurnSpace.centralClear(scene,2.3f,true))
            val navigation=ObstacleNavigation(useCorridorPlanner=true,useImageHeightLayers=true)
            fun evaluate(now: Long,depth: ByteArray)=navigation.evaluate(now,depth,640,360,1f,emptyList(),
                MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false),
                VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,
                doubleArrayOf(1.0,0.0,0.0,0.0),recordedCamera)
            val blocked=frame();surface(blocked,-0.2f,0.2f,0.8f,130,230)
            for(now in 1000L..1600L step 200) evaluate(now,blocked)
            val events=(1800L..3000L step 200).mapNotNull { evaluate(it,bytes).event }
            assertEquals("$id clearance event",listOf(RiskEventKind.HEADING_CLEAR),events.map { it.kind })
        }
    }

    @Test fun lowCeilingAndHangingObjectRemainRisks() {
        val low=ceilingFrame(0.35f)
        assertNull(ImageHeightScene(low,640,360,1f,camera).ceiling)
        assertEquals(ImageHeightBand.HIGH,navigate(low,emptyList()).event?.heightBand)
        val hanging=ceilingFrame(0.8f);surface(hanging,-0.10f,0.10f,0.5f,20,95)
        assertEquals(ImageHeightBand.HIGH,navigate(hanging,emptyList()).event?.heightBand)
    }

    @Test fun measuredGroundPlaneIsExcludedEvenWhenItEntersMiddleBand() {
        val bytes=frame(0f)
        for(y in 0 until 360) for(x in 0 until 640) {
            // Tilted camera: floor enters the middle band and still has enough
            // measured depth span in the fitting region to establish a plane.
            val denominator=0.95f*(y-camera.cy)/camera.fy+0.31225f
            if(denominator>0f) {
                val z=1.4f/denominator
                if(z<=4f) write(bytes,x,y,z)
            }
        }
        val scene=ImageHeightScene(bytes,640,360,1f,camera)
        assertNotNull(scene.ground)
        val z=depthAt(bytes,640,320,230,1f)!!
        assertTrue(scene.floor(320,230,z))
        assertFalse(scene.accept(ImageHeightBand.FRONT,320,230,z))
    }

}
