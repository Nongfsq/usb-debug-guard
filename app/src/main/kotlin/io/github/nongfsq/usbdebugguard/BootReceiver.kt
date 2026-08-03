package io.github.nongfsq.usbdebugguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val localizedContext = LocaleHelper.wrap(context)
        if (!GuardPrefs.serviceEnabled(localizedContext)) return
        val service = Intent(localizedContext, GuardService::class.java).setAction(GuardService.ACTION_START)
        localizedContext.startForegroundService(service)
    }
}
