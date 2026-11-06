package com.helios.app

object StartupCheckPolicy {
    fun failure(cameraPresent:Boolean,device:DeviceState,detectorFailed:Boolean,sensorsReady:Boolean,
        voiceReady:Boolean,elapsedMs:Long):String? = when {
        !cameraPresent -> "未连接胸前相机，请连接后重新启动"
        device==DeviceState.ERROR -> "相机初始化失败，请检查连接或USB授权"
        detectorFailed -> "目标检测初始化失败，请在更多中查看日志"
        elapsedMs>=15_000 && !voiceReady -> "语音初始化超时，请重新启动"
        elapsedMs>=15_000 && !sensorsReady -> "设备数据检查未通过，请检查连接或USB授权后重新启动"
        else -> null
    }
}
