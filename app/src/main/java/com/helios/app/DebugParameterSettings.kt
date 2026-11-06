package com.helios.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

internal object DebugParameterRepository {
    fun load(context:Context):DebugParameters = runCatching {
        val raw=context.getSharedPreferences("debug-parameters",Context.MODE_PRIVATE).getString("values",null)
            ?: return DebugParameters.defaults()
        val json=JSONObject(raw)
        DebugParameters.fromSaved(json.keys().asSequence().associateWith {json.getDouble(it)})
    }.getOrElse {
        EventLog(context).append("debug_parameters_invalid",mapOf("reason" to "validation_failed_using_defaults"))
        DebugParameters.defaults()
    }
    fun save(context:Context,parameters:DebugParameters) {
        check(context.getSharedPreferences("debug-parameters",Context.MODE_PRIVATE).edit()
            .putString("values",JSONObject(parameters.asMap()).toString()).commit()) {"保存失败，请重试"}
    }
}

internal fun showDebugParameterSettings(activity:Activity) {
    fun busy()=FallStatusStore.status.value.state in setOf(FallState.CONFIRMING,FallState.HELPING)
    fun apply(value:DebugParameters):Boolean {
        if(busy()) {
            AlertDialog.Builder(activity).setMessage("请先完成当前跌倒回应，再修改调试参数。").setPositiveButton("知道了",null).show();return false
        }
        return runCatching {
            DebugParameterRepository.save(activity,value)
            val session=AssistanceStateStore.status.value.session
            if(session in setOf(SessionState.ACTIVE,SessionState.PAUSED,SessionState.STARTING)) {
                activity.startService(Intent(activity,AssistanceService::class.java).setAction(AssistanceService.ACTION_DEBUG_PARAMETERS))
            } else DebugParameterRuntime.current=value
            EventLog(activity).append("debug_parameters_saved",mapOf("values" to value.asMap()))
            AlertDialog.Builder(activity).setMessage(if(session in setOf(SessionState.ACTIVE,SessionState.PAUSED,SessionState.STARTING))
                "参数已保存，正在应用。当前问答和找物观察将暂停，请停稳后重新发起或继续任务。" else "参数已保存，下次启动辅助时使用。")
                .setPositiveButton("知道了",null).show()
            true
        }.getOrElse {AlertDialog.Builder(activity).setMessage(it.message ?: "保存失败").setPositiveButton("知道了",null).show();false}
    }
    fun edit(group:String) {
        val original=DebugParameterRepository.load(activity)
        val fields=linkedMapOf<String,EditText>()
        val form=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(32,16,32,16)}
        form.addView(TextView(activity).apply {text="保存后应用到本机；敏感度调整仅改变对应门槛，仍需其他检测证据。"})
        val specs=ParameterCatalog.specs.filter {it.group==group}
        specs.forEach {spec->
            form.addView(TextView(activity).apply {text="\n${spec.label}（${spec.unit}）\n默认 ${spec.default} · 范围 ${spec.min}–${spec.max}"+(if(spec.hint.isBlank()) "" else "\n${spec.hint}")})
            val input=EditText(activity).apply {
                inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine();setText(original[spec.key].toString());contentDescription="${spec.label}，单位${spec.unit}"
            }
            fields[spec.key]=input;form.addView(input)
        }
        val dialog=AlertDialog.Builder(activity).setTitle(group).setView(ScrollView(activity).apply {addView(form)})
            .setPositiveButton("保存并应用",null).setNeutralButton("本组默认",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {specs.forEach {fields[it.key]?.setText(it.default.toString())}}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var invalid=false
                val patch=fields.mapValues {(_,input)->input.text.toString().trim().toDoubleOrNull().also {
                    if(it==null) {input.error="请输入有效数字";invalid=true}
                } ?: Double.NaN}
                if(!invalid) runCatching {original.patched(patch)}.onSuccess {if(apply(it)) dialog.dismiss()}
                    .onFailure {AlertDialog.Builder(activity).setMessage(it.message).setPositiveButton("返回修改",null).show()}
            }
        }
        dialog.show()
    }
    val choices=ParameterCatalog.groups+"恢复全部默认值"
    AlertDialog.Builder(activity).setTitle("调试参数").setItems(choices.toTypedArray()) {_,index->
        if(index<ParameterCatalog.groups.size) edit(choices[index])
        else AlertDialog.Builder(activity).setTitle("恢复全部默认值？").setMessage("将覆盖所有手动调试参数，应用中的任务会暂停。")
            .setPositiveButton("恢复并应用") {_,_->apply(DebugParameters.defaults())}.setNegativeButton("取消",null).show()
    }.setNegativeButton("关闭",null).show()
}
