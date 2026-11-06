package com.helios.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat

/** Only AssistanceService owns this reference. UI consumers may inspect, never construct a camera. */
object SensorRuntime {
    @Volatile var camera: GeminiCamera? = null
        internal set
}

class SensorSessionController(private val context: Context, private val onRisk: (Intent)->Unit) : AutoCloseable {
    private val usb=context.getSystemService(UsbManager::class.java)
    private val action="${context.packageName}.USB_CAMERA_PERMISSION"
    @Volatile private var closed=false
    @Volatile var cameraPresent=false
        private set
    private fun refreshPresence() {cameraPresent=usb.deviceList.values.any {it.vendorId==0x2bc5 && it.productId==0x0670}}
    val camera=GeminiCamera(context,::requestPermission) { risk ->
        if(!closed) onRisk(Intent().setAction(AssistanceService.ACTION_LOCAL_RISK)
            .putExtra(AssistanceService.EXTRA_RISK_ID,risk.id)
            .putExtra(AssistanceService.EXTRA_RISK_KEY,risk.key)
            .putExtra(AssistanceService.EXTRA_RISK_LEVEL,risk.level.name)
            .putExtra(AssistanceService.EXTRA_RISK_CLASS_ID,risk.classId)
            .putExtra(AssistanceService.EXTRA_RISK_METERS,risk.distanceMeters)
            .putExtra(AssistanceService.EXTRA_RISK_SPEED,risk.closingMetersPerSecond)
            .putExtra(AssistanceService.EXTRA_RISK_KIND,risk.kind.name)
            .putExtra(AssistanceService.EXTRA_RISK_DIRECTION,risk.avoidanceDirection?.name)
            .putExtra(AssistanceService.EXTRA_RISK_REMEMBERED,risk.rememberedDirection)
            .putExtra(AssistanceService.EXTRA_RISK_OBSERVATION_ONLY,risk.observationOnly)
            .putExtra(AssistanceService.EXTRA_RISK_HEIGHT_BAND,risk.heightBand.name)
            .putExtra(AssistanceService.EXTRA_RISK_SPACE,risk.spaceHint?.name)
            .putExtra(AssistanceService.EXTRA_RISK_OBSERVED,risk.observedMs)
            .putExtra(AssistanceService.EXTRA_RISK_EXPIRES,risk.expiresMs))
    }
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(closed) return
            refreshPresence()
            if(intent.action==action) {
                val device=androidx.core.content.IntentCompat.getParcelableExtra(intent,UsbManager.EXTRA_DEVICE,UsbDevice::class.java) ?: return
                val live=usb.deviceList[device.deviceName] ?: return
                if(live.deviceId!=device.deviceId || live.vendorId!=0x2bc5 || live.productId!=0x0670) return
                camera.permissionResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) && usb.hasPermission(live))
            } else camera.usbChanged()
        }
    }
    init {
        check(SensorRuntime.camera==null) { "A sensor session already exists" }
        ContextCompat.registerReceiver(context,receiver,IntentFilter().apply {
            addAction(action);addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        },ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshPresence()
        SensorRuntime.camera=camera
    }
    fun start() { if(!closed) camera.start() }
    private fun requestPermission(device:UsbDevice) {
        if(closed) return
        val intent=Intent(action).setPackage(context.packageName)
        usb.requestPermission(device,PendingIntent.getBroadcast(context,device.deviceId,intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
    }
    override fun close() {
        if(closed) return
        closed=true
        context.unregisterReceiver(receiver)
        if(SensorRuntime.camera===camera) SensorRuntime.camera=null
        camera.previewEnabled=false
        camera.dispose()
    }
}
