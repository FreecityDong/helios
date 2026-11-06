package com.helios.app

enum class VoiceIntent {
    QUERY_STATUS,
    PAUSE_ASSISTANCE,
    RESUME_ASSISTANCE,
    STOP_ASSISTANCE,
    CONFIRM_STOP,
    CANCEL_CONFIRMATION,
    ASK_SCENE,
    DESCRIBE_SCENE,
    DESCRIBE_DETAIL,
    FIND_OBJECT,
    CONFIRM_FIND,
    RESUME_FIND,
    FINISH_FIND,
    CANCEL_TASK,
    UNKNOWN,
}

object CommandIntentParser {
    fun parse(rawText: String, confirmingExit: Boolean = false): VoiceIntent {
        val text = normalize(rawText)
        if (confirmingExit) {
            return when {
                text in setOf("确认退出", "确定退出", "退出", "确定", "是的") -> VoiceIntent.CONFIRM_STOP
                text in setOf("取消", "不退出", "继续") -> VoiceIntent.CANCEL_CONFIRMATION
                else -> VoiceIntent.UNKNOWN
            }
        }
        return when {
            text in setOf("当前状态", "查询状态", "现在什么状态", "状态") -> VoiceIntent.QUERY_STATUS
            text in setOf("暂停辅助", "暂停") -> VoiceIntent.PAUSE_ASSISTANCE
            text in setOf("恢复辅助", "恢复") -> VoiceIntent.RESUME_ASSISTANCE
            text in setOf("退出辅助", "关闭辅助", "停止辅助", "退出") -> VoiceIntent.STOP_ASSISTANCE
            text in setOf("看看周围", "我在哪里", "分析环境", "环境描述", "描述环境") -> VoiceIntent.DESCRIBE_SCENE
            text in setOf("再详细一点", "详细一点", "详细描述") -> VoiceIntent.DESCRIBE_DETAIL
            text in setOf("确认", "确认找", "是的", "对", "开始找") -> VoiceIntent.CONFIRM_FIND
            text in setOf("找到了", "我找到了", "结束找物", "结束查找", "不用找了") -> VoiceIntent.FINISH_FIND
            FindTarget.extract(text) != null -> VoiceIntent.FIND_OBJECT
            text in setOf("继续找", "继续找物") -> VoiceIntent.RESUME_FIND
            text in setOf("取消", "不找了", "取消找物", "不对", "不是", "不用了") -> VoiceIntent.CANCEL_TASK
            else -> VoiceIntent.UNKNOWN
        }
    }

    internal fun normalize(rawText: String): String = rawText
        .lowercase()
        .replace(Regex("[\\s，。！？、,.!?]"), "")
}
