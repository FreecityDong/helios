package com.helios.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler

/** v2.0.6's Java USB watcher predates target-35 receiver flags. Keep its native ABI unchanged. */
class OrbbecAndroidContext(base: Context) : ContextWrapper(base) {
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
        if (Build.VERSION.SDK_INT >= 33) baseContext.registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else baseContext.registerReceiver(receiver, filter)

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, permission: String?, scheduler: Handler?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) baseContext.registerReceiver(receiver, filter, permission, scheduler, RECEIVER_NOT_EXPORTED)
        else baseContext.registerReceiver(receiver, filter, permission, scheduler)
}
