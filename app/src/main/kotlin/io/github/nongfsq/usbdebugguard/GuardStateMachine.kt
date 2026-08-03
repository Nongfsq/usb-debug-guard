package io.github.nongfsq.usbdebugguard

import android.content.Context

data class GuardReport(
    val status: GuardStatus,
    val probe: UsbAdbState,
    val result: RootResult? = null,
    val circuit: RootCircuitSnapshot = RootCircuitSnapshot(),
)

class GuardStateMachine(
    private val settings: GuardSettings,
    private val probeReader: UsbAdbStateReader,
    private val root: RootCommandRunner,
) {
    constructor(context: Context) : this(
        settings = AndroidGuardSettings(context),
        probeReader = AndroidUsbAdbStateReader(context),
        root = RootRuntime.get(context),
    )

    fun refresh(): GuardReport {
        val probe = probeReader.read()
        return GuardReport(computeStatus(probe), probe, circuit = root.circuit())
    }

    fun tick(forceRepair: Boolean = false): GuardReport {
        val probe = probeReader.read()
        val guarded = settings.guarded()
        val snapshotPending = settings.snapshotPending()

        if (settings.stopPending()) {
            return if (snapshotPending || guarded) {
                restore(lockAfterRestore = false, probe = probe)
            } else {
                completeStop(probe)
            }
        }

        if (!settings.serviceEnabled()) {
            if (snapshotPending || guarded) return restore(lockAfterRestore = false, probe = probe)
            return remember(GuardStatus.Idle, probe)
        }

        root.circuit().takeIf { it.open }?.let {
            return remember(statusForCircuit(it), probe)
        }

        if (snapshotPending && settings.guardMutationStarted() && !guarded) {
            return restore(lockAfterRestore = false, probe = probe)
        }

        if (!probe.usbConnected) {
            if (snapshotPending || guarded) {
                return restore(lockAfterRestore = settings.lockOnDisconnect(), probe = probe)
            }
            return remember(GuardStatus.WaitingUsb, probe)
        }

        if (settings.requireAdb() && !probe.adbEnabled) {
            if (snapshotPending || guarded) return restore(lockAfterRestore = false, probe = probe)
            return remember(GuardStatus.WaitingAdb, probe)
        }

        if (!guarded) {
            if (!snapshotPending) {
                val capture = captureSnapshot()
                if (!capture.ok) return remember(statusForResult(capture), probe, capture)
            }
            return applyGuard(probe = probe, forceScreenAction = true)
        }

        return if (forceRepair) {
            applyGuard(probe = probe, forceScreenAction = false)
        } else {
            remember(GuardStatus.Protected, probe)
        }
    }

    fun stop(): GuardReport {
        settings.setStopPending(true)
        val probe = probeReader.read()
        return if (settings.snapshotPending() || settings.guarded()) {
            restore(lockAfterRestore = false, probe = probe)
        } else {
            completeStop(probe)
        }
    }

    fun retryRoot(): GuardReport {
        val probe = probeReader.read()
        val result = root.run(
            category = RootCommandCategory.Test,
            command = "id",
            manualRetry = true,
        )
        settings.setLastAction("retry-root:${result.outcome.value}:${result.code}")
        val status = if (result.ok) computeStatus(probe) else statusForResult(result)
        return remember(status, probe, result)
    }

    fun lockNow(): GuardReport {
        val probe = probeReader.read()
        val result = runLockCommand()
        return remember(if (result.ok) computeStatus(probe) else statusForResult(result), probe, result)
    }

    private fun computeStatus(probe: UsbAdbState): GuardStatus {
        val circuit = root.circuit()
        return when {
            circuit.open -> statusForCircuit(circuit)
            settings.guarded() -> GuardStatus.Protected
            !settings.serviceEnabled() -> GuardStatus.Idle
            !probe.usbConnected -> GuardStatus.WaitingUsb
            settings.requireAdb() && !probe.adbEnabled -> GuardStatus.WaitingAdb
            else -> GuardStatus.Unknown
        }
    }

    private fun captureSnapshot(): RootResult {
        val result = root.run(
            category = RootCommandCategory.CaptureSettings,
            command = DisplaySettingsSnapshot.captureCommand,
            outputValidator = { DisplaySettingsSnapshot.fromProtocol(it) != null },
        )
        if (result.ok) {
            settings.saveSnapshot(requireNotNull(DisplaySettingsSnapshot.fromProtocol(result.stdout)))
        }
        return result
    }

    private fun applyGuard(probe: UsbAdbState, forceScreenAction: Boolean): GuardReport {
        val brightness = settings.guardedBrightness()
        val mode = settings.guardMode()
        val command = buildList {
            add("settings put system screen_brightness_mode 0")
            add("settings put system screen_brightness $brightness")
            if (mode == GuardMode.DimAwake) {
                add("settings put global stay_on_while_plugged_in 2")
                add("settings put system screen_off_timeout 2147483647")
                if (forceScreenAction) add("input keyevent KEYCODE_WAKEUP")
                if (settings.dismissKeyguard()) add("wm dismiss-keyguard")
            } else {
                add("settings put global stay_on_while_plugged_in 0")
                add("settings put system screen_off_timeout 5000")
                if (forceScreenAction) add("input keyevent KEYCODE_SLEEP")
            }
        }.joinToString(" && ")
        settings.markGuardMutationStarted()
        val result = root.run(RootCommandCategory.ApplyGuard, command)
        if (result.ok) {
            settings.setGuarded(true)
            settings.setLastAction("guard:${mode.value}:${result.code}")
            return remember(GuardStatus.Protected, probe, result)
        }
        settings.setLastAction("guard-failed:${result.outcome.value}:${result.code}")
        return remember(statusForResult(result), probe, result)
    }

    private fun restore(lockAfterRestore: Boolean, probe: UsbAdbState): GuardReport {
        val result = root.run(RootCommandCategory.RestoreSettings, settings.loadSnapshot().restoreCommand())
        if (!result.ok) {
            settings.setLastAction("restore-failed:${result.outcome.value}:${result.code}")
            return remember(statusForResult(result, restore = true), probe, result)
        }

        settings.setGuarded(false)
        settings.clearSnapshot()
        settings.setLastAction("restore:${result.code}")
        if (settings.stopPending()) {
            settings.setServiceEnabled(false)
            settings.setStopPending(false)
        }
        if (lockAfterRestore) {
            val lock = runLockCommand()
            if (!lock.ok) return remember(statusForResult(lock), probe, lock)
        }
        val status = if (settings.serviceEnabled()) GuardStatus.WaitingUsb else GuardStatus.Idle
        return remember(status, probe, result)
    }

    private fun completeStop(probe: UsbAdbState): GuardReport {
        settings.setServiceEnabled(false)
        settings.setStopPending(false)
        return remember(GuardStatus.Idle, probe)
    }

    private fun runLockCommand(): RootResult = root.run(
        RootCommandCategory.LockScreen,
        "input keyevent KEYCODE_SLEEP || input keyevent KEYCODE_POWER",
    ).also {
        settings.setLastAction("lock:${it.outcome.value}:${it.code}")
    }

    private fun statusForResult(result: RootResult, restore: Boolean = false): GuardStatus {
        if (restore && result.outcome != RootOutcome.Success) return GuardStatus.RestoreFailed
        return when (result.outcome) {
        RootOutcome.Denied -> GuardStatus.RootRequired
        RootOutcome.Timeout,
        RootOutcome.DestroyFailed,
        RootOutcome.CircuitOpen -> GuardStatus.CircuitOpen
        RootOutcome.Exception,
        RootOutcome.Cancelled -> GuardStatus.RootUnavailable
        RootOutcome.Success -> GuardStatus.Unknown
        }
    }

    private fun statusForCircuit(circuit: RootCircuitSnapshot): GuardStatus = when (circuit.reason) {
        RootOutcome.Denied -> GuardStatus.RootRequired
        RootOutcome.Timeout,
        RootOutcome.DestroyFailed -> GuardStatus.CircuitOpen
        else -> GuardStatus.RootUnavailable
    }

    private fun remember(status: GuardStatus, probe: UsbAdbState, result: RootResult? = null): GuardReport {
        settings.setLastStatus(status)
        return GuardReport(status, probe, result, root.circuit())
    }
}
