package com.helios.app

/** Only applied after activation; startup and deliberate recalibration have their own gate. */
object RuntimeHealthPolicy {
    fun failure(device:DeviceState,agesMs:List<Long>,detectorFailed:Boolean,calibrated:Boolean,
        microphoneGranted:Boolean=true,cameraGranted:Boolean=true):String? {
        if(!microphoneGranted) return "麦克风权限失效"
        if(!cameraGranted) return "相机权限失效"
        if(device==DeviceState.DISCONNECTED || device==DeviceState.CONNECTING) return "相机连接中断"
        if(device==DeviceState.ERROR) return "相机发生异常"
        if(detectorFailed) return "目标检测发生异常"
        val names=listOf("图像数据","深度数据","加速度数据","陀螺仪数据")
        if(agesMs.size!=4) return "传感器状态异常"
        agesMs.forEachIndexed { i,age -> if(age<0 || age>=2_000) return "${names[i]}中断" }
        if(!calibrated) return "佩戴校准已失效"
        return null
    }
}
