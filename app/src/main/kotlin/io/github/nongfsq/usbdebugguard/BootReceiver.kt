package io.github.nongfsq.usbdebugguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val localizedContext = LocaleHelper.wrap(context)
        if (!GuardPrefs.serviceEnabled(localizedContext)) return
        val service = Intent(localizedContext, GuardService::class.java).setAction(GuardService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) {
            localizedContext.startForegroundService(service)
        } else {
            localizedContext.startService(service)
        }
    }
}
