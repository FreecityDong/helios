package com.helios.app

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Enumeration is evidence of presence only, never evidence of a working stream or route. */
data class DeviceBaseline(
    val phone: String,
    val system: String,
    val usbHost: Boolean,
    val usbDevices: List<String>,
    val audioInputs: List<String>,
    val audioOutputs: List<String>,
    val cameraStreams: String = "未接入：RGB、深度、IMU 均待阶段 3 验证",
    val audioRoute: String = "未验证：当前没有录音或播放会话",
)

class DeviceProbe(private val context: Context) {
    fun snapshot(): DeviceBaseline {
        val usb = context.getSystemService(UsbManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        return DeviceBaseline(
            phone = "${Build.MANUFACTURER} ${Build.MODEL}",
            system = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${Build.DISPLAY}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}",
            usbHost = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            usbDevices = usb.deviceList.values.sortedBy { it.deviceId }.map {
                // Do not collect serial numbers or claim that any Orbbec VID identifies Gemini 2.
                "VID 0x${it.vendorId.toString(16)} / PID 0x${it.productId.toString(16)} · " +
                    if (usb.hasPermission(it)) "已有 USB 授权；数据流未验证" else "尚未 USB 授权；数据流未验证"
            },
            audioInputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { audioLabel(it.type) }.distinct().sorted(),
            audioOutputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { audioLabel(it.type) }.distinct().sorted(),
        )
    }

    private fun audioLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦克风"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "手机听筒"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙媒体设备（A2DP）"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话设备（SCO）"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE 耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 音频设备"
        else -> "系统音频设备（类型 $type）"
    }
}
