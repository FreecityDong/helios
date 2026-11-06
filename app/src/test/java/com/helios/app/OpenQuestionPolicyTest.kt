package com.helios.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenQuestionPolicyTest {
    @Test fun unrestrictedVisualQuestionsAreRoutedWithoutAnAllowlist() {
        for(q in listOf("杯子是什么颜色", "左边桌上放着什么", "门上的字写的是什么", "这个杯子有什么具体特征"))
            assertEquals(VoiceIntent.ASK_SCENE,OpenQuestionPolicy.resolve(q))
    }
    @Test fun controlsAndFindRequestsAlwaysStayLocal() {
        assertEquals(VoiceIntent.CANCEL_TASK,OpenQuestionPolicy.resolve("取消"))
        assertEquals(VoiceIntent.PAUSE_ASSISTANCE,OpenQuestionPolicy.resolve("暂停辅助"))
        assertEquals(VoiceIntent.STOP_ASSISTANCE,OpenQuestionPolicy.resolve("退出辅助"))
        assertEquals(VoiceIntent.FIND_OBJECT,OpenQuestionPolicy.resolve("找杯子"))
        assertEquals(VoiceIntent.CONFIRM_FIND,OpenQuestionPolicy.resolve("确认"))
        assertEquals(VoiceIntent.DESCRIBE_DETAIL,OpenQuestionPolicy.resolve("再详细一点"))
    }
    @Test fun explicitConfirmationWindowsNeverTurnUnknownSpeechIntoAQuestion() {
        assertEquals(VoiceIntent.UNKNOWN,OpenQuestionPolicy.resolve("杯子的颜色",confirmingExit=true))
        assertEquals(VoiceIntent.UNKNOWN,OpenQuestionPolicy.resolve("杯子的颜色",constrained=true))
        assertEquals(VoiceIntent.CANCEL_TASK,OpenQuestionPolicy.resolve("取消",constrained=true))
        assertEquals(VoiceIntent.CONFIRM_STOP,OpenQuestionPolicy.resolve("确认退出",confirmingExit=true))
    }
    @Test fun noiseAndAcknowledgementsDoNotCreateAnotherCloudTurn() {
        for(q in listOf("", "[unk]", "谢谢。", "好的", "嗯嗯", "长".repeat(161))) {
            assertNull(OpenQuestionPolicy.clean(q))
            assertNotEquals(VoiceIntent.ASK_SCENE,OpenQuestionPolicy.resolve(q))
        }
        assertEquals("杯子是什么颜色",OpenQuestionPolicy.clean("杯子 是 什么 颜色 [unk]"))
    }
    @Test fun eachQuestionRequestContainsOnlyItsQuestionAndFreshImage() {
        val first=SceneProtocol.request("first-image",false,"杯子是什么颜色")
        val next=SceneProtocol.request("second-image",false,"杯子有什么特征")
        val messages=next.getJSONArray("messages")
        assertEquals(2,messages.length())
        val content=messages.getJSONObject(1).getJSONArray("content")
        assertEquals("杯子有什么特征",content.getJSONObject(0).getString("text"))
        assertTrue(content.getJSONObject(1).toString().contains("second-image"))
        assertFalse(next.toString().contains("first-image"))
        assertFalse(next.toString().contains("杯子是什么颜色"))
        assertEquals("disabled",first.getJSONObject("thinking").getString("type"))
        assertEquals(320,next.getInt("max_tokens"))
        assertEquals("白色杯子。带有把手。",SceneProtocol.text(JSONObject("""{"summary":"白色杯子","details":["带有把手"],"uncertainty":""}"""),true))
    }
    @Test fun movementAndStaleInputsBlockTheQuestionGate() {
        val state=OperationalState(device=DeviceState.STREAMING,motion=MotionSnapshot(state=MotionState.STILL,stale=false,calibrated=true))
        assertTrue(SceneGate.ready(state,2000,1900,false,0))
        for(m in listOf(MotionState.MOVING,MotionState.SHAKING))
            assertFalse(SceneGate.ready(state.copy(motion=state.motion.copy(state=m)),2000,1900,false,0))
        assertFalse(SceneGate.ready(state.copy(motion=state.motion.copy(stale=true)),2000,1900,false,0))
        assertFalse(SceneGate.ready(state,2000,500,false,0))
    }
}
