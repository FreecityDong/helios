package com.helios.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SceneProtocolTest {
    private fun scene() = JSONObject().put("summary", "近处左侧是椅子").put("details", JSONArray().put("稍远处是办公桌")).put("uncertainty", "")
    private fun completion(reason: String = "stop", reasoning: String = "") = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", reason).put("message", JSONObject().put("content", scene().toString()).put("reasoning_content", reasoning))))
    private fun rejects(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun directRequestKeepsVisionFastModelAndNonThinking() {
        val body = SceneProtocol.request("test-image", false)
        assertEquals("deepseek-flash", body.getString("model"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(body.getBoolean("stream")); assertEquals(240, body.getInt("max_tokens"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        val content = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("data:image/jpeg;base64,test-image", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertTrue(content.getJSONObject(0).getString("text").contains("40到60"))
    }
    @Test fun detailUsesCurrentImageAndExistingLengthGuidance() {
        val body = SceneProtocol.request("new-image", true)
        assertEquals(420, body.getInt("max_tokens"))
        val content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue(content.getJSONObject(0).getString("text").contains("100到160"))
        assertTrue(content.getJSONObject(1).toString().contains("new-image"))
    }
    @Test fun directAndProxySceneFormattingIsIdentical() {
        assertEquals(SceneProtocol.text(scene(), true), SceneProtocol.text(SceneProtocol.directScene(completion()), true))
        assertEquals("近处左侧是椅子。", SceneProtocol.text(scene(), false))
        assertEquals("近处左侧是椅子。稍远处是办公桌。", SceneProtocol.text(scene(), true))
    }
    @Test fun truncatedOrThinkingResponsesAreRejected() {
        rejects { SceneProtocol.directScene(completion("length")) }
        rejects { SceneProtocol.directScene(completion(reasoning = "hidden reasoning")) }
        rejects { SceneProtocol.directScene(JSONObject().put("choices", JSONArray())) }
    }
    @Test fun fabricatedDistanceAndNavigationAreRejectedEvenInUnspokenDetails() {
        for (text in listOf("前方2米是门", "向左走", "可以通行", "距离30cm")) {
            rejects { SceneProtocol.text(scene().put("details", JSONArray().put(text)), false) }
        }
    }
    @Test fun malformedAndOversizedSceneFieldsAreRejected() {
        rejects { SceneProtocol.text(scene().put("extra", true), true) }
        rejects { SceneProtocol.text(scene().put("summary", ""), true) }
        rejects { SceneProtocol.text(scene().put("summary", "长".repeat(81)), true) }
        rejects { SceneProtocol.text(scene().put("details", JSONArray().put(1)), true) }
        rejects { SceneProtocol.text(scene().put("uncertainty", "\n"), true) }
    }
}
