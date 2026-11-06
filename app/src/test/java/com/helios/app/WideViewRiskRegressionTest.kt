package com.helios.app

import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual new 640x360 frame shape without changing the risk thresholds. */
class WideViewRiskRegressionTest {
    private val moving = MotionSnapshot(state=MotionState.MOVING,calibrated=true,stale=false)
    private fun box(width: Int,height: Int,meters: Float) = TargetBox(0,0.8f,
        width*0.4f,height*0.2f,width*0.6f,height*0.8f,DepthEstimate(meters,0.9f,"fixture"))
    private fun lanes(width: Int,height: Int,left: Int,center: Int,right: Int): ByteArray =
        ByteArray(width*height*2).also { data ->
            for (y in 0 until height) for (x in 0 until width) {
                val mm=when { x<width*0.34f -> left; x<width*0.66f -> center; else -> right }
                val i=(y*width+x)*2
                data[i]=mm.toByte(); data[i+1]=(mm shr 8).toByte()
            }
        }

    @Test fun wideFrameSupportsBothEarlyDirectionsAndSingleDelivery() {
        for (direction in AvoidanceDirection.entries) {
            val evaluator=AvoidanceDirectionEvaluator()
            val bytes=if(direction==AvoidanceDirection.LEFT) lanes(640,360,2500,1500,900)
                else lanes(640,360,900,1500,2500)
            fun sample(t: Long)=evaluator.evaluate(t,bytes,640,360,1f,listOf(box(640,360,1.5f)),
                moving,VisualMotionEvidence(VisualMotionKind.STABLE,t,12,10),true,true,null)
            assertFalse(sample(1000).directionReady)
            assertFalse(sample(1250).directionReady)
            val result=sample(1500)
            assertEquals(direction,result.direction)
            assertEquals(RiskLevel.CAUTION,result.guidanceLevel)
            assertTrue(result.directionReady)
            assertFalse(sample(1750).directionReady)
        }
    }

    @Test fun approachAlertUsesScaledBoxCoordinatesInBothFrameShapes() {
        for(height in listOf(480,360)) {
            val evaluator=LocalRiskEvaluator()
            evaluator.evaluate(1000,listOf(box(640,height,1.7f)),640,height,moving,SessionState.ACTIVE,true)
            evaluator.evaluate(1250,listOf(box(640,height,1.6f)),640,height,moving,SessionState.ACTIVE,true)
            val result=evaluator.evaluate(1500,listOf(box(640,height,1.5f)),640,height,moving,SessionState.ACTIVE,true)
            assertEquals(RiskLevel.CAUTION,result.event?.level)
            assertEquals(1.5f,result.event!!.distanceMeters,0.001f)
        }
    }

    @Test fun wideDepthHolesNeverProduceAnOpeningOrDeparture() {
        val evaluator=AvoidanceDirectionEvaluator()
        val bytes=lanes(640,360,0,1500,0)
        for(t in 1000L..2000L step 250L) {
            val result=evaluator.evaluate(t,bytes,640,360,1f,listOf(box(640,360,1.5f)),
                moving,VisualMotionEvidence(VisualMotionKind.STABLE,t,12,10),true,true,null)
            assertNull(result.direction)
            assertFalse(result.obstacleCleared)
        }
    }
}
