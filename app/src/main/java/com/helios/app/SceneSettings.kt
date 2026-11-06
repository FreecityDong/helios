package com.helios.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

fun showSceneSettings(activity: Activity) {
    val current = SceneConfig.read(activity)
    val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 12) }
    layout.addView(TextView(activity).apply { text = "可在任意运动状态下唤醒并自由提问，例如“杯子是什么颜色”。主动提问的回答结束后有提示音，8秒内可直接追问；自动简述播完直接恢复唤醒监听；运动中先播“请先停稳”，停稳后自动上传；分析或播报期间再进入运动仍会打断旧任务。每轮向 DeepSeek 上传一张新画面，不附带历史对话，可选择手机直接联网或通过环境服务。图片不写入本机文件；网络失败不影响本地避障和跌倒流程。自动简述仅在稳定静止后上传一次。" })
    val direct = CheckBox(activity).apply { text = "手机直接连接 DeepSeek"; isChecked = current.direct || !current.enabled }
    val url = EditText(activity).apply { hint = "环境服务 HTTPS 地址"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setText(current.url) }
    val token = EditText(activity).apply { hint = "环境服务访问令牌（不是 DeepSeek 密钥）"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(current.token) }
    val enabled = CheckBox(activity).apply { text = "允许上传单帧并启用环境理解"; isChecked = current.enabled }
    val automatic = CheckBox(activity).apply { text = "稳定停下后自动简述一次"; isChecked = current.automatic }
    val testMode = CheckBox(activity).apply { text = "环境理解独立测试：忽略运动校准，暂停避障语音"; isChecked = current.testMode }
    val debug = activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    layout.addView(direct); layout.addView(url); layout.addView(token);
    fun updateConnectionFields() {
        url.visibility = if (direct.isChecked) android.view.View.GONE else android.view.View.VISIBLE
        token.hint = if (direct.isChecked) "DeepSeek API 密钥（仅保存在本机）" else "环境服务访问令牌"
    }
    if (direct.isChecked && !current.direct) token.setText("")
    updateConnectionFields()
    direct.setOnCheckedChangeListener { _, _ -> token.setText(""); updateConnectionFields() }
     layout.addView(enabled); layout.addView(automatic)
    if (debug) layout.addView(testMode)
    val dialog = AlertDialog.Builder(activity).setTitle("云端环境理解").setView(layout).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val saved = runCatching { SceneConfig.save(activity, if (direct.isChecked) SceneProtocol.DIRECT_URL else url.text.toString(), token.text.toString(), enabled.isChecked, automatic.isChecked, debug && testMode.isChecked) }.isSuccess
            if (!saved) {
                Toast.makeText(activity, "请填写有效的密钥或服务地址与访问令牌", Toast.LENGTH_LONG).show()
            } else {
                SceneStatusStore.update(SceneStatus(if (enabled.isChecked) "环境理解已配置，请启动辅助并完成佩戴校准" else "环境理解已停用"))
                if (AssistanceStateStore.status.value.session in setOf(SessionState.ACTIVE, SessionState.PAUSED)) {
                    activity.startService(Intent(activity, AssistanceService::class.java).setAction(AssistanceService.ACTION_SCENE_CONFIG))
                }
                dialog.dismiss()
            }
        }
    }
    dialog.show()
}
