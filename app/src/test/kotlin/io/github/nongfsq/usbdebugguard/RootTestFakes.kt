package io.github.nongfsq.usbdebugguard

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class InMemoryRootSafetyStore : RootSafetyStore {
    private var state = RootCircuitSnapshot()
    val diagnostics = CopyOnWriteArrayList<RootDiagnostic>()

    @Synchronized
    override fun snapshot(): RootCircuitSnapshot = state

    @Synchronized
    override fun open(reason: RootOutcome, retryAllowedAtMillis: Long): RootCircuitSnapshot {
        val continuingIncident = state.failureCount > 0
        state = RootCircuitSnapshot(
            open = true,
            reason = reason,
            failureCount = state.failureCount + 1,
            lastOutcome = reason,
            manualRetryConsumed = if (continuingIncident) state.manualRetryConsumed else false,
            retryAllowedAtMillis = if (continuingIncident) state.retryAllowedAtMillis else retryAllowedAtMillis,
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
        diagnostics += diagnostic
        state = state.copy(lastOutcome = diagnostic.outcome)
    }
}

class FakeRootExecutor(
    private val behavior: (RootRequest) -> RootExecution,
) : RootExecutor {
    val callCount = AtomicInteger()
    val active = AtomicInteger()
    val maxActive = AtomicInteger()

    override fun execute(request: RootRequest): RootExecution {
        callCount.incrementAndGet()
        val nowActive = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, nowActive) }
        return try {
            behavior(request)
        } finally {
            active.decrementAndGet()
        }
    }

    override fun cancelActive() = Unit

    override fun close() = Unit
}

fun execution(
    request: RootRequest,
    outcome: RootOutcome,
    code: Int = if (outcome == RootOutcome.Success) 0 else 1,
    stdout: String = "",
    aliveAfterDestroy: Boolean? = null,
): RootExecution = RootExecution(
    requestId = request.id,
    category = request.category,
    startedAtMillis = 10,
    endedAtMillis = 20,
    pid = 123,
    code = code,
    stdout = stdout,
    stderr = if (outcome == RootOutcome.Success) "" else outcome.value,
    outcome = outcome,
    aliveAfterDestroy = aliveAfterDestroy,
)

fun result(
    outcome: RootOutcome,
    category: RootCommandCategory = RootCommandCategory.Test,
    code: Int = if (outcome == RootOutcome.Success) 0 else 1,
    stdout: String = "",
    circuitOpen: Boolean = outcome in setOf(
        RootOutcome.Denied,
        RootOutcome.Timeout,
        RootOutcome.Exception,
        RootOutcome.DestroyFailed,
        RootOutcome.CircuitOpen,
    ),
): RootResult = RootResult(
    code = code,
    stdout = stdout,
    stderr = if (outcome == RootOutcome.Success) "" else outcome.value,
    outcome = outcome,
    diagnostic = RootDiagnostic(
        requestId = "test",
        category = category,
        startedAtMillis = 10,
        endedAtMillis = 20,
        pid = 123,
        outcome = outcome,
        aliveAfterDestroy = when (outcome) {
            RootOutcome.Timeout -> false
            RootOutcome.DestroyFailed -> true
            else -> null
        },
        circuitOpen = circuitOpen,
        failureCount = if (circuitOpen) 1 else 0,
    ),
)
