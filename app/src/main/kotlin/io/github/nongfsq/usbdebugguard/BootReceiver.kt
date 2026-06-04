package io.github.nongfsq.usbdebugguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!GuardPrefs.serviceEnabled(context)) return
        val service = Intent(context, GuardService::class.java).setAction(GuardService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
