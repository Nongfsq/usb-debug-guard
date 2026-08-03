package io.github.nongfsq.usbdebugguard

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class RootCommandCategory(val value: String) {
    Test("test"),
    CaptureSettings("capture-settings"),
    ApplyGuard("apply-guard"),
    RestoreSettings("restore-settings"),
    LockScreen("lock-screen"),
}

enum class RootOutcome(val value: String) {
    Success("success"),
    Denied("denied"),
    Timeout("timeout"),
    Exception("exception"),
    DestroyFailed("destroy-failed"),
    CircuitOpen("circuit-open"),
    Cancelled("cancelled");

    companion object {
        fun from(value: String?): RootOutcome? = entries.firstOrNull { it.value == value }
    }
}

data class RootRequest(
    val id: String,
    val category: RootCommandCategory,
    val command: String,
)

data class RootExecution(
    val requestId: String,
    val category: RootCommandCategory,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val pid: Long?,
    val code: Int,
    val stdout: String,
    val stderr: String,
    val outcome: RootOutcome,
    val aliveAfterDestroy: Boolean?,
)

data class RootCircuitSnapshot(
    val open: Boolean = false,
    val reason: RootOutcome? = null,
    val failureCount: Int = 0,
    val lastOutcome: RootOutcome? = null,
    val manualRetryConsumed: Boolean = false,
    val retryAllowedAtMillis: Long = 0L,
)

data class RootDiagnostic(
    val requestId: String,
    val category: RootCommandCategory,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val pid: Long?,
    val outcome: RootOutcome,
    val aliveAfterDestroy: Boolean?,
    val circuitOpen: Boolean,
    val failureCount: Int,
) {
    fun structuredLog(): String = buildString {
        append("requestId=").append(requestId)
        append(" category=").append(category.value)
        append(" startedAt=").append(startedAtMillis)
        append(" endedAt=").append(endedAtMillis)
        append(" pid=").append(pid ?: "unknown")
        append(" outcome=").append(outcome.value)
        append(" aliveAfterDestroy=").append(aliveAfterDestroy ?: "not-applicable")
        append(" circuitOpen=").append(circuitOpen)
        append(" failureCount=").append(failureCount)
    }
}

data class RootResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val outcome: RootOutcome,
    val diagnostic: RootDiagnostic,
) {
    val ok: Boolean get() = outcome == RootOutcome.Success && code == 0
}

interface RootExecutor : AutoCloseable {
    fun execute(request: RootRequest): RootExecution
    fun cancelActive()
    override fun close()
}

fun interface RootProcessFactory {
    fun start(command: String): Process
}

internal data class TrackedRootProcess(
    val pid: Long,
    val startToken: String?,
    val platformHandle: Any? = null,
)

internal interface RootProcessTree {
    fun directChildren(): Set<Long>
    fun resolvePid(process: Process, childrenBeforeStart: Set<Long>): Long?
    fun descendants(process: Process, rootPid: Long?): List<TrackedRootProcess>
    fun track(pid: Long): TrackedRootProcess
    fun destroy(process: TrackedRootProcess)
    fun isAlive(process: TrackedRootProcess): Boolean
}

internal class PlatformRootProcessTree : RootProcessTree {
    override fun directChildren(): Set<Long> = File("/proc/self/task")
        .listFiles()
        .orEmpty()
        .flatMapTo(linkedSetOf()) { task -> readChildren(File(task, "children")) }

    override fun resolvePid(process: Process, childrenBeforeStart: Set<Long>): Long? {
        reflectedPid(process)?.let { return it }
        repeat(PID_DISCOVERY_ATTEMPTS) {
            val candidates = directChildren() - childrenBeforeStart
            if (candidates.size == 1) return candidates.single()
            if (it < PID_DISCOVERY_ATTEMPTS - 1) Thread.sleep(PID_DISCOVERY_DELAY_MILLIS)
        }
        return null
    }

    override fun descendants(process: Process, rootPid: Long?): List<TrackedRootProcess> {
        reflectedDescendants(process).takeIf { it.isNotEmpty() }?.let { return it }
        if (rootPid == null) return emptyList()

        val discovered = linkedSetOf<Long>()
        val pending = ArrayDeque<Long>()
        pending += rootPid
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            procChildren(parent).forEach { child ->
                if (discovered.add(child)) pending += child
            }
        }
        return discovered.map(::trackedProcProcess)
    }

    override fun track(pid: Long): TrackedRootProcess = trackedProcProcess(pid)

    override fun destroy(process: TrackedRootProcess) {
        if (process.platformHandle != null) {
            val destroyed = invokeHandle(process.platformHandle, "destroyForcibly") as? Boolean
            if (destroyed == true) return
        }
        if (invokeAndroidKill(process.pid)) return
        runCatching {
            ProcessBuilder("kill", "-9", process.pid.toString())
                .start()
                .waitFor(KILL_COMMAND_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    override fun isAlive(process: TrackedRootProcess): Boolean {
        process.platformHandle?.let { handle ->
            (invokeHandle(handle, "isAlive") as? Boolean)?.let { return it }
        }
        procState(process.pid)?.let { state ->
            if (state == 'Z') return false
            return process.startToken == null || process.startToken == procStartToken(process.pid)
        }
        return androidProcessExists(process.pid) ?: false
    }

    private fun reflectedPid(process: Process): Long? = runCatching {
        val value = Process::class.java.getMethod("pid").invoke(process) as Number
        value.toLong()
    }.getOrNull()

    private fun reflectedDescendants(process: Process): List<TrackedRootProcess> = runCatching {
        val stream = Process::class.java.getMethod("descendants").invoke(process) as AutoCloseable
        try {
            val iterator = Class.forName("java.util.stream.BaseStream")
                .getMethod("iterator")
                .invoke(stream) as Iterator<*>
            buildList {
                iterator.forEachRemaining { handle ->
                    if (handle != null) {
                        val pid = (invokeHandle(handle, "pid") as Number).toLong()
                        add(TrackedRootProcess(pid, startToken = null, platformHandle = handle))
                    }
                }
            }
        } finally {
            stream.close()
        }
    }.getOrDefault(emptyList())

    private fun invokeHandle(handle: Any, method: String): Any? = runCatching {
        Class.forName("java.lang.ProcessHandle").getMethod(method).invoke(handle)
    }.getOrNull()

    private fun invokeAndroidKill(pid: Long): Boolean = runCatching {
        val constants = Class.forName("android.system.OsConstants")
        val signal = constants.getField("SIGKILL").getInt(null)
        val os = Class.forName("android.system.Os")
        os.getMethod("kill", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(null, pid.toInt(), signal)
        true
    }.getOrDefault(false)

    private fun androidProcessExists(pid: Long): Boolean? {
        return try {
            val os = Class.forName("android.system.Os")
            os.getMethod("kill", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(null, pid.toInt(), 0)
            true
        } catch (error: InvocationTargetException) {
            val errno = runCatching {
                error.cause?.javaClass?.getField("errno")?.getInt(error.cause)
            }.getOrNull() ?: return null
            val constants = runCatching { Class.forName("android.system.OsConstants") }.getOrNull()
                ?: return null
            when (errno) {
                constants.getField("ESRCH").getInt(null) -> false
                constants.getField("EPERM").getInt(null) -> true
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun procChildren(pid: Long): Set<Long> = File("/proc/$pid/task")
        .listFiles()
        .orEmpty()
        .flatMapTo(linkedSetOf()) { task -> readChildren(File(task, "children")) }

    private fun readChildren(file: File): List<Long> = runCatching {
        file.readText().trim().split(Regex("\\s+"))
            .mapNotNull(String::toLongOrNull)
    }.getOrDefault(emptyList())

    private fun trackedProcProcess(pid: Long) = TrackedRootProcess(
        pid = pid,
        startToken = procStartToken(pid),
    )

    private fun procState(pid: Long): Char? = procStatFields(pid)?.firstOrNull()?.firstOrNull()

    private fun procStartToken(pid: Long): String? = procStatFields(pid)?.getOrNull(PROC_START_TOKEN_INDEX)

    private fun procStatFields(pid: Long): List<String>? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        stat.substring(stat.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
    }.getOrNull()

    companion object {
        private const val PID_DISCOVERY_ATTEMPTS = 5
        private const val PID_DISCOVERY_DELAY_MILLIS = 10L
        private const val KILL_COMMAND_WAIT_MILLIS = 250L
        private const val PROC_START_TOKEN_INDEX = 19
    }
}

private class ProcessTreeMonitor(
    private val process: Process,
    private val rootPid: () -> Long?,
    private val processTree: RootProcessTree,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val tracked = Collections.synchronizedMap(linkedMapOf<Long, TrackedRootProcess>())
    private val thread = Thread({
        while (running.get()) {
            capture()
            if (!runCatching { process.isAlive }.getOrDefault(true)) break
            runCatching { Thread.sleep(TREE_SAMPLE_MILLIS) }
        }
        capture()
    }, "USB-Debug-Guard-root-tree").apply { isDaemon = true }

    fun start() = thread.start()

    fun capture() {
        processTree.descendants(process, rootPid()).forEach { tracked[it.pid] = it }
    }

    fun trackExplicit(pid: Long) {
        tracked[pid] = processTree.track(pid)
    }

    fun snapshot(): List<TrackedRootProcess> = synchronized(tracked) { tracked.values.toList() }

    override fun close() {
        running.set(false)
        thread.interrupt()
        runCatching { thread.join(TREE_MONITOR_JOIN_MILLIS) }
        capture()
    }

    companion object {
        private const val TREE_SAMPLE_MILLIS = 25L
        private const val TREE_MONITOR_JOIN_MILLIS = 100L
    }
}

private data class ActiveRootProcess(
    val process: Process,
    val pid: AtomicLong,
    val monitor: ProcessTreeMonitor,
)

class ProcessRootExecutor internal constructor(
    private val processFactory: RootProcessFactory = RootProcessFactory { command ->
        val rootCommand = "printf '$ROOT_CHILD_PID_MARKER%s\\n' \"\$\$\"; $command"
        ProcessBuilder(
            "sh",
            "-c",
            "printf '$DIRECT_PID_MARKER%s\\n' \"\$\$\"; exec su -c \"\$1\"",
            "usb-debug-guard",
            rootCommand,
        ).start()
    },
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val destroyWaitMillis: Long = DEFAULT_DESTROY_WAIT_MILLIS,
    private val outputWaitMillis: Long = DEFAULT_OUTPUT_WAIT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val processTree: RootProcessTree = PlatformRootProcessTree(),
) : RootExecutor {
    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)
    private val activeProcess = AtomicReference<ActiveRootProcess?>(null)
    private val outputPool = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "USB-Debug-Guard-root-output").apply { isDaemon = true }
    }

    override fun execute(request: RootRequest): RootExecution {
        val startedAt = clock()
        if (closed.get() || !inFlight.compareAndSet(false, true)) {
            return exceptionExecution(request, startedAt, "Root executor is closed or busy")
        }

        var active: ActiveRootProcess? = null
        var stdoutFuture: Future<String>? = null
        var stderrFuture: Future<String>? = null
        return try {
            val childrenBeforeStart = processTree.directChildren()
            val process = processFactory.start(request.command)
            val pid = AtomicLong(processTree.resolvePid(process, childrenBeforeStart) ?: UNKNOWN_PID)
            val monitor = ProcessTreeMonitor(process, { pid.pidOrNull() }, processTree).also(ProcessTreeMonitor::start)
            active = ActiveRootProcess(process, pid, monitor)
            activeProcess.set(active)
            if (closed.get()) {
                val alive = destroyAndConfirm(active)
                return RootExecution(
                    requestId = request.id,
                    category = request.category,
                    startedAtMillis = startedAt,
                    endedAtMillis = clock(),
                    pid = pid.pidOrNull(),
                    code = if (alive) CODE_DESTROY_FAILED else CODE_EXCEPTION,
                    stdout = "",
                    stderr = if (alive) "Root process remained alive during close" else "Root executor closed",
                    outcome = if (alive) RootOutcome.DestroyFailed else RootOutcome.Cancelled,
                    aliveAfterDestroy = alive,
                )
            }
            stdoutFuture = outputPool.submit(Callable {
                drain(process.inputStream) { marker, markerPid ->
                    when (marker) {
                        DIRECT_PID_MARKER -> pid.compareAndSet(UNKNOWN_PID, markerPid)
                        ROOT_CHILD_PID_MARKER -> monitor.trackExplicit(markerPid)
                    }
                }
            })
            stderrFuture = outputPool.submit(Callable { drain(process.errorStream) })

            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                val alive = destroyAndConfirm(active)
                cancelCollectors(process, stdoutFuture, stderrFuture)
                RootExecution(
                    requestId = request.id,
                    category = request.category,
                    startedAtMillis = startedAt,
                    endedAtMillis = clock(),
                    pid = pid.pidOrNull(),
                    code = if (alive) CODE_DESTROY_FAILED else CODE_TIMEOUT,
                    stdout = "",
                    stderr = if (alive) "Root process remained alive after destroy" else "Root process timed out",
                    outcome = if (alive) RootOutcome.DestroyFailed else RootOutcome.Timeout,
                    aliveAfterDestroy = alive,
                )
            } else {
                val stdout = awaitOutput(stdoutFuture)
                val stderr = awaitOutput(stderrFuture)
                monitor.close()
                cancelCollectors(process, stdoutFuture, stderrFuture)
                val code = process.exitValue()
                val outcome = when {
                    closed.get() -> RootOutcome.Cancelled
                    code == 0 -> RootOutcome.Success
                    else -> RootOutcome.Denied
                }
                RootExecution(
                    requestId = request.id,
                    category = request.category,
                    startedAtMillis = startedAt,
                    endedAtMillis = clock(),
                    pid = pid.pidOrNull(),
                    code = code,
                    stdout = stdout,
                    stderr = stderr,
                    outcome = outcome,
                    aliveAfterDestroy = null,
                )
            }
        } catch (error: Exception) {
            val alive = active?.let(::destroyAndConfirm)
            active?.process?.let { cancelCollectors(it, stdoutFuture, stderrFuture) }
            RootExecution(
                requestId = request.id,
                category = request.category,
                startedAtMillis = startedAt,
                endedAtMillis = clock(),
                pid = active?.pid?.pidOrNull(),
                code = if (alive == true) CODE_DESTROY_FAILED else CODE_EXCEPTION,
                stdout = "",
                stderr = error.javaClass.simpleName,
                outcome = if (alive == true) RootOutcome.DestroyFailed else RootOutcome.Exception,
                aliveAfterDestroy = alive,
            )
        } finally {
            active?.monitor?.close()
            activeProcess.compareAndSet(active, null)
            inFlight.set(false)
        }
    }

    override fun cancelActive() {
        activeProcess.get()?.let { active ->
            destroyAndConfirm(active)
            closeProcessStreams(active.process)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelActive()
        outputPool.shutdownNow()
    }

    private fun destroyAndConfirm(active: ActiveRootProcess): Boolean {
        active.monitor.close()
        val descendants = active.monitor.snapshot()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(destroyWaitMillis)
        descendants.asReversed().forEach(processTree::destroy)
        val exitedAfterDescendants = runCatching {
            active.process.waitFor(
                minOf(DESCENDANT_REAP_GRACE_MILLIS, remainingMillis(deadline)),
                TimeUnit.MILLISECONDS,
            )
        }.getOrDefault(false)
        if (!exitedAfterDescendants) {
            runCatching { active.process.destroyForcibly() }
            runCatching {
                active.process.waitFor(remainingMillis(deadline), TimeUnit.MILLISECONDS)
            }
        }

        while (System.nanoTime() < deadline) {
            val directAlive = runCatching { active.process.isAlive }.getOrDefault(true)
            val descendantAlive = descendants.any(processTree::isAlive)
            if (!directAlive && !descendantAlive) return false
            runCatching { Thread.sleep(DESTROY_CONFIRM_POLL_MILLIS) }
        }
        return runCatching { active.process.isAlive }.getOrDefault(true) ||
            descendants.any(processTree::isAlive)
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))

    private fun cancelCollectors(
        process: Process,
        stdoutFuture: Future<String>?,
        stderrFuture: Future<String>?,
    ) {
        closeProcessStreams(process)
        stdoutFuture?.cancel(true)
        stderrFuture?.cancel(true)
    }

    private fun closeProcessStreams(process: Process) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }

    private fun awaitOutput(future: Future<String>): String =
        future.get(outputWaitMillis, TimeUnit.MILLISECONDS)

    private fun drain(
        input: InputStream,
        onPidMarker: ((String, Long) -> Unit)? = null,
    ): String {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            var retained = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val writable = minOf(count, MAX_CAPTURE_BYTES - retained)
                if (writable > 0) {
                    output.write(buffer, 0, writable)
                    retained += writable
                    if (onPidMarker != null) scanPidMarkers(output.toString(StandardCharsets.UTF_8.name()), onPidMarker)
                }
            }
            val captured = output.toString(StandardCharsets.UTF_8.name())
            if (onPidMarker != null) scanPidMarkers(captured, onPidMarker)
            return captured.lineSequence()
                .filterNot { parsePidMarker(it.trim()) != null }
                .joinToString("\n")
                .trim()
        }
    }

    private fun scanPidMarkers(output: String, onPidMarker: (String, Long) -> Unit) {
        output.lineSequence().forEach { line ->
            parsePidMarker(line.trim())?.let { (marker, pid) -> onPidMarker(marker, pid) }
        }
    }

    private fun parsePidMarker(line: String): Pair<String, Long>? {
        val marker = when {
            line.startsWith(DIRECT_PID_MARKER) -> DIRECT_PID_MARKER
            line.startsWith(ROOT_CHILD_PID_MARKER) -> ROOT_CHILD_PID_MARKER
            else -> return null
        }
        val pid = line.removePrefix(marker).toLongOrNull()?.takeIf { it > 0 } ?: return null
        return marker to pid
    }

    private fun exceptionExecution(request: RootRequest, startedAt: Long, message: String) = RootExecution(
        requestId = request.id,
        category = request.category,
        startedAtMillis = startedAt,
        endedAtMillis = clock(),
        pid = null,
        code = CODE_EXCEPTION,
        stdout = "",
        stderr = message,
        outcome = RootOutcome.Exception,
        aliveAfterDestroy = null,
    )

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 8_000L
        const val DEFAULT_DESTROY_WAIT_MILLIS = 1_000L
        private const val DEFAULT_OUTPUT_WAIT_MILLIS = 1_000L
        private const val DESCENDANT_REAP_GRACE_MILLIS = 200L
        private const val DESTROY_CONFIRM_POLL_MILLIS = 20L
        private const val MAX_CAPTURE_BYTES = 64 * 1_024
        private const val CODE_EXCEPTION = -1
        private const val CODE_TIMEOUT = -2
        private const val CODE_DESTROY_FAILED = -3
        internal const val DIRECT_PID_MARKER = "UDG_PROCESS_PID="
        internal const val ROOT_CHILD_PID_MARKER = "UDG_ROOT_CHILD_PID="
        private const val UNKNOWN_PID = -1L
    }
}

private fun AtomicLong.pidOrNull(): Long? = get().takeIf { it > 0 }

interface RootSafetyStore {
    fun snapshot(): RootCircuitSnapshot
    fun open(reason: RootOutcome, retryAllowedAtMillis: Long = 0L): RootCircuitSnapshot
    fun tryBeginManualRetry(nowMillis: Long): RootCircuitSnapshot?
    fun closeAfterSuccess(): RootCircuitSnapshot
    fun record(diagnostic: RootDiagnostic)
}

interface RootCommandRunner {
    fun run(
        category: RootCommandCategory,
        command: String,
        manualRetry: Boolean = false,
        outputValidator: ((String) -> Boolean)? = null,
    ): RootResult
    fun circuit(): RootCircuitSnapshot
}

class RootController(
    private val executor: RootExecutor,
    private val safetyStore: RootSafetyStore,
    private val executionLock: ReentrantLock = ReentrantLock(true),
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestId: () -> String = { UUID.randomUUID().toString() },
    private val manualRetryCooldownMillis: Long = DEFAULT_MANUAL_RETRY_COOLDOWN_MILLIS,
) : RootCommandRunner, AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun run(
        category: RootCommandCategory,
        command: String,
        manualRetry: Boolean,
        outputValidator: ((String) -> Boolean)?,
    ): RootResult =
        executionLock.withLock {
            val startedAt = clock()
            if (closed.get()) {
                return@withLock syntheticResult(category, startedAt, RootOutcome.Cancelled)
            }
            val before = safetyStore.snapshot()
            if (manualRetry) {
                if (safetyStore.tryBeginManualRetry(startedAt) == null) {
                    return@withLock syntheticResult(category, startedAt, RootOutcome.CircuitOpen, before)
                }
            } else if (before.open) {
                return@withLock syntheticResult(category, startedAt, RootOutcome.CircuitOpen, before)
            }

            val request = RootRequest(requestId(), category, command)
            var execution = try {
                executor.execute(request)
            } catch (error: Exception) {
                RootExecution(
                    requestId = request.id,
                    category = category,
                    startedAtMillis = startedAt,
                    endedAtMillis = clock(),
                    pid = null,
                    code = -1,
                    stdout = "",
                    stderr = error.javaClass.simpleName,
                    outcome = RootOutcome.Exception,
                    aliveAfterDestroy = null,
                )
            }

            if (execution.outcome == RootOutcome.Success && outputValidator != null) {
                val valid = runCatching { outputValidator(execution.stdout) }.getOrDefault(false)
                if (!valid) {
                    execution = execution.copy(
                        code = -1,
                        stderr = "Root output validation failed",
                        outcome = RootOutcome.Exception,
                    )
                }
            }

            val circuit = when {
                execution.outcome == RootOutcome.Success -> safetyStore.closeAfterSuccess()
                execution.outcome.opensCircuit() -> safetyStore.open(
                    execution.outcome,
                    retryAllowedAtMillis = clock() + manualRetryCooldownMillis,
                )
                else -> safetyStore.snapshot()
            }
            val diagnostic = execution.toDiagnostic(circuit)
            safetyStore.record(diagnostic)
            RootResult(
                code = execution.code,
                stdout = execution.stdout,
                stderr = execution.stderr,
                outcome = execution.outcome,
                diagnostic = diagnostic,
            )
        }

    override fun circuit(): RootCircuitSnapshot = safetyStore.snapshot()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.close()
    }

    private fun syntheticResult(
        category: RootCommandCategory,
        startedAt: Long,
        outcome: RootOutcome,
        circuit: RootCircuitSnapshot = safetyStore.snapshot(),
    ): RootResult {
        val diagnostic = RootDiagnostic(
            requestId = requestId(),
            category = category,
            startedAtMillis = startedAt,
            endedAtMillis = clock(),
            pid = null,
            outcome = outcome,
            aliveAfterDestroy = null,
            circuitOpen = circuit.open,
            failureCount = circuit.failureCount,
        )
        return RootResult(
            code = if (outcome == RootOutcome.CircuitOpen) -4 else -5,
            stdout = "",
            stderr = outcome.value,
            outcome = outcome,
            diagnostic = diagnostic,
        )
    }

    companion object {
        const val DEFAULT_MANUAL_RETRY_COOLDOWN_MILLIS = 5_000L
    }
}

private fun RootOutcome.opensCircuit(): Boolean = when (this) {
    RootOutcome.Denied,
    RootOutcome.Timeout,
    RootOutcome.Exception,
    RootOutcome.DestroyFailed -> true
    RootOutcome.Success,
    RootOutcome.CircuitOpen,
    RootOutcome.Cancelled -> false
}

private fun RootExecution.toDiagnostic(circuit: RootCircuitSnapshot) = RootDiagnostic(
    requestId = requestId,
    category = category,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    pid = pid,
    outcome = outcome,
    aliveAfterDestroy = aliveAfterDestroy,
    circuitOpen = circuit.open,
    failureCount = circuit.failureCount,
)
