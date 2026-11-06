package com.helios.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FindProtocolTest {
    private val keys=FindTarget.resolve("钥匙")!!
    private fun payload(region: Any = JSONArray(listOf(.1,.1,.4,.4)), found: Boolean=true, confidence: Double=.95, target: String="钥匙") =
        JSONObject().put("target",target).put("found",found).put("confidence",confidence).put("region",region)
    private fun wrap(value: JSONObject) = JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop")
        .put("message",JSONObject().put("content",value.toString()))))
    private fun depth(mm: Int) = ByteArray(80*80*2).apply { for(i in indices step 2) { this[i]=(mm and 255).toByte(); this[i+1]=(mm shr 8).toByte() } }
    private fun evidence(bytes: ByteArray=depth(2000)) = SceneEvidence(80,80,depth=bytes,depthWidth=80,depthHeight=80,depthScaleMm=1f,depthDeltaUs=0)
    @Test fun requestUsesSingleImageFixedTargetAndNonThinking() {
        val body=FindProtocol.request(keys,"single-image")
        assertEquals("deepseek-flash",body.getString("model"));assertEquals("disabled",body.getJSONObject("thinking").getString("type"))
        assertEquals(2,body.getJSONArray("messages").length())
        assertTrue(body.toString().contains("single-image"));assertTrue(body.toString().contains("钥匙"))
    }
    @Test fun cloudRegionUsesAlignedDepthFromCapturedPairEvenWithoutYoloClass() {
        val observation=FindProtocol.parse(wrap(payload()),keys,evidence())
        assertTrue(observation.depthVerified);assertEquals(2f,observation.meters!!,.01f)
    }
    @Test fun missingDepthNeverTurnsModelConfidenceIntoMetricDistance() {
        val observation=FindProtocol.parse(wrap(payload()),keys,SceneEvidence(80,80))
        assertNull(observation.meters);assertFalse(observation.depthVerified)
        assertTrue(FindProtocol.describe(keys,observation).contains("距离暂不能确认"))
    }
    @Test fun wrongAlignmentAndSparseDepthRemainUnknown() {
        for(frame in listOf(evidence().copy(depthWidth=40),evidence(ByteArray(80*80*2)))) {
            val observation=FindProtocol.parse(wrap(payload()),keys,frame)
            assertNull(observation.meters);assertFalse(observation.depthVerified)
        }
    }
    @Test fun trustworthyDistanceSpeechIsConcise() {
        val observation=FindProtocol.parse(wrap(payload()),keys,evidence())
        val text=FindProtocol.describe(keys,observation)
        assertTrue(text.contains("约2.0米"));assertFalse(text.contains("最近可见表面"))
    }
    @Test fun invalidRegionWrongTargetAndExtraFieldsAreRejected() {
        for(value in listOf(payload(target="手机"),payload(region=JSONArray(listOf(-.1,.1,.3,.3))),payload(region=JSONArray(listOf(.4,.1,.2,.3))),payload().put("distance",1),payload(confidence=1.1))) {
            assertTrue(runCatching { FindProtocol.parse(wrap(value),keys,evidence()) }.isFailure)
        }
    }
    @Test fun notFoundAndNetworkFailureAreSeparateOutcomes() {
        val observation=FindProtocol.parse(wrap(payload(JSONObject.NULL,false,0.0)),keys,evidence())
        assertFalse(observation.found);assertNull(observation.region)
        assertTrue(runCatching { FindProtocol.parse(JSONObject().put("error","network_error"),keys,evidence()) }.isFailure)
    }
    @Test fun coordinateDirectionsAreInCameraFrame() {
        assertEquals("画面左侧",FindRegion(.1,.1,.3,.4).direction)
        assertEquals("画面中间",FindRegion(.4,.1,.6,.4).direction)
        assertEquals("画面右侧",FindRegion(.7,.1,.9,.4).direction)
    }
    @Test fun providerFormatMetadataDoesNotRejectAnOtherwiseValidObservation() {
        val actual=payload(JSONObject.NULL,false,0.0).put("type","json_object")
        assertFalse(FindProtocol.parse(wrap(actual),keys,evidence()).found)
        assertTrue(runCatching { FindProtocol.parse(wrap(payload().put("type","other")),keys,evidence()) }.isFailure)
    }

    @Test fun outOfSyncDepthCannotLeakThroughLocalYoloFallback() {
        val phone=FindTarget.resolve("手机")!!
        val local=TargetBox(67,.99f,8f,8f,32f,32f,DepthEstimate(1f,1f,"valid"))
        for(delta in listOf<Long?>(null,50_001L)) {
            val frame=evidence().copy(targets=listOf(local),depthDeltaUs=delta)
            val observation=FindProtocol.parse(wrap(payload(target="手机")),phone,frame)
            assertNull(observation.meters);assertFalse(observation.depthVerified)
        }
    }

    @Test fun visibleContextIsPreservedButMetricAndWalkingClaimsAreRejected() {
        val good=payload().put("context","在桌面左侧，靠近显示器")
        assertEquals("在桌面左侧，靠近显示器",FindProtocol.parse(wrap(good),keys,evidence()).context)
        for(text in listOf("向左走", "可以通行", "前方1米", "前方一米")) {
            assertTrue(runCatching { FindProtocol.parse(wrap(payload().put("context",text)),keys,evidence()) }.isFailure)
        }
    }
    @Test fun onlyLargeNearbyDepthVerifiedObstaclesAreMentioned() {
        val chair=TargetBox(56,.9f,15f,10f,65f,75f,DepthEstimate(.9f,.9f,"valid"))
        val frame=evidence().copy(targets=listOf(chair))
        assertEquals("椅子",FindProtocol.parse(wrap(payload()),keys,frame).hazard?.name)
        for(box in listOf(chair.copy(distance=DepthEstimate(3f,.9f,"valid")),chair.copy(right=20f,bottom=20f),chair.copy(distance=DepthEstimate(null,0f,"invalid")))) {
            assertNull(FindProtocol.parse(wrap(payload()),keys,frame.copy(targets=listOf(box))).hazard)
        }
        assertNull(FindProtocol.parse(wrap(payload()),keys,frame.copy(depthDeltaUs=50_001)).hazard)
    }
    @Test fun targetItselfIsNotRepeatedAsADifferentObstacle() {
        val phone=FindTarget.resolve("椅子")!!
        val box=TargetBox(56,.9f,8f,8f,32f,32f,DepthEstimate(.5f,1f,"valid"))
        val observed=FindProtocol.parse(wrap(payload(target="椅子")),phone,evidence().copy(targets=listOf(box)))
        assertNull(observed.hazard)
    }

}
