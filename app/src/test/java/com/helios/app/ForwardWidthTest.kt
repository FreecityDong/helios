package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class ForwardWidthTest {
    private val camera=HorizontalProjection(640,360,345f,319.5f)
    private val still=MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false)
    private fun frame()=ByteArray(640*360*2).also {bytes ->
        for(i in bytes.indices step 2) {bytes[i]=0xb8.toByte();bytes[i+1]=0x0b}
    }
    private fun set(bytes:ByteArray,x:Int,y:Int,z:Int) {
        val i=(y*640+x)*2;bytes[i]=z.toByte();bytes[i+1]=(z shr 8).toByte()
    }
    private fun evaluate(bytes:ByteArray,width:Float,boxes:List<TargetBox> = emptyList()):NavigationEvaluation {
        val nav=ObstacleNavigation(NavigationConfig(forwardWidthMeters=width),useCorridorPlanner=true,useImageHeightLayers=true)
        var result=NavigationEvaluation("initial")
        for(now in listOf(1000L,1200L,1600L)) result=nav.evaluate(now,bytes,640,360,1f,boxes,still,
            VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,null,camera)
        return result
    }
    @Test fun widthSettingIsTotalCentimetersAndOldSavedValuesKeepDefault() {
        val p=DebugParameters.defaults()
        assertEquals(60.0,p["forwardWidth"],0.0)
        assertEquals(.6f,DebugParameters.from(mapOf("caution" to 2.0)).navigation.forwardWidthMeters,0.0001f)
        assertEquals(1f,p.patched(mapOf("forwardWidth" to 100.0)).navigation.forwardWidthMeters,0.0001f)
        assertEquals(.2f,p.patched(mapOf("forwardWidth" to 20.0)).navigation.forwardWidthMeters,0.0001f)
        for(value in listOf(19.0,151.0,Double.NaN,Double.POSITIVE_INFINITY)) {
            try {p.patched(mapOf("forwardWidth" to value));fail("invalid width accepted")} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun pointMembershipAndOverlayAgreeForEveryConfiguredWidthAndDepth() {
        for(width in listOf(.2f,.6f,1f,1.5f)) for(z in listOf(.5f,1f,2f,4f)) {
            val geometry=camera.copy(forwardWidthMeters=width)
            for(x in listOf(-width/2-.01f,-width/2+.01f,width/2-.01f,width/2+.01f)) {
                val pixel=geometry.pixel(x,z)
                assertEquals(geometry.inForward(pixel,z),NavigationRegionOverlay.classify(pixel,z,geometry)==NavigationRegionOverlay.FORWARD)
                assertEquals(kotlin.math.abs(x)<width/2,geometry.inForward(pixel,z))
            }
        }
    }
    @Test fun widerRegionChangesBothKnownUnknownDetectionAndMaskTogether() {
        val bytes=frame();val z=1.2f
        val left=camera.pixel(.35f,z).toInt();val right=camera.pixel(.46f,z).toInt()
        for(y in 132..227) for(x in left..right) set(bytes,x,y,1200)
        val box=TargetBox(56,.95f,left.toFloat(),132f,right.toFloat(),227f,DepthEstimate(z,1f,"test"))
        for(boxes in listOf(emptyList(),listOf(box))) {
            assertNull(evaluate(bytes,.6f,boxes).episodeId)
            assertNotNull(evaluate(bytes,1f,boxes).episodeId)
        }
        val point=180*640+(left+right)/2
        val narrow=NavigationRegionOverlay.build(bytes,640,360,1f,camera.copy(forwardWidthMeters=.6f))
        val wide=NavigationRegionOverlay.build(bytes,640,360,1f,camera.copy(forwardWidthMeters=1f))
        assertNotEquals(NavigationRegionOverlay.FORWARD,narrow.regions[point])
        assertEquals(NavigationRegionOverlay.FORWARD,wide.regions[point])
        assertEquals(1f,wide.forwardWidthMeters,0f)
    }
    @Test fun perPixelMaskShowsASurfaceThatOldFivePointTilesEntirelyMissed() {
        val bytes=frame()
        for(y in 161..167) for(x in 401..407) set(bytes,x,y,700)
        set(bytes,404,164,3000);set(bytes,407,167,3000)
        // All five old tile samples see only the outside background.
        for((x,y) in listOf(400 to 160,407 to 160,400 to 167,407 to 167,404 to 164))
            assertNotEquals(NavigationRegionOverlay.FORWARD,NavigationRegionOverlay.classify(x.toFloat(),3f,camera))
        val rawBox=TargetBox(0,.95f,401f,161f,408f,168f)
        val distance=DetectionGeometry.distance(rawBox,bytes,640,360,1f)
        assertEquals(.7f,distance.meters!!,.001f)
        val box=rawBox.copy(distance=distance)
        assertNotNull(evaluate(bytes,.6f,listOf(box)).episodeId)
        assertNull(evaluate(bytes,.3f,listOf(box)).episodeId)
        val mask=NavigationRegionOverlay.build(bytes,640,360,1f,camera)
        assertEquals(1,mask.cellSize)
        assertEquals(NavigationRegionOverlay.FORWARD,mask.regions[161*640+401])
        assertNotEquals(NavigationRegionOverlay.FORWARD,mask.regions[164*640+404])
    }
    @Test fun everyAcceptedCentralSurfacePointIsShownForwardWithoutTilePriorityOverride() {
        val bytes=frame()
        for(y in 135..220) for(x in 350..460) set(bytes,x,y,800)
        for(width in listOf(.2f,.6f,1f)) {
            val projection=camera.copy(forwardWidthMeters=width)
            val scene=ImageHeightScene(bytes,640,360,1f,projection)
            val mask=NavigationRegionOverlay.build(bytes,640,360,1f,projection)
            for(y in 135..220 step 3) for(x in 350..460 step 3)
                if(scene.accept(ImageHeightBand.FRONT,x,y,.8f))
                    assertEquals(NavigationRegionOverlay.FORWARD,mask.regions[y*640+x])
        }
    }
    @Test fun measuredSideOnlySurfaceDoesNotCreateAnIndependentCentralAlarmAtNarrowWidth() {
        val bytes=frame()
        for(y in 120..230) for(x in 0..80) set(bytes,x,y,700)
        assertNull(evaluate(bytes,.2f).episodeId)
        val mask=NavigationRegionOverlay.build(bytes,640,360,1f,camera.copy(forwardWidthMeters=.2f))
        assertEquals(NavigationRegionOverlay.SIDE_TOO_CLOSE,mask.regions[180*640+40])
    }
    @Test fun obstacleReleaseUsesTheSameConfiguredWidthAsDetection() {
        val initial=frame()
        for(y in 130..229) for(x in 300..339) set(initial,x,y,800)
        val next=frame();val z=1.2f
        for(y in 132..227) for(x in camera.pixel(.35f,z).toInt()..camera.pixel(.46f,z).toInt()) set(next,x,y,1200)
        for(width in listOf(.6f,1f)) {
            val nav=ObstacleNavigation(NavigationConfig(forwardWidthMeters=width),useCorridorPlanner=true,useImageHeightLayers=true)
            fun step(now:Long,bytes:ByteArray)=nav.evaluate(now,bytes,640,360,1f,emptyList(),still,
                VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,null,camera)
            for(now in 1000L..1600L step 200) step(now,initial)
            var clears=0
            for(now in 1800L..3200L step 200) if(step(now,next).event?.kind==RiskEventKind.HEADING_CLEAR) clears++
            assertEquals(if(width==.6f) 1 else 0,clears)
        }
    }
}
