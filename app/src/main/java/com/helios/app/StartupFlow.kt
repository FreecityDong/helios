package com.helios.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StartupPhase { OFF, INITIALIZING, WAITING_DEVICE, CALIBRATING, READY, ERROR }
data class StartupStatus(val phase: StartupPhase = StartupPhase.OFF, val message: String = "请佩戴相机后启动辅助")
object StartupStatusStore {
    private val mutable = MutableStateFlow(StartupStatus())
    val status = mutable.asStateFlow()
    fun update(value: StartupStatus) { mutable.value=value }
}

/** One flow per service startup. Calibration is issued once, never restarted by polling. */
class StartupFlow {
    var started=false; private set
    var calibrationRequested=false; private set
    var calibrationPromptPending=false; private set
    var readyOnce=false; private set
    private var generation=0L
    private var failure:String?=null
    private var lastReminderMs:Long?=null
    private var reminderCount=0
    fun start(): Long {
        generation++;started=true;calibrationRequested=false;calibrationPromptPending=false;readyOnce=false;failure=null;lastReminderMs=null;reminderCount=0
        return generation
    }
    fun stop() { generation++;started=false;calibrationRequested=false;calibrationPromptPending=false }
    fun observe(voiceReady: Boolean, sensorsReady: Boolean, calibrated: Boolean, still: Boolean,
        error: String?=null, deviceMessage: String="正在连接相机并等待有效数据"): StartupStatus {
        if(!started) return StartupStatus()
        if(error!=null && failure==null) {
            failure=error;generation++;readyOnce=false;calibrationRequested=false;calibrationPromptPending=false
        }
        failure?.let {return StartupStatus(StartupPhase.ERROR,"检查未通过，辅助未启用。$it")}
        if(!voiceReady) {
            if(readyOnce || calibrationPromptPending) {generation++;readyOnce=false;calibrationPromptPending=false;calibrationRequested=false}
            return StartupStatus(StartupPhase.INITIALIZING,"正在检查语音和设备，辅助尚未启用")
        }
        if(!sensorsReady) {
            // A disconnect invalidates an in-progress prompt and its eventual callback.
            if(calibrationRequested || calibrationPromptPending) { generation++;calibrationRequested=false;calibrationPromptPending=false }
            readyOnce=false
            return StartupStatus(StartupPhase.WAITING_DEVICE,"辅助未启用。$deviceMessage")
        }
        if(!calibrationRequested || !calibrated) {
            if(readyOnce) { generation++;calibrationRequested=false;readyOnce=false }
            return StartupStatus(StartupPhase.CALIBRATING,"请将相机固定在胸前，保持静止，等待校准")
        }
        if(!readyOnce && !still) return StartupStatus(StartupPhase.CALIBRATING,"校准已完成，请继续停稳，正在确认状态")
        readyOnce=true
        return StartupStatus(StartupPhase.READY,"辅助运行中")
    }
    fun beginCalibrationPrompt(): Long? {
        if(!started || failure!=null || calibrationRequested || calibrationPromptPending) return null
        calibrationPromptPending=true
        return generation
    }
    fun acceptCalibrationPrompt(token: Long, nowMs:Long?=null): Boolean {
        if(!started || token!=generation || !calibrationPromptPending) return false
        calibrationPromptPending=false;calibrationRequested=true
        lastReminderMs=nowMs;reminderCount=0
        return true
    }
    fun takeReminder(nowMs:Long,speechBusy:Boolean):Boolean {
        if(!started || failure!=null || !calibrationRequested || readyOnce || speechBusy) return false
        val last=lastReminderMs ?: return false
        val delay=if(reminderCount==0) 10_000L else 15_000L
        if(nowMs-last<delay) return false
        lastReminderMs=nowMs;reminderCount++
        return true
    }
    fun activationToken(): Long? = if(started && readyOnce) generation else null
    fun mayActivate(token: Long): Boolean = started && failure==null && readyOnce && token==generation
    fun retryCalibration() { generation++;calibrationRequested=false;calibrationPromptPending=false;readyOnce=false }
}
