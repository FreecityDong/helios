package com.helios.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

class AudioRouteController(private val context: Context, private val log: EventLog) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var selected: AudioDeviceInfo? = null
    private var selectedAt = Long.MIN_VALUE
    private var keeper: BluetoothAudioKeeper? = null
    private var ownsMode=false
    private fun bluetooth(device: AudioDeviceInfo?) = device?.type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun select(device: AudioDeviceInfo): Boolean {
        // Retain the session's route across microphone/TTS handoffs. Re-selecting an
        // already requested SCO device can restart the headset's audio transport.
        if (selected?.id == device.id && (!bluetooth(device) || keeper?.alive==true)) return true
        keeper?.close();keeper=null
        if (bluetooth(device) && audioManager.mode==AudioManager.MODE_NORMAL) {
            audioManager.mode=AudioManager.MODE_IN_COMMUNICATION;ownsMode=true
        }
        if (!audioManager.setCommunicationDevice(device)) { releaseAllRoutes();return false }
        selected = device
        selectedAt = SystemClock.elapsedRealtime()
        if (bluetooth(device)) {
            runCatching {BluetoothAudioKeeper(device)}.onSuccess {keeper=it
                log.append("bluetooth_audio_keeper_started",mapOf("deviceType" to device.type,"mode" to audioManager.mode))
            }.onFailure {log.append("bluetooth_audio_keeper_error",mapOf("type" to it.javaClass.simpleName))}
        }
        return true
    }

    fun routeInput(): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val device = audioManager.availableCommunicationDevices.firstOrNull(::bluetooth)
            if (device != null && select(device)) return if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                "蓝牙 LE 耳机麦克风" else "蓝牙 SCO 耳机麦克风"
        }
        releaseAllRoutes()
        return "系统默认麦克风"
    }

    fun routeHelpSpeaker(): String {
        val speaker = audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return if (speaker != null && select(speaker)) "已请求手机扬声器" else "外放路由失败，系统默认输出"
    }

    fun speechUsage(): Int {
        if (selected != null && audioManager.availableCommunicationDevices.none { it.id == selected?.id }) releaseAllRoutes()
        return if (selected != null) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
    }
    fun promptStream() = if (bluetooth(selected)) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
    fun playbackSettleMs(): Long {
        val device=selected ?: return 0
        val elapsed=SystemClock.elapsedRealtime()-selectedAt
        if(!bluetooth(device)) return (350L-elapsed).coerceIn(0,350)
        val ready=audioManager.communicationDevice?.id==device.id && keeper?.ready(device.id)==true
        return PlaybackReadiness.waitMs(elapsed,ready)
    }
    fun diagnostics(): Map<String,Any> = mapOf("mode" to audioManager.mode,
        "requestedType" to (selected?.type ?: -1),"actualType" to (audioManager.communicationDevice?.type ?: -1),
        "keeperAlive" to (keeper?.alive==true),"keeperReady" to (selected?.let {keeper?.ready(it.id)}==true))

    fun releaseAllRoutes() {
        keeper?.close();keeper=null
        audioManager.clearCommunicationDevice()
        if(ownsMode && audioManager.mode==AudioManager.MODE_IN_COMMUNICATION) audioManager.mode=AudioManager.MODE_NORMAL
        ownsMode=false
        selected = null
        selectedAt = Long.MIN_VALUE
    }
}
