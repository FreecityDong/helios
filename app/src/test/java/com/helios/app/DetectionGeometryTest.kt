package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class DetectionGeometryTest {
    @Test fun letterboxCoordinatesMapBackAndNmsIsClassAware() {
        val data = Array(84) { FloatArray(3) }
        for (i in 0..2) { data[0][i]=160f; data[1][i]=160f; data[2][i]=100f; data[3][i]=100f }
        data[4][0]=0.9f; data[4][1]=0.8f; data[5][2]=0.7f
        val boxes = DetectionGeometry.decode(data,640,480)
        assertEquals(2,boxes.size)
        assertEquals(220f,boxes[0].left,0.001f); assertEquals(140f,boxes[0].top,0.001f)
    }
    @Test fun letterbox416MapsBackToAlignedRgbCoordinates() {
        val data = Array(84) { FloatArray(1) }
        data[0][0]=208f; data[1][0]=208f; data[2][0]=65f; data[3][0]=65f; data[4][0]=0.9f
        val box=DetectionGeometry.decode(data,640,480,416).single()
        assertEquals(270f,box.left,0.001f)
        assertEquals(190f,box.top,0.001f)
        assertEquals(370f,box.right,0.001f)
        assertEquals(290f,box.bottom,0.001f)
    }
    @Test fun nearestSupportedSurfaceRejectsIsolatedLowPixelAndUsesMetricScale() {
        val box = TargetBox(0,0.9f,0f,0f,40f,40f)
        fun bytes(value: (Int)->Int) = ByteArray(40*40*2).also { b -> for (i in 0 until 1600) { val v=value(i); b[i*2]=v.toByte(); b[i*2+1]=(v shr 8).toByte() } }
        val known = DetectionGeometry.distance(box,bytes { 1000 },40,40,2f)
        assertEquals(2f,known.meters!!,0.001f)
        assertNull(DetectionGeometry.distance(box,bytes { 0 },40,40,1f).meters)
        val foreground = DetectionGeometry.distance(box,bytes { if(it%40<20) 1000 else 3000 },40,40,1f)
        assertEquals(1f,foreground.meters!!,0.001f)
        val noise = DetectionGeometry.distance(box,bytes { if(it==20*40+20) 300 else 2000 },40,40,1f)
        assertEquals(2f,noise.meters!!,0.001f)
        val edge = DetectionGeometry.distance(box,bytes { if(it%40<3) 300 else 2000 },40,40,1f)
        assertEquals(2f,edge.meters!!,0.001f)
        assertEquals("框内最近连续表面",foreground.reason)
        assertNull(DetectionGeometry.distance(box,ByteArray(2),40,40,1f).meters)
    }

    @Test fun nestedDetectionsDoNotGiveOuterTargetTheInnerTargetsDistance() {
        val person = TargetBox(0,0.9f,0f,0f,40f,40f)
        val cup = TargetBox(41,0.8f,14f,14f,26f,26f)
        val bytes = ByteArray(40*40*2)
        for (y in 0 until 40) for (x in 0 until 40) {
            val mm = if (x in 14..25 && y in 14..25) 500 else 2000
            val p = (y*40+x)*2
            bytes[p]=mm.toByte(); bytes[p+1]=(mm shr 8).toByte()
        }
        val boxes = listOf(person,cup)
        assertEquals(2f, DetectionGeometry.distance(person,boxes,bytes,40,40,1f).meters!!,0.001f)
        assertEquals(0.5f, DetectionGeometry.distance(cup,boxes,bytes,40,40,1f).meters!!,0.001f)
        assertEquals("未重叠区域最近连续表面",DetectionGeometry.distance(person,boxes,bytes,40,40,1f).reason)
    }

    @Test fun partialOverlapUsesEachTargetsExclusiveVisibleSurface() {
        val first=TargetBox(0,0.8f,0f,0f,30f,30f)
        val second=TargetBox(56,0.7f,15f,0f,45f,30f)
        val depth=ByteArray(50*30*2)
        for (y in 0 until 30) for (x in 0 until 50) {
            val mm=when { x<15 -> 1000; x<30 -> 500; else -> 3000 }
            val p=(y*50+x)*2
            depth[p]=mm.toByte(); depth[p+1]=(mm shr 8).toByte()
        }
        val boxes=listOf(first,second)
        assertEquals(1f,DetectionGeometry.distance(first,boxes,depth,50,30,1f).meters!!,0.001f)
        assertEquals(3f,DetectionGeometry.distance(second,boxes,depth,50,30,1f).meters!!,0.001f)
    }

    @Test fun almostCompletelyOverlappingBoxesStayUnknown() {
        val first=TargetBox(0,0.8f,0f,0f,40f,40f)
        val second=TargetBox(56,0.7f,1f,0f,41f,40f)
        val depth=ByteArray(50*40*2) { if (it%2==0) 0xe8.toByte() else 0x03.toByte() }
        val boxes=listOf(first,second)
        assertNull(DetectionGeometry.distance(first,boxes,depth,50,40,1f).meters)
        assertEquals("重叠后可归属区域不足",DetectionGeometry.distance(second,boxes,depth,50,40,1f).reason)
    }

    @Test fun sharedDepthIsAssignedOnlyToItsConnectedExclusiveSurface() {
        val left=TargetBox(0,0.9f,0f,0f,30f,30f)
        val right=TargetBox(56,0.8f,15f,0f,45f,30f)
        fun frame(depthAt: (Int)->Int): ByteArray = ByteArray(50*30*2).also { data ->
            for (y in 0 until 30) for (x in 0 until 50) {
                val mm=depthAt(x); val p=(y*50+x)*2
                data[p]=mm.toByte(); data[p+1]=(mm shr 8).toByte()
            }
        }
        val boxes=listOf(left,right)
        val continuous=DetectionGeometry.distances(boxes,frame { x -> if (x<23) 1000 else 3000 },50,30,1f)
        assertEquals(1f,continuous[0].meters!!,0.001f)
        assertEquals(3f,continuous[1].meters!!,0.001f)
        assertEquals("交叉区域连续深度归属",continuous[0].reason)
        assertEquals("交叉区域连续深度归属",continuous[1].reason)

        // A same-depth island behind a depth discontinuity is not connected to the left seed.
        val island=DetectionGeometry.distances(boxes,frame { x -> when { x<15 -> 1000; x<19 -> 2000; x<25 -> 1000; else -> 3000 } },50,30,1f)
        assertEquals(1f,island[0].meters!!,0.001f)
        assertEquals("未重叠区域最近连续表面",island[0].reason)
    }

    @Test fun equalDepthReferencesCannotClaimSharedPixels() {
        val left=TargetBox(0,0.9f,0f,0f,30f,30f)
        val right=TargetBox(56,0.8f,15f,0f,45f,30f)
        val data=ByteArray(50*30*2)
        for (y in 0 until 30) for (x in 0 until 50) {
            val mm=if (x in 15..29) 500 else 1000; val p=(y*50+x)*2
            data[p]=mm.toByte(); data[p+1]=(mm shr 8).toByte()
        }
        val results=DetectionGeometry.distances(listOf(left,right),data,50,30,1f)
        assertEquals("未重叠区域最近连续表面",results[0].reason)
        assertEquals("未重叠区域最近连续表面",results[1].reason)
        assertEquals(1f,results[0].meters!!,0.001f)
        assertEquals(1f,results[1].meters!!,0.001f)
    }

    @Test fun sparseExclusiveSurfaceCanGainQualityFromContinuousOverlap() {
        val left=TargetBox(0,0.9f,0f,0f,32f,40f)
        val right=TargetBox(56,0.8f,16f,0f,48f,40f)
        val data=ByteArray(50*40*2)
        for (y in 0 until 40) for (x in 0 until 50) {
            val mm=when {
                x in 10..23 -> 1000
                x >= 24 -> 3000
                else -> 0
            }
            val p=(y*50+x)*2
            data[p]=mm.toByte(); data[p+1]=(mm shr 8).toByte()
        }
        val result=DetectionGeometry.distances(listOf(left,right),data,50,40,1f)
        assertEquals(1f,result[0].meters!!,0.001f)
        assertEquals("交叉区域连续深度归属",result[0].reason)
        assertEquals(3f,result[1].meters!!,0.001f)
    }

    @Test fun sparseExclusiveSurfaceWithoutConnectedOverlapRemainsUnknown() {
        val left=TargetBox(0,0.9f,0f,0f,32f,40f)
        val right=TargetBox(56,0.8f,16f,0f,48f,40f)
        val data=ByteArray(50*40*2)
        for (y in 0 until 40) for (x in 0 until 50) {
            val mm=when {
                x in 3..8 || x in 18..23 -> 1000
                x >= 24 -> 3000
                else -> 0
            }
            val p=(y*50+x)*2
            data[p]=mm.toByte(); data[p+1]=(mm shr 8).toByte()
        }
        val result=DetectionGeometry.distances(listOf(left,right),data,50,40,1f)
        assertNull(result[0].meters)
        assertEquals(3f,result[1].meters!!,0.001f)
    }

    @Test fun oneThreeEdgeIntersectionGivesOverlapToShorterIntersectingSegment() {
        val large=TargetBox(0,0.9f,0f,0f,40f,40f)
        val small=TargetBox(41,0.8f,32f,10f,50f,30f)
        fun frame(smallInFront: Boolean)=ByteArray(50*40*2).also { data ->
            for (y in 0 until 40) for (x in 0 until 50) {
                val mm=when {
                    x>=40 && y in 10..29 -> if (smallInFront) 1000 else 3000
                    x>=32 && y in 10..29 -> if (smallInFront) 1000 else 2000
                    else -> 2000
                }
                val p=(y*50+x)*2
                data[p]=mm.toByte(); data[p+1]=(mm shr 8).toByte()
            }
        }
        val boxes=listOf(large,small)
        val front=DetectionGeometry.distances(boxes,frame(true),50,40,1f)
        assertEquals(2f,front[0].meters!!,0.001f)
        assertEquals(1f,front[1].meters!!,0.001f)
        val behind=DetectionGeometry.distances(boxes,frame(false),50,40,1f)
        assertEquals(2f,behind[0].meters!!,0.001f)
        assertEquals(2f,behind[1].meters!!,0.001f)
        assertEquals("一三边短交叉段优先",behind[1].reason)
    }

    @Test fun threeEdgeIntersectionDoesNotAssignDepthToTargetWithoutExclusiveAnchor() {
        val large=TargetBox(0,0.9f,0f,0f,40f,40f)
        val strip=TargetBox(56,0.8f,-5f,-5f,45f,20f)
        val data=ByteArray(50*40*2)
        for (y in 0 until 40) for (x in 0 until 50) {
            val mm=if (y<20) 1000 else 2000
            val p=(y*50+x)*2
            data[p]=mm.toByte(); data[p+1]=(mm shr 8).toByte()
        }
        val result=DetectionGeometry.distances(listOf(large,strip),data,50,40,1f)
        assertEquals(1f,result[0].meters!!,0.001f)
        assertNull(result[1].meters)
    }

    @Test fun detectedPhoneMostlyInsidePersonGetsItsOwnVisibleDepth() {
        val person=TargetBox(0,0.93f,215f,108f,639f,480f)
        val phone=TargetBox(67,0.84f,194f,276f,321f,421f)
        val data=ByteArray(640*480*2)
        for (y in 0 until 480) for (x in 0 until 640) {
            val mm=if (x in 194..320 && y in 276..420) 500 else 2000
            val p=(y*640+x)*2
            data[p]=mm.toByte(); data[p+1]=(mm shr 8).toByte()
        }
        val result=DetectionGeometry.distances(listOf(person,phone),data,640,480,1f)
        assertEquals(2f,result[0].meters!!,0.001f)
        assertEquals(0.5f,result[1].meters!!,0.001f)
        assertEquals("一三边短交叉段优先",result[1].reason)
    }

    @Test fun twoTwoCornerIntersectionKeepsDepthContinuityRule() {
        val first=TargetBox(0,0.9f,0f,0f,30f,30f)
        val second=TargetBox(56,0.8f,15f,15f,45f,45f)
        val data=ByteArray(45*45*2)
        for (y in 0 until 45) for (x in 0 until 45) {
            val mm=if (x<15 || y<15 || (x<30 && y<30 && x+y<45)) 1000 else 3000
            val p=(y*45+x)*2
            data[p]=mm.toByte(); data[p+1]=(mm shr 8).toByte()
        }
        val result=DetectionGeometry.distances(listOf(first,second),data,45,45,1f)
        assertEquals(1f,result[0].meters!!,0.001f)
        assertEquals(3f,result[1].meters!!,0.001f)
        assertEquals("交叉区域连续深度归属",result[0].reason)
        assertEquals("交叉区域连续深度归属",result[1].reason)
    }
}
