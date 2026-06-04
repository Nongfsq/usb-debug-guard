package io.github.nongfsq.usbdebugguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings

data class UsbAdbState(
    val usbConnected: Boolean,
    val adbEnabled: Boolean,
    val rootReady: Boolean,
)

object UsbAdbProbe {
    fun read(context: Context): UsbAdbState {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val frameworkUsb = (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0
        val usb = RootShell.sh(
            "cat /sys/class/power_supply/usb/online 2>/dev/null || " +
                "cat /sys/class/power_supply/usb/present 2>/dev/null || echo 0"
        )
        val adb = RootShell.sh("settings get global adb_enabled 2>/dev/null || echo 0")
        val adbByApi = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.ADB_ENABLED) == "1"
        }.getOrDefault(false)
        return UsbAdbState(
            usbConnected = frameworkUsb || usb.stdout.firstLine() == "1",
            adbEnabled = adb.stdout.firstLine() == "1" || adbByApi,
            rootReady = usb.ok || adb.ok,
        )
    }
}

fun String.firstLine(): String = lineSequence().firstOrNull()?.trim().orEmpty()
