package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class HorizontalProjectionTest {
    private val camera = HorizontalProjection(640,360,345f,319.5f)
    private val motion = MotionSnapshot(state=MotionState.STILL,calibrated=true,stale=false)
    private val pose = doubleArrayOf(1.0,0.0,0.0,0.0)
    private fun box(z: Float,left: Float=-0.2f,right: Float=0.2f) = TargetBox(56,0.9f,
        camera.pixel(left,z),90f,camera.pixel(right,z),260f,DepthEstimate(z,1f,"test"))
    private fun frame(z: Float,nearSide: Boolean=false,rightMeters: Float=3f,halfWidth: Float=0.2f): ByteArray {
        val target=box(z,-halfWidth,halfWidth)
        return ByteArray(640*360*2).also { bytes ->
            for (y in 0 until 360) for (x in 0 until 640) {
                val meters = when {
                    x.toFloat() in target.left..target.right && y in 90..260 -> z
                    nearSide && x in 20..70 -> 1f
                    x >= 320 -> rightMeters
                    else -> 3f
                }
                val raw=(meters*1000).toInt(); val i=(y*640+x)*2
                bytes[i]=raw.toByte();bytes[i+1]=(raw shr 8).toByte()
            }
        }
    }
    private fun step(nav: ObstacleNavigation,now: Long,z: Float,known: Boolean=true) =
        nav.evaluate(now,frame(z),640,360,1f,if (known) listOf(box(z)) else emptyList(),
            motion,VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),true,true,pose,camera)

    @Test fun sameWidthHasGrowingImageRatioAndConstantMetricOccupancy() {
        for (z in listOf(2f,1f,0.5f)) {
            val target=box(z)
            assertEquals(0.4f,camera.lateralMeters(target.right,z)-camera.lateralMeters(target.left,z),0.0001f)
            assertEquals(0f,camera.overlapMeters(target,-0.95f,-0.32f)!!,0.0001f)
            assertEquals(0f,camera.overlapMeters(target,0.32f,0.95f)!!,0.0001f)
        }
        assertEquals(4f,(box(0.5f).right-box(0.5f).left)/(box(2f).right-box(2f).left),0.0001f)
    }
    @Test fun wideObstacleStillOccupiesBothPhysicalSides() {
        for (z in listOf(2f,1f,0.5f)) {
            assertTrue(camera.overlapMeters(box(z,-0.8f,0.8f),-0.95f,-0.32f)!! > 0.4f)
            assertTrue(camera.overlapMeters(box(z,-0.8f,0.8f),0.32f,0.95f)!! > 0.4f)
        }
    }
    @Test fun nearOcclusionUsesRecentSpaceForKnownAndUnknownTargets() {
        for (known in listOf(true,false)) {
            val nav=ObstacleNavigation()
            step(nav,1000,2f,known);step(nav,1250,2f,known)
            val caution=step(nav,1500,2f,known)
            assertEquals("distance_projected",caution.guidance.geometryMode)
            assertEquals(AvoidanceDirection.RIGHT,caution.event?.avoidanceDirection)
            step(nav,1750,1.5f,known)
            step(nav,2000,1f,known)
            assertEquals(AvoidanceDirection.RIGHT,step(nav,2250,1f,known).event?.avoidanceDirection)
            step(nav,2500,0.5f,known)
            val urgent=step(nav,2750,0.5f,known)
            assertEquals(RiskLevel.URGENT,urgent.event?.level)
            assertEquals(AvoidanceDirection.RIGHT,urgent.event?.avoidanceDirection)
            assertTrue(urgent.event!!.rememberedDirection)
            assertTrue(urgent.guidance.rightValidRatio < 0.65f)
        }
    }
    @Test fun missingHistoryDoesNotCertifySpaceOutsideFieldOfView() {
        val nav=ObstacleNavigation()
        step(nav,1000,0.5f)
        assertNull(step(nav,1250,0.5f).event?.avoidanceDirection)
    }
    @Test fun nearSideOutsideProjectedBandVetoesDirection() {
        val evaluator=AvoidanceDirectionEvaluator()
        var result=AvoidanceSnapshot()
        for (now in 1000L..1500L step 250L) result=evaluator.evaluate(now,frame(2f,true,2.5f),640,360,1f,
            listOf(box(2f)),motion,VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),
            true,true,null,false,pose,camera,2f)
        assertEquals(AvoidanceDirection.RIGHT,result.direction)
    }
    @Test fun thinKnownObstacleDoesNotDisappearInCenterDepthPercentile() {
        val nav=ObstacleNavigation()
        var result=NavigationEvaluation("initial")
        for (now in 1000L..1500L step 250L) result=nav.evaluate(now,frame(2f,halfWidth=0.04f),640,360,1f,
            listOf(box(2f,-0.04f,0.04f)),motion,VisualMotionEvidence(VisualMotionKind.STABLE,now,12,10),
            true,true,pose,camera)
        assertEquals(RiskLevel.CAUTION,result.event?.level)
        assertEquals(AvoidanceDirection.RIGHT,result.event?.avoidanceDirection)
    }
    @Test fun mismatchedOrInvalidIntrinsicsCannotBeUsed() {
        assertFalse(camera.matches(640,480))
        assertFalse(camera.copy(fx=Float.NaN).matches(640,360))
        val result=ObstacleNavigation().evaluate(1000,frame(2f),640,360,1f,listOf(box(2f)),
            motion,VisualMotionEvidence(VisualMotionKind.STABLE,1000,12,10),true,true,pose,camera.copy(height=480))
        assertEquals("image_fraction_fallback",result.guidance.geometryMode)
    }
}
