package com.helios.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandIntentParserTest {
    @Test fun parsesCoreCommands() {
        assertEquals(VoiceIntent.QUERY_STATUS, CommandIntentParser.parse("当前状态。"))
        assertEquals(VoiceIntent.PAUSE_ASSISTANCE, CommandIntentParser.parse("暂停辅助"))
        assertEquals(VoiceIntent.RESUME_ASSISTANCE, CommandIntentParser.parse("恢复辅助"))
        assertEquals(VoiceIntent.STOP_ASSISTANCE, CommandIntentParser.parse("退出辅助"))
        assertEquals(VoiceIntent.STOP_ASSISTANCE, CommandIntentParser.parse("退出"))
    }

    @Test fun routesSceneAndFind() {
        assertEquals(VoiceIntent.DESCRIBE_SCENE, CommandIntentParser.parse("看看周围"))
        assertEquals(VoiceIntent.FIND_OBJECT, CommandIntentParser.parse("帮我找钥匙"))
    }

    @Test fun exitConfirmationUsesRestrictedVocabulary() {
        assertEquals(VoiceIntent.CONFIRM_STOP, CommandIntentParser.parse("确认退出", confirmingExit = true))
        assertEquals(VoiceIntent.CANCEL_CONFIRMATION, CommandIntentParser.parse("取消", confirmingExit = true))
        assertEquals(VoiceIntent.UNKNOWN, CommandIntentParser.parse("当前状态", confirmingExit = true))
    }
    @Test fun finishFindingIsNotMistakenForANewTargetNamedDaoLe() {
        assertEquals(VoiceIntent.FINISH_FIND,CommandIntentParser.parse("找到了"))
        assertEquals(VoiceIntent.FINISH_FIND,CommandIntentParser.parse("我找到了"))
        assertEquals(VoiceIntent.CANCEL_TASK,CommandIntentParser.parse("不找了"))
    }

}
