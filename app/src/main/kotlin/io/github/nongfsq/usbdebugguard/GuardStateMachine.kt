package io.github.nongfsq.usbdebugguard

import android.content.Context

data class GuardReport(
    val status: GuardStatus,
    val probe: UsbAdbState,
    val result: RootResult? = null,
)

class GuardStateMachine(private val context: Context) {
    private var repairTick = 0

    fun refresh(): GuardReport {
        val probe = UsbAdbProbe.read(context)
        val status = computeStatus(probe)
        GuardPrefs.setLastStatus(context, status)
        return GuardReport(status, probe)
    }

    fun tick(forceRepair: Boolean = false): GuardReport {
        val probe = UsbAdbProbe.read(context)
        val serviceEnabled = GuardPrefs.serviceEnabled(context)
        val guarded = GuardPrefs.isGuarded(context)

        if (!serviceEnabled) {
            if (guarded) return restore(lockAfterRestore = false, probe = probe)
            return remember(GuardStatus.Idle, probe)
        }

        if (!probe.rootReady) {
            return remember(GuardStatus.RootRequired, probe)
        }

        if (!probe.usbConnected) {
            if (guarded) return restore(lockAfterRestore = GuardPrefs.lockOnDisconnect(context), probe = probe)
            return remember(GuardStatus.WaitingUsb, probe)
        }

        if (GuardPrefs.requireAdb(context) && !probe.adbEnabled) {
            if (guarded) return restore(lockAfterRestore = false, probe = probe)
            return remember(GuardStatus.WaitingAdb, probe)
        }

        if (!guarded) {
            val snapshot = captureSnapshot()
            GuardPrefs.saveSnapshot(context, snapshot)
            return applyGuard(probe = probe, forceScreenAction = true)
        }

        repairTick += 1
        val shouldRepair = forceRepair || repairTick >= 20
        return if (shouldRepair) {
            repairTick = 0
            applyGuard(probe = probe, forceScreenAction = false)
        } else {
            remember(GuardStatus.Protected, probe)
        }
    }

    fun stop(): GuardReport {
        GuardPrefs.setServiceEnabled(context, false)
        val probe = UsbAdbProbe.read(context)
        return if (GuardPrefs.isGuarded(context)) {
            restore(lockAfterRestore = false, probe = probe)
        } else {
            remember(GuardStatus.Idle, probe)
        }
    }

    fun lockNow(): RootResult = RootShell.sh("input keyevent KEYCODE_SLEEP || input keyevent KEYCODE_POWER").also {
        GuardPrefs.setLastAction(context, "lock:${it.code}")
    }

    fun testRoot(): RootResult = RootShell.sh("id && settings get global adb_enabled").also {
        GuardPrefs.setLastAction(context, "test-root:${it.code}")
    }

    private fun computeStatus(probe: UsbAdbState): GuardStatus = when {
        GuardPrefs.isGuarded(context) -> GuardStatus.Protected
        !GuardPrefs.serviceEnabled(context) -> GuardStatus.Idle
        !probe.rootReady -> GuardStatus.RootRequired
        !probe.usbConnected -> GuardStatus.WaitingUsb
        GuardPrefs.requireAdb(context) && !probe.adbEnabled -> GuardStatus.WaitingAdb
        else -> GuardStatus.Unknown
    }

    private fun captureSnapshot(): DisplaySettingsSnapshot = DisplaySettingsSnapshot(
        readSetting("settings get global stay_on_while_plugged_in"),
        readSetting("settings get system screen_brightness_mode"),
        readSetting("settings get system screen_brightness"),
        readSetting("settings get system screen_off_timeout"),
    )

    private fun readSetting(command: String): String {
        val value = RootShell.sh(command).stdout.firstLine()
        return value.ifBlank { "0" }
    }

    private fun applyGuard(probe: UsbAdbState, forceScreenAction: Boolean): GuardReport {
        val brightness = GuardPrefs.guardedBrightness(context)
        val mode = GuardPrefs.guardMode(context)
        val command = buildList {
            add("settings put system screen_brightness_mode 0")
            add("settings put system screen_brightness $brightness")
            if (mode == GuardMode.DimAwake) {
                add("settings put global stay_on_while_plugged_in 2")
                add("settings put system screen_off_timeout 2147483647")
                if (forceScreenAction) add("input keyevent KEYCODE_WAKEUP")
                if (GuardPrefs.dismissKeyguard(context)) add("wm dismiss-keyguard")
            } else {
                add("settings put global stay_on_while_plugged_in 0")
                add("settings put system screen_off_timeout 5000")
                if (forceScreenAction) add("input keyevent KEYCODE_SLEEP")
            }
        }.joinToString("; ")
        val result = RootShell.sh(command)
        if (result.ok) {
            GuardPrefs.setGuarded(context, true)
            GuardPrefs.setLastAction(context, "guard:${mode.value}:${result.code}")
            return remember(GuardStatus.Protected, probe, result)
        }
        GuardPrefs.setLastAction(context, "guard-failed:${result.code}")
        return remember(GuardStatus.RootRequired, probe, result)
    }

    private fun restore(lockAfterRestore: Boolean, probe: UsbAdbState): GuardReport {
        val snapshot = GuardPrefs.loadSnapshot(context)
        val result = RootShell.sh(snapshot.restoreCommand())
        if (result.ok) {
            GuardPrefs.setGuarded(context, false)
            if (lockAfterRestore) lockNow()
            GuardPrefs.setLastAction(context, "restore:${result.code}")
            return remember(if (GuardPrefs.serviceEnabled(context)) GuardStatus.WaitingUsb else GuardStatus.Idle, probe, result)
        }
        GuardPrefs.setLastAction(context, "restore-failed:${result.code}")
        return remember(GuardStatus.RestoreFailed, probe, result)
    }

    private fun remember(status: GuardStatus, probe: UsbAdbState, result: RootResult? = null): GuardReport {
        GuardPrefs.setLastStatus(context, status)
        return GuardReport(status, probe, result)
    }
}
