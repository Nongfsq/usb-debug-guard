package io.github.nongfsq.usbdebugguard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.system.Os
import android.system.OsConstants
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessRootExecutorInstrumentedTest {
    @Test
    fun hangingAndroidProcessIsBoundedAndOpensCircuitExactlyOnce() {
        val starts = AtomicInteger()
        val executor = ProcessRootExecutor(
            processFactory = RootProcessFactory {
                starts.incrementAndGet()
                ProcessBuilder("sh", "-c", "trap '' TERM INT HUP; while read line; do :; done").start()
            },
            timeoutMillis = 150,
            destroyWaitMillis = 500,
        )
        val controller = RootController(executor, InstrumentedSafetyStore())

        val startedAt = System.currentTimeMillis()
        val first = controller.run(RootCommandCategory.Test, "instrumented-fixture")
        repeat(10) {
            assertEquals(
                RootOutcome.CircuitOpen,
                controller.run(RootCommandCategory.Test, "instrumented-fixture").outcome,
            )
        }
        controller.close()

        assertTrue(first.outcome == RootOutcome.Timeout || first.outcome == RootOutcome.DestroyFailed)
        assertEquals(first.outcome == RootOutcome.DestroyFailed, first.diagnostic.aliveAfterDestroy)
        assertTrue(first.diagnostic.circuitOpen)
        assertEquals(1, starts.get())
        assertTrue(System.currentTimeMillis() - startedAt < 2_000)
    }

    @Test
    fun androidProcessStdoutAndStderrAreDrainedConcurrently() {
        val executor = ProcessRootExecutor(
            processFactory = RootProcessFactory {
                ProcessBuilder(
                    "sh",
                    "-c",
                    "i=0; while [ \$i -lt 2000 ]; do echo out; echo err >&2; i=\$((i+1)); done",
                ).start()
            },
            timeoutMillis = 5_000,
            destroyWaitMillis = 500,
        )

        val execution = executor.execute(request())
        executor.close()

        assertEquals(RootOutcome.Success, execution.outcome)
        assertFalse(execution.stdout.isBlank())
        assertFalse(execution.stderr.isBlank())
        assertTrue(execution.stdout.contains("out"))
        assertTrue(execution.stderr.contains("err"))
    }

    @Test
    fun androidTimeoutDestroysDescendantAndConfirmsItIsGone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pidFile = java.io.File(context.cacheDir, "root-tree-child.pid").apply { delete() }
        val processTree = RecordingRootProcessTree()
        val executor = ProcessRootExecutor(
            processFactory = RootProcessFactory {
                ProcessBuilder(
                    "sh",
                    "-c",
                    "printf '${ProcessRootExecutor.DIRECT_PID_MARKER}%s\\n' \"\$\$\"; " +
                        "sleep 30 & child=\$!; " +
                        "printf '${ProcessRootExecutor.ROOT_CHILD_PID_MARKER}%s\\n' \"\$child\"; " +
                        "echo \$child > '${pidFile.absolutePath}'; wait \$child",
                ).start()
            },
            timeoutMillis = 500,
            destroyWaitMillis = 750,
            processTree = processTree,
        )

        val execution = executor.execute(request())
        executor.close()
        val childPid = pidFile.readText().trim().toInt()
        try {
            assertEquals(RootOutcome.Timeout, execution.outcome)
            assertEquals(false, execution.aliveAfterDestroy)
            assertTrue(
                "rootPid=${processTree.rootPid} descendants=${processTree.descendantPids}",
                childPid.toLong() in processTree.descendantPids,
            )
            assertFalse(
                "child stat=${processStat(childPid)}",
                processExists(childPid),
            )
        } finally {
            if (processExists(childPid)) runCatching { Os.kill(childPid, OsConstants.SIGKILL) }
            pidFile.delete()
        }
    }

    @Test
    fun productionWrapperCapturesPidAndHidesInternalMarkers() {
        val executor = ProcessRootExecutor(
            timeoutMillis = 1_000,
            destroyWaitMillis = 500,
        )

        val execution = executor.execute(request())
        executor.close()

        assertTrue(execution.pid != null && execution.pid > 0)
        assertFalse(execution.stdout.contains(ProcessRootExecutor.DIRECT_PID_MARKER))
        assertFalse(execution.stdout.contains(ProcessRootExecutor.ROOT_CHILD_PID_MARKER))
        assertTrue(
            execution.outcome in setOf(
                RootOutcome.Success,
                RootOutcome.Denied,
                RootOutcome.Exception,
            )
        )
    }

    private fun request() = RootRequest(
        id = UUID.randomUUID().toString(),
        category = RootCommandCategory.Test,
        command = "instrumented-fixture",
    )
}

private fun processExists(pid: Int): Boolean = java.io.File("/proc/$pid").exists()

private fun processStat(pid: Int): String = runCatching {
    java.io.File("/proc/$pid/stat").readText()
}.getOrDefault("missing")

private class RecordingRootProcessTree : RootProcessTree {
    private val delegate = PlatformRootProcessTree()
    val descendantPids = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    var rootPid: Long? = null

    override fun directChildren(): Set<Long> = delegate.directChildren()

    override fun resolvePid(process: Process, childrenBeforeStart: Set<Long>): Long? =
        delegate.resolvePid(process, childrenBeforeStart).also { rootPid = it }

    override fun descendants(process: Process, rootPid: Long?): List<TrackedRootProcess> =
        delegate.descendants(process, rootPid).also { descendants ->
            descendantPids += descendants.map(TrackedRootProcess::pid)
        }

    override fun track(pid: Long): TrackedRootProcess = delegate.track(pid).also {
        descendantPids += pid
    }

    override fun destroy(process: TrackedRootProcess) = delegate.destroy(process)

    override fun isAlive(process: TrackedRootProcess): Boolean = delegate.isAlive(process)
}

private class InstrumentedSafetyStore : RootSafetyStore {
    private var state = RootCircuitSnapshot()

    @Synchronized
    override fun snapshot(): RootCircuitSnapshot = state

    @Synchronized
    override fun open(reason: RootOutcome, retryAllowedAtMillis: Long): RootCircuitSnapshot {
        state = RootCircuitSnapshot(
            open = true,
            reason = reason,
            failureCount = state.failureCount + 1,
            lastOutcome = reason,
            retryAllowedAtMillis = retryAllowedAtMillis,
        )
        return state
    }

    @Synchronized
    override fun tryBeginManualRetry(nowMillis: Long): RootCircuitSnapshot? {
        if (!state.open || state.manualRetryConsumed || nowMillis < state.retryAllowedAtMillis) return null
        state = state.copy(manualRetryConsumed = true)
        return state
    }

    @Synchronized
    override fun closeAfterSuccess(): RootCircuitSnapshot {
        state = RootCircuitSnapshot(lastOutcome = RootOutcome.Success)
        return state
    }

    @Synchronized
    override fun record(diagnostic: RootDiagnostic) {
        state = state.copy(lastOutcome = diagnostic.outcome)
    }
}
