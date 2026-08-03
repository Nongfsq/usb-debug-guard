package io.github.nongfsq.usbdebugguard

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessRootExecutorTest {
    @Test
    fun timeoutDestroysProcessAndConfirmsExit() {
        val process = ControlledProcess(finishes = false, destroySucceeds = true)
        val executor = ProcessRootExecutor(
            processFactory = RootProcessFactory { process },
            timeoutMillis = 20,
            destroyWaitMillis = 10,
        )

        val result = executor.execute(request())
        executor.close()

        assertEquals(RootOutcome.Timeout, result.outcome)
        assertEquals(false, result.aliveAfterDestroy)
        assertFalse(process.isAlive)
        assertEquals(1, process.destroyCalls.get())
    }

    @Test
    fun timeoutWithFailedDestroyReportsStillAlive() {
        val process = ControlledProcess(finishes = false, destroySucceeds = false)
        val executor = ProcessRootExecutor(
            processFactory = RootProcessFactory { process },
            timeoutMillis = 20,
            destroyWaitMillis = 10,
        )

        val result = executor.execute(request())
        executor.close()

        assertEquals(RootOutcome.DestroyFailed, result.outcome)
        assertEquals(true, result.aliveAfterDestroy)
        assertTrue(process.isAlive)
        assertTrue(process.destroyCalls.get() >= 1)
    }

    @Test
    fun slowProcessReturnsWithinBoundAndBothStreamsAreDrained() {
        val process = ControlledProcess(
            finishes = true,
            destroySucceeds = true,
            finishDelayMillis = 40,
            stdout = "ready",
            stderr = "diagnostic",
        )
        val executor = ProcessRootExecutor(
            processFactory = RootProcessFactory { process },
            timeoutMillis = 200,
            destroyWaitMillis = 10,
        )

        val result = executor.execute(request())
        executor.close()

        assertEquals(RootOutcome.Success, result.outcome)
        assertEquals("ready", result.stdout)
        assertEquals("diagnostic", result.stderr)
        assertEquals(null, result.aliveAfterDestroy)
    }

    @Test
    fun processStartExceptionIsStructured() {
        val executor = ProcessRootExecutor(
            processFactory = RootProcessFactory { throw IllegalStateException("start failed") },
            timeoutMillis = 20,
        )

        val result = executor.execute(request())
        executor.close()

        assertEquals(RootOutcome.Exception, result.outcome)
        assertEquals(null, result.pid)
    }

    @Test
    fun outputCollectorFailureIsReportedAsExceptionInsteadOfEmptySuccess() {
        val process = ControlledProcess(
            finishes = true,
            destroySucceeds = true,
            stdoutInput = object : InputStream() {
                override fun read(): Int = throw IOException("collector failed")
            },
        )
        val executor = ProcessRootExecutor(
            processFactory = RootProcessFactory { process },
            timeoutMillis = 100,
            outputWaitMillis = 100,
        )

        val result = executor.execute(request())
        executor.close()

        assertEquals(RootOutcome.Exception, result.outcome)
        assertFalse(result.outcome == RootOutcome.Success)
    }

    @Test
    fun timeoutDestroysTrackedDescendantProcessTree() {
        val shell = java.io.File("/bin/sh")
        org.junit.Assume.assumeTrue(shell.canExecute())
        val pidFile = Files.createTempFile("usb-debug-guard-child", ".pid").toFile()
        pidFile.delete()
        val processFactory = RootProcessFactory {
            ProcessBuilder(
                shell.absolutePath,
                "-c",
                "sleep 30 & child=\$!; echo \$child > '${pidFile.absolutePath}'; wait \$child",
            ).start()
        }
        val executor = ProcessRootExecutor(
            processFactory = processFactory,
            timeoutMillis = 300,
            destroyWaitMillis = 500,
        )

        val result = executor.execute(request())
        executor.close()
        val childPid = pidFile.readText().trim().toLong()
        val childHandle = ProcessHandle.of(childPid).orElse(null)
        try {
            assertEquals(RootOutcome.Timeout, result.outcome)
            assertEquals(false, result.aliveAfterDestroy)
            assertFalse(childHandle?.isAlive ?: false)
        } finally {
            if (childHandle?.isAlive == true) childHandle.destroyForcibly()
            pidFile.delete()
        }
    }

    @Test
    fun pidMarkersAreCapturedButNeverExposedAsCommandOutput() {
        val process = ControlledProcess(
            finishes = true,
            destroySucceeds = true,
            stdout = """
                ${ProcessRootExecutor.DIRECT_PID_MARKER}4321
                ready
            """.trimIndent(),
        )
        val executor = ProcessRootExecutor(
            processFactory = RootProcessFactory { process },
            timeoutMillis = 100,
        )

        val result = executor.execute(request())
        executor.close()

        assertEquals(RootOutcome.Success, result.outcome)
        assertEquals(4321L, result.pid)
        assertEquals("ready", result.stdout)
    }

    @Test
    fun timeoutWithUnkillableExplicitChildIsDestroyFailed() {
        val process = ControlledProcess(
            finishes = false,
            destroySucceeds = true,
            stdout = "${ProcessRootExecutor.ROOT_CHILD_PID_MARKER}4242",
        )
        val processTree = UnkillableChildProcessTree()
        val executor = ProcessRootExecutor(
            processFactory = RootProcessFactory { process },
            processTree = processTree,
            timeoutMillis = 30,
            destroyWaitMillis = 40,
        )

        val result = executor.execute(request())
        executor.close()

        assertEquals(RootOutcome.DestroyFailed, result.outcome)
        assertEquals(true, result.aliveAfterDestroy)
        assertTrue(processTree.destroyCalls.get() >= 1)
    }

    private fun request() = RootRequest("request-1", RootCommandCategory.Test, "PAYLOAD")
}

private class UnkillableChildProcessTree : RootProcessTree {
    val destroyCalls = AtomicInteger()

    override fun directChildren(): Set<Long> = emptySet()
    override fun resolvePid(process: Process, childrenBeforeStart: Set<Long>): Long? = null
    override fun descendants(process: Process, rootPid: Long?): List<TrackedRootProcess> = emptyList()
    override fun track(pid: Long): TrackedRootProcess = TrackedRootProcess(pid, startToken = "fixture")
    override fun destroy(process: TrackedRootProcess) { destroyCalls.incrementAndGet() }
    override fun isAlive(process: TrackedRootProcess): Boolean = true
}

private class ControlledProcess(
    private val finishes: Boolean,
    private val destroySucceeds: Boolean,
    private val finishDelayMillis: Long = 0,
    stdout: String = "",
    stderr: String = "",
    stdoutInput: InputStream? = null,
    private val exitCode: Int = 0,
) : Process() {
    @Volatile
    private var alive = true
    val destroyCalls = AtomicInteger()
    private val stdoutStream = stdoutInput ?: ByteArrayInputStream(stdout.toByteArray())
    private val stderrStream = ByteArrayInputStream(stderr.toByteArray())
    private val stdinStream = ByteArrayOutputStream()

    override fun getOutputStream(): OutputStream = stdinStream

    override fun getInputStream(): InputStream = stdoutStream

    override fun getErrorStream(): InputStream = stderrStream

    override fun waitFor(): Int {
        while (alive) Thread.sleep(1)
        return exitCode
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        if (!alive) return true
        val timeoutMillis = unit.toMillis(timeout)
        if (finishes && finishDelayMillis <= timeoutMillis) {
            if (finishDelayMillis > 0) Thread.sleep(finishDelayMillis)
            alive = false
            return true
        }
        if (timeoutMillis > 0) Thread.sleep(timeoutMillis)
        return !alive
    }

    override fun exitValue(): Int {
        check(!alive) { "still alive" }
        return exitCode
    }

    override fun destroy() {
        destroyForcibly()
    }

    override fun destroyForcibly(): Process {
        destroyCalls.incrementAndGet()
        if (destroySucceeds) alive = false
        return this
    }

    override fun isAlive(): Boolean = alive
}
