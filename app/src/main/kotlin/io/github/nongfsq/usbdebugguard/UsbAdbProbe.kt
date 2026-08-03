package io.github.nongfsq.usbdebugguard

import android.content.Context
import android.content.IntentFilter
import android.provider.Settings

data class UsbAdbState(
    val usbConnected: Boolean,
    val adbEnabled: Boolean,
)

fun interface UsbAdbStateReader {
    fun read(): UsbAdbState
}

class AndroidUsbAdbStateReader(private val context: Context) : UsbAdbStateReader {
    override fun read(): UsbAdbState {
        val usbState = context.registerReceiver(null, IntentFilter(ACTION_USB_STATE))
        val usbConnected = usbState?.getBooleanExtra(EXTRA_USB_CONNECTED, false) == true
        val adbBySettings = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
        val adbByUsbState = usbState?.getBooleanExtra(EXTRA_ADB, false) == true
        return UsbAdbState(
            usbConnected = usbConnected,
            adbEnabled = adbBySettings || adbByUsbState,
        )
    }

    companion object {
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val EXTRA_USB_CONNECTED = "connected"
        private const val EXTRA_ADB = "adb"
    }
}

object UsbAdbProbe {
    fun read(context: Context): UsbAdbState = AndroidUsbAdbStateReader(context).read()
}

fun String.firstLine(): String = lineSequence().firstOrNull()?.trim().orEmpty()
