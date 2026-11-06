package com.helios.app

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat

/** Compatibility USB entry only. All display and sensor ownership live in MainActivity/Service. */
class CameraDebugActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent.action==UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val attached=IntentCompat.getParcelableExtra(intent,UsbManager.EXTRA_DEVICE,UsbDevice::class.java)
            val live=attached?.let { getSystemService(UsbManager::class.java).deviceList[it.deviceName] }
            if(live==null || live.deviceId!=attached.deviceId || live.vendorId!=0x2bc5 || live.productId!=0x0670) {finish();return}
        }
        startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
