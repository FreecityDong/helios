package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class LocalCorridorPlannerTest {
    private val camera=HorizontalProjection(640,360,345f,319.5f)
    private val motion=MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false)
    private val pose=doubleArrayOf(1.0,0.0,0.0,0.0)
    private data class Surface(val left: Float,val right: Float,val z: Float)
    private fun scene(vararg extra: Surface,invalid: Boolean=false,obstacleZ: Float=2f) = ByteArray(640*360*2).also { bytes ->
        val surfaces=listOf(Surface(-0.2f,0.2f,obstacleZ))+extra
        for (y in 0 until 360) for (x in 0 until 640) {
            val z=surfaces.filter { camera.lateralMeters(x.toFloat(),it.z) in it.left..it.right }
                .minOfOrNull { it.z } ?: 3.5f
            val raw=if (invalid) 0 else (z*1000).toInt(); val i=(y*640+x)*2
            bytes[i]=raw.toByte();bytes[i+1]=(raw shr 8).toByte()
        }
    }
    private fun plan(bytes: ByteArray,cameraOverride: HorizontalProjection?=camera,z: Float=2f) =
        LocalCorridorPlanner().evaluate(bytes,640,360,1f,cameraOverride,z)
    @Test fun bothVisibleSidesCanOfferObservation() {
        val result=plan(scene())
        assertTrue(result.left.toString(),result.left.accepted)
        assertTrue(result.right.toString(),result.right.accepted)
        assertEquals(1f,result.left.requiredWidthMeters,0.001f)
        assertEquals(1f,result.left.coverage,0.001f)
    }
    @Test fun closerLeftObstacleSelectsRightAndMirrorSelectsLeft() {
        val left=plan(scene(Surface(-1.5f,-0.35f,1.5f)))
        assertFalse(left.left.accepted);assertTrue(left.right.accepted)
        assertEquals(AvoidanceDirection.RIGHT,left.best(null))
        val right=plan(scene(Surface(0.35f,1.5f,1.5f)))
        assertEquals(AvoidanceDirection.LEFT,right.best(null))
    }
    @Test fun narrowGapAndWideWallRejectBothSides() {
        assertNull(plan(scene(Surface(-1.7f,0.55f,2f),Surface(1.25f,1.7f,2f))).best(null))
        assertNull(plan(scene(Surface(-1.7f,1.7f,2f))).best(null))
    }
    @Test fun unknownIntrinsicsDepthAndNearBodyCoverageDoNotInventDirection() {
        assertNull(plan(scene(),null).best(null))
        assertNull(plan(scene(invalid=true)).best(null))
        assertEquals("near_body_coverage_unknown",plan(scene(),z=0.5f).left.reason)
        assertNull(plan(scene(),camera.copy(height=480)).best(null))
    }
    @Test fun previousSideIsStableWhenBothSidesAreComparable() {
        val result=plan(scene())
        assertEquals(AvoidanceDirection.LEFT,result.best(AvoidanceDirection.LEFT))
        assertEquals(AvoidanceDirection.RIGHT,result.best(AvoidanceDirection.RIGHT))
    }
    @Test fun currentContradictionImmediatelyRevokesConfirmedDirection() {
        val evaluator=CorridorDirectionEvaluator()
        var result=AvoidanceSnapshot()
        for (now in listOf(1000L,1200L,1400L)) result=evaluator.evaluate(now,scene(),640,360,1f,camera,2f,motion,pose)
        assertNotNull(result.direction)
        val old=result.direction!!
        val blocked=if (old==AvoidanceDirection.LEFT) scene(Surface(-1.5f,-0.35f,1.5f)) else scene(Surface(0.35f,1.5f,1.5f))
        result=evaluator.evaluate(1600,blocked,640,360,1f,camera,2f,motion,pose)
        assertNull(result.direction)
        assertFalse(if (old==AvoidanceDirection.LEFT) result.corridorLeft!!.accepted else result.corridorRight!!.accepted)
    }
    @Test fun turnGapAndInvalidPoseResetPositiveConfirmation() {
        for (case in 0..2) {
            val evaluator=CorridorDirectionEvaluator()
            for (now in listOf(1000L,1200L,1400L)) evaluator.evaluate(now,scene(),640,360,1f,camera,2f,motion,pose)
            val result=evaluator.evaluate(if (case==0) 2400 else 1600,scene(),640,360,1f,camera,2f,
                if (case==1) motion.copy(rotationRate=1.0) else motion,if (case==2) null else pose)
            assertNull(result.direction)
        }
    }
    @Test fun unknownStripeCannotBeIgnoredByAverageCoverage() {
        val bytes=scene()
        for (y in 0 until 360) for (x in 390..415) {
            val i=(y*640+x)*2;bytes[i]=0;bytes[i+1]=0
        }
        val result=plan(bytes)
        assertFalse(result.right.accepted)
    }
    @Test fun configuredBodyEnvelopeCanRejectAnOtherwiseOpenCandidate() {
        val result=LocalCorridorPlanner(CorridorConfig(bodyWidthMeters=1.0f)).evaluate(scene(),640,360,1f,camera,2f)
        assertFalse(result.left.accepted);assertFalse(result.right.accepted)
        assertEquals(1.4f,result.left.requiredWidthMeters,0.001f)
    }
    @Test fun sparseVerticalNearReturnDoesNotBecomeFreeBackground() {
        val bytes=scene()
        // A bar visible on only one sampled row must not disappear in a percentile.
        for (x in 440..620) {
            val i=(130*640+x)*2;bytes[i]=0xdc.toByte();bytes[i+1]=5 // 1.5m
        }
        assertFalse(plan(bytes).right.accepted)
    }
    @Test fun visibleSideOpeningCanSupportObservationWithUnknownEntry() {
        val result=plan(scene(Surface(-1.5f,-0.35f,1.3f),obstacleZ=1.5f),z=1.5f)
        assertFalse(result.right.accepted)
        assertTrue(result.right.toString(),result.right.observationEligible)
        assertEquals(AvoidanceDirection.RIGHT,result.best(null))
        assertTrue(result.right.obstacleOpeningWidthMeters>=result.right.requiredWidthMeters)
        assertTrue(result.right.coverage<1f)
    }
    @Test fun unknownOpeningOrOccupiedSideCannotUseObservationTier() {
        val invalid=plan(scene(invalid=true,obstacleZ=1.5f),z=1.5f)
        assertFalse(invalid.right.observationEligible)
        val wall=plan(scene(Surface(0.35f,1.5f,1.3f),obstacleZ=1.5f),z=1.5f)
        assertFalse(wall.right.observationEligible)
        assertNull(wall.best(AvoidanceDirection.RIGHT)?.takeIf { it==AvoidanceDirection.RIGHT })
    }
    @Test fun newPlannerIntegratesObservationSpeechAndRechecksLiveDirection() {
        val nav=ObstacleNavigation(useCorridorPlanner=true)
        var result=NavigationEvaluation("initial")
        val target=TargetBox(56,0.9f,camera.pixel(-0.2f,2f),90f,camera.pixel(0.2f,2f),260f,DepthEstimate(2f,1f,"test"))
        for (now in listOf(1000L,1200L,1400L)) result=nav.evaluate(now,scene(),640,360,1f,listOf(target),motion,
            VisualMotionEvidence(VisualMotionKind.UNKNOWN,now,5,1),true,true,pose,camera)
        val event=result.event!!
        assertNotNull(event.avoidanceDirection);assertTrue(event.observationOnly)
        val text=LocalRiskSpeech.text(event.level,event.classId,listOf("物体"),event.kind,event.avoidanceDirection,
            event.rememberedDirection,event.observationOnly)
        assertTrue(text.contains("可见空间"));assertFalse(text.contains("向右避让"));assertFalse(text.contains("转动"))
        val blocked=scene(Surface(-1.7f,1.7f,2f))
        nav.evaluate(1600,blocked,640,360,1f,listOf(target),motion,
            VisualMotionEvidence(VisualMotionKind.STABLE,1600,12,10),true,true,pose,camera)
        assertFalse(NavigationStateStore.valid(event.key,1600,event.avoidanceDirection))
    }
    @Test fun diagnosticsSeparateFovLossFromReliableFreeDepth() {
        val result=plan(scene(obstacleZ=1.5f),z=1.5f).right
        assertTrue(result.diagnosticsAvailable)
        assertEquals("out_of_view",result.coverageFailure)
        assertTrue(result.fieldOfViewCoverage<1f)
        assertEquals(1f,result.reliableDepthWithinView,0.001f)
        assertTrue(result.outOfViewFraction>0f)
        assertEquals(0f,result.invalidDepthFraction,0.001f)
        assertEquals(camera.lateralMeters(639f,1.5f)-HorizontalProjection.HALF_FORWARD_METERS,
            result.sideVisibleWidthAtObstacleMeters,0.001f)
        assertFalse(result.accepted)
        assertTrue(result.observationEligible)
        assertEquals(1f,result.outOfViewFraction+result.invalidDepthFraction+
            result.occludedFraction+result.observedFreeFraction,0.001f)
    }
    @Test fun invalidDepthHasItsOwnCauseAndDoesNotBecomeFree() {
        val result=plan(scene(invalid=true)).right
        assertEquals("invalid_depth",result.coverageFailure)
        assertEquals(0f,result.reliableDepthWithinView,0f)
        assertEquals(0f,result.observedFreeFraction,0f)
        assertTrue(result.invalidDepthFraction>0.5f)
        assertFalse(result.availableForObservation)
    }
    @Test fun isolatedNearReturnIsReportedAsOcclusionRatherThanCollision() {
        val bytes=scene()
        for (y in 0 until 360) {
            val i=(y*640+500)*2
            bytes[i]=0xb0.toByte();bytes[i+1]=4 // isolated 1.2m return
        }
        val result=plan(bytes).right
        assertTrue(result.diagnosticsAvailable)
        assertTrue(result.occludedFraction>0f)
        assertTrue(result.minimumGapMeters>0f)
        assertEquals(0f,result.invalidDepthFraction,0f)
        assertEquals(1f,result.reliableDepthWithinView,0f)
    }
    @Test fun actualCollisionRemainsRejectedAndHasDiagnosticEvidence() {
        val result=plan(scene(Surface(0.35f,1.5f,1.5f))).right
        assertEquals("occupied_corridor",result.reason)
        assertEquals("occupied",result.coverageFailure)
        assertTrue(result.diagnosticsAvailable)
        assertTrue(result.minimumGapMeters<=0f)
        assertFalse(result.availableForObservation)
        assertEquals(0f,result.coverage,0f)
        assertEquals(1f,result.outOfViewFraction+result.invalidDepthFraction+
            result.occludedFraction+result.observedFreeFraction,0.001f)
    }

    @Test fun incompleteCandidateDoesNotEraseMeasuredSideOpening() {
        val bytes=scene()
        for (y in 0 until 360) for (x in 390..415) {
            val i=(y*640+x)*2;bytes[i]=0;bytes[i+1]=0
        }
        val result=plan(bytes).right
        assertFalse(result.accepted)
        assertEquals(0f,result.obstacleOpeningWidthMeters,0f)
        assertNotNull(result.measuredSideOpeningWidthMeters)
        assertTrue(result.measuredSideOpeningWidthMeters!!>0f)
        assertEquals("invalid_depth",result.openingMeasurement?.boundary)
    }
    @Test fun completelyMissingSideDepthReportsUnknownWidth() {
        val result=plan(scene(invalid=true)).right
        assertNull(result.measuredSideOpeningWidthMeters)
        assertFalse(result.availableForObservation)
    }

    @Test fun knownMetricOpeningMatchesProjectionAtObstaclePlane() {
        for (width in listOf(0.6f,0.9f,1.2f)) {
            val result=plan(scene(Surface(0.2f+width,2f,2f))).right
            assertNotNull(result.measuredSideOpeningWidthMeters)
            assertEquals(width,result.measuredSideOpeningWidthMeters!!,0.06f)
            assertEquals(2f,result.openingMeasurement!!.distanceMeters,0f)
            assertEquals("near_surface",result.openingMeasurement!!.boundary)
        }
    }
    @Test fun measuredOpeningIncludesFreeSpaceInsideSpeechEnvelope() {
        val result=plan(scene()).right
        assertTrue(result.measuredSideOpeningWidthMeters!!>result.sideVisibleWidthAtObstacleMeters)
        assertTrue(camera.lateralMeters(result.openingMeasurement!!.startPixel!!,2f)<HorizontalProjection.HALF_FORWARD_METERS)
        assertEquals("field_of_view",result.openingMeasurement!!.boundary)
        val measurement=result.openingMeasurement!!
        assertEquals(measurement.widthMeters!!,
            (measurement.endPixel!!-measurement.startPixel!!)*measurement.distanceMeters/camera.fx,0.0001f)
    }

    @Test fun recordedUpperInterferenceIsRemovedWithoutInventingMissingDepth() {
        val bytes=javaClass.getResourceAsStream("/corridor/right-open-0627.bin")!!.use { it.readBytes() }
        val realCamera=HorizontalProjection(640,360,344.39072f,319.1861f,344.47714f,180.21915f)
        val scene=ImageHeightScene(bytes,640,360,1f,realCamera)
        val result=LocalCorridorPlanner().evaluate(bytes,640,360,1f,realCamera,1.905f,scene)
        assertTrue(result.right.toString(),result.right.minimumGapMeters>0f)
        assertNull(result.right.blockedHeightBand)
        assertEquals("invalid_depth",result.right.coverageFailure)
        assertTrue(result.right.invalidDepthFraction<0.06f)
        assertFalse(result.right.availableForObservation)
        assertFalse(result.left.availableForObservation)
    }

    @Test fun measuredFarBackgroundIsNotMissingDepth() {
        val bytes=scene()
        for(y in 0 until 360) for(x in 420..455) {
            val i=(y*640+x)*2;bytes[i]=0xcc.toByte();bytes[i+1]=0x10 // 4.3m
        }
        val result=plan(bytes).right
        assertEquals(0f,result.invalidDepthFraction,0f)
    }

    @Test fun openingStartsBeyondActualObstacleEvenOutsideSpeechEnvelope() {
        val bytes=scene(Surface(-0.2f,0.65f,2f),Surface(1.35f,2f,2f))
        val result=plan(bytes).right
        assertEquals(0.7f,result.measuredSideOpeningWidthMeters!!,0.06f)
        assertTrue(result.openingMeasurement!!.startPixel!!>camera.pixel(0.5f,2f))
        assertFalse(result.availableForObservation)
    }

    @Test fun recordedOpeningDoesNotRestartOnItsOwnObstacleColumn() {
        val bytes=javaClass.getResourceAsStream("/corridor/right-open-0628.bin")!!.use { it.readBytes() }
        val realCamera=HorizontalProjection(640,360,344.39072f,319.1861f,344.47714f,180.21915f)
        val result=LocalCorridorPlanner().evaluate(bytes,640,360,1f,realCamera,1.338f,
            ImageHeightScene(bytes,640,360,1f,realCamera))
        val measurement=result.right.openingMeasurement!!
        assertEquals(452f,measurement.startPixel!!,0f)
        assertEquals(460f,measurement.endPixel!!,0f)
        assertEquals("invalid_depth",measurement.boundary)
        assertTrue(measurement.widthMeters!!>0f)
        assertFalse(result.right.availableForObservation)
        // The left cabinet recedes gradually. It is still the obstacle surface,
        // rather than a metre-wide gap just because it is farther than targetZ.
        assertNull(result.left.measuredSideOpeningWidthMeters)
        assertEquals("obstacle_continues_out_of_view",result.left.openingMeasurement!!.boundary)
        assertEquals("occupied_corridor",result.left.reason)
        val mask=NavigationRegionOverlay.build(bytes,640,360,1f,realCamera)
        val text=NavigationRegionOverlay.withCorridorStatus(mask,
            AvoidanceSnapshot(corridorRight=result.right)).sideStatus[1]
        assertTrue(text.contains("开口未测完整（缺测）"))
        val leftText=NavigationRegionOverlay.withCorridorStatus(mask,
            AvoidanceSnapshot(corridorLeft=result.left)).sideStatus[0]
        assertTrue(leftText.contains("障碍延伸出画面"))
    }

    @Test fun singleFreeRayIsNotAZeroWidthMeasurement() {
        val bytes=scene(Surface(0.23f,2f,2f))
        val result=plan(bytes).right
        assertNull(result.measuredSideOpeningWidthMeters)
        assertFalse(result.availableForObservation)
    }

}
