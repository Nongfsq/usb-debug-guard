package io.github.nongfsq.usbdebugguard

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardStateMachineTest {
    @Test
    fun ordinaryUsbDisconnectedProbeNeverCallsRoot() {
        val settings = FakeGuardSettings(serviceEnabled = true)
        val root = QueueRootRunner()
        val machine = GuardStateMachine(settings, UsbAdbStateReader { UsbAdbState(false, false) }, root)

        val report = machine.tick()

        assertEquals(GuardStatus.WaitingUsb, report.status)
        assertEquals(0, root.calls)
    }

    @Test
    fun ordinaryAdbDisabledProbeNeverCallsRoot() {
        val settings = FakeGuardSettings(serviceEnabled = true, requireAdb = true)
        val root = QueueRootRunner()
        val machine = GuardStateMachine(settings, UsbAdbStateReader { UsbAdbState(true, false) }, root)

        val report = machine.tick()

        assertEquals(GuardStatus.WaitingAdb, report.status)
        assertEquals(0, root.calls)
    }

    @Test
    fun activeUsbCapturesSettingsInOneRequestThenAppliesGuard() {
        val settings = FakeGuardSettings(serviceEnabled = true)
        val root = QueueRootRunner(
            result(RootOutcome.Success, stdout = validSnapshotOutput()),
            result(RootOutcome.Success, category = RootCommandCategory.ApplyGuard),
        )
        val machine = GuardStateMachine(settings, UsbAdbStateReader { UsbAdbState(true, true) }, root)

        val report = machine.tick()

        assertEquals(GuardStatus.Protected, report.status)
        assertTrue(settings.isGuardedState)
        assertEquals(2, root.calls)
        assertEquals(
            listOf(RootCommandCategory.CaptureSettings, RootCommandCategory.ApplyGuard),
            root.categories,
        )
    }

    @Test
    fun oneHangPreventsEveryLaterAutomaticRootRequest() {
        val settings = FakeGuardSettings(serviceEnabled = true)
        val store = InMemoryRootSafetyStore()
        val executor = FakeRootExecutor {
            execution(it, RootOutcome.Timeout, code = -2, aliveAfterDestroy = false)
        }
        val controller = RootController(executor, store)
        val machine = GuardStateMachine(settings, UsbAdbStateReader { UsbAdbState(true, true) }, controller)

        assertEquals(GuardStatus.CircuitOpen, machine.tick().status)
        repeat(25) { assertEquals(GuardStatus.CircuitOpen, machine.tick(forceRepair = true).status) }

        assertEquals(1, executor.callCount.get())
    }

    @Test
    fun failedApplyKeepsOriginalSnapshotAndNextSafeRunRestoresBeforeRecapture() {
        val settings = FakeGuardSettings(serviceEnabled = true)
        val failedRoot = QueueRootRunner(
            result(RootOutcome.Success, stdout = validSnapshotOutput("7", "1", "177", "45000")),
            result(RootOutcome.Timeout, category = RootCommandCategory.ApplyGuard, code = -2),
        )
        val probe = UsbAdbStateReader { UsbAdbState(true, true) }

        assertEquals(GuardStatus.CircuitOpen, GuardStateMachine(settings, probe, failedRoot).tick().status)
        assertTrue(settings.snapshotPending())
        assertTrue(settings.guardMutationStarted())
        assertEquals("177", settings.loadSnapshot().screenBrightness)

        val recoveredRoot = QueueRootRunner(
            result(RootOutcome.Success, category = RootCommandCategory.RestoreSettings),
        )
        val recovered = GuardStateMachine(settings, probe, recoveredRoot).tick()

        assertEquals(GuardStatus.WaitingUsb, recovered.status)
        assertEquals(listOf(RootCommandCategory.RestoreSettings), recoveredRoot.categories)
        assertTrue(recoveredRoot.commands.single().contains("screen_brightness 177"))
        assertTrue(recoveredRoot.commands.single().contains(" && "))
        assertEquals(false, settings.snapshotPending())
    }

    @Test
    fun commandsFailFastAndCaptureUsesNamedProtocol() {
        val settings = FakeGuardSettings(serviceEnabled = true)
        val root = QueueRootRunner(
            result(RootOutcome.Success, stdout = validSnapshotOutput()),
            result(RootOutcome.Success, category = RootCommandCategory.ApplyGuard),
        )
        val machine = GuardStateMachine(settings, UsbAdbStateReader { UsbAdbState(true, true) }, root)

        assertEquals(GuardStatus.Protected, machine.tick().status)
        assertTrue(root.commands[0].contains("UDG_SETTING_stay_on="))
        assertTrue(root.commands.all { ";" !in it })
        assertTrue(root.commands.all { " && " in it })
    }

    @Test
    fun malformedSnapshotOutputStopsBeforeDisplayMutation() {
        val settings = FakeGuardSettings(serviceEnabled = true)
        val root = QueueRootRunner(
            result(
                RootOutcome.Success,
                stdout = "root-manager-banner\nUDG_SETTING_stay_on=0\nUDG_SETTING_screen_brightness=120",
            ),
        )
        val machine = GuardStateMachine(settings, UsbAdbStateReader { UsbAdbState(true, true) }, root)

        assertEquals(GuardStatus.RootUnavailable, machine.tick().status)
        assertEquals(1, root.calls)
        assertEquals(false, settings.guardMutationStarted())
        assertEquals(false, settings.snapshotPending())
    }

    @Test
    fun stopKeepsServiceAndWarningUntilRestoreActuallySucceeds() {
        val settings = FakeGuardSettings(serviceEnabled = true)
        settings.saveSnapshot(DisplaySettingsSnapshot("3", "1", "99", "60000"))
        settings.markGuardMutationStarted()
        settings.setGuarded(true)
        val probe = UsbAdbStateReader { UsbAdbState(false, false) }
        val failedRoot = QueueRootRunner(
            result(RootOutcome.Denied, category = RootCommandCategory.RestoreSettings),
        )

        val failed = GuardStateMachine(settings, probe, failedRoot).stop()
        assertEquals(GuardStatus.RestoreFailed, failed.status)
        assertTrue(settings.serviceEnabled())
        assertTrue(settings.stopPending())
        assertTrue(settings.snapshotPending())

        val recoveredRoot = QueueRootRunner(
            result(RootOutcome.Success, category = RootCommandCategory.RestoreSettings),
        )
        val recovered = GuardStateMachine(settings, probe, recoveredRoot).tick()
        assertEquals(GuardStatus.Idle, recovered.status)
        assertEquals(false, settings.serviceEnabled())
        assertEquals(false, settings.stopPending())
        assertEquals(false, settings.snapshotPending())
    }
}

private class QueueRootRunner(vararg queued: RootResult) : RootCommandRunner {
    private val results = ArrayDeque(queued.toList())
    private var circuit = RootCircuitSnapshot()
    var calls: Int = 0
        private set
    val categories = mutableListOf<RootCommandCategory>()
    val commands = mutableListOf<String>()

    override fun run(
        category: RootCommandCategory,
        command: String,
        manualRetry: Boolean,
        outputValidator: ((String) -> Boolean)?,
    ): RootResult {
        calls += 1
        categories += category
        commands += command
        var next = results.pollFirst() ?: result(RootOutcome.Success, category)
        if (next.ok && outputValidator != null && !outputValidator(next.stdout)) {
            next = result(RootOutcome.Exception, category)
        }
        if (!next.ok) {
            circuit = RootCircuitSnapshot(true, next.outcome, 1, next.outcome)
        } else {
            circuit = circuit.copy(lastOutcome = RootOutcome.Success)
        }
        return next
    }

    override fun circuit(): RootCircuitSnapshot = circuit
}

private class FakeGuardSettings(
    private var serviceEnabled: Boolean,
    private val requireAdb: Boolean = true,
) : GuardSettings {
    var isGuardedState: Boolean = false
    private var snapshot = DisplaySettingsSnapshot.fallback
    var snapshotPendingState: Boolean = false
    var mutationStartedState: Boolean = false
    private var stopPendingState: Boolean = false

    override fun serviceEnabled(): Boolean = serviceEnabled
    override fun setServiceEnabled(enabled: Boolean) { serviceEnabled = enabled }
    override fun requireAdb(): Boolean = requireAdb
    override fun lockOnDisconnect(): Boolean = true
    override fun dismissKeyguard(): Boolean = false
    override fun guardMode(): GuardMode = GuardMode.ScreenOff
    override fun guardedBrightness(): Int = 1
    override fun guarded(): Boolean = isGuardedState
    override fun setGuarded(guarded: Boolean) { this.isGuardedState = guarded }
    override fun setLastStatus(status: GuardStatus) = Unit
    override fun setLastAction(action: String) = Unit
    override fun snapshotPending(): Boolean = snapshotPendingState
    override fun guardMutationStarted(): Boolean = mutationStartedState
    override fun markGuardMutationStarted() { mutationStartedState = true }
    override fun saveSnapshot(snapshot: DisplaySettingsSnapshot) {
        this.snapshot = snapshot
        snapshotPendingState = true
        mutationStartedState = false
    }
    override fun loadSnapshot(): DisplaySettingsSnapshot = snapshot
    override fun clearSnapshot() {
        snapshot = DisplaySettingsSnapshot.fallback
        snapshotPendingState = false
        mutationStartedState = false
    }
    override fun stopPending(): Boolean = stopPendingState
    override fun setStopPending(pending: Boolean) { stopPendingState = pending }
}

private fun validSnapshotOutput(
    stayOn: String = "0",
    mode: String = "1",
    brightness: String = "120",
    timeout: String = "30000",
): String = """
    UDG_SETTING_stay_on=$stayOn
    UDG_SETTING_brightness_mode=$mode
    UDG_SETTING_screen_brightness=$brightness
    UDG_SETTING_screen_off_timeout=$timeout
""".trimIndent()
