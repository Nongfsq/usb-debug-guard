package io.github.nongfsq.usbdebugguard

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootControllerTest {
    @Test
    fun normalSuccessKeepsCircuitClosed() {
        val store = InMemoryRootSafetyStore()
        val executor = FakeRootExecutor { execution(it, RootOutcome.Success, stdout = "uid=0") }
        val controller = RootController(executor, store)

        val result = controller.run(RootCommandCategory.Test, "id")

        assertTrue(result.ok)
        assertEquals("uid=0", result.stdout)
        assertFalse(controller.circuit().open)
        assertEquals(1, executor.callCount.get())
    }

    @Test
    fun userDenialOpensCircuitImmediately() {
        val store = InMemoryRootSafetyStore()
        val executor = FakeRootExecutor { execution(it, RootOutcome.Denied) }
        val controller = RootController(executor, store)

        val result = controller.run(RootCommandCategory.ApplyGuard, "PAYLOAD")

        assertEquals(RootOutcome.Denied, result.outcome)
        assertTrue(controller.circuit().open)
        assertEquals(RootOutcome.Denied, controller.circuit().reason)
    }

    @Test
    fun revokedAuthorizationAfterSuccessOpensCircuit() {
        var granted = true
        val store = InMemoryRootSafetyStore()
        val executor = FakeRootExecutor {
            execution(it, if (granted) RootOutcome.Success else RootOutcome.Denied)
        }
        val controller = RootController(executor, store)

        assertTrue(controller.run(RootCommandCategory.Test, "id").ok)
        granted = false
        assertEquals(RootOutcome.Denied, controller.run(RootCommandCategory.LockScreen, "PAYLOAD").outcome)
        assertTrue(controller.circuit().open)
        assertEquals(2, executor.callCount.get())
    }

    @Test
    fun executorExceptionOpensCircuit() {
        val store = InMemoryRootSafetyStore()
        val executor = FakeRootExecutor { throw IllegalStateException("boom") }
        val controller = RootController(executor, store)

        val result = controller.run(RootCommandCategory.Test, "id")

        assertEquals(RootOutcome.Exception, result.outcome)
        assertTrue(controller.circuit().open)
        assertEquals(1, controller.circuit().failureCount)
    }

    @Test
    fun slowRequestThatEventuallySucceedsDoesNotOpenCircuit() {
        val store = InMemoryRootSafetyStore()
        val executor = FakeRootExecutor {
            Thread.sleep(60)
            execution(it, RootOutcome.Success)
        }
        val controller = RootController(executor, store)

        val result = controller.run(RootCommandCategory.Test, "id")

        assertTrue(result.ok)
        assertFalse(controller.circuit().open)
    }

    @Test
    fun neverReturningModelOpensCircuitAndAutomaticCallsStopGrowing() {
        val store = InMemoryRootSafetyStore()
        val executor = FakeRootExecutor {
            execution(it, RootOutcome.Timeout, code = -2, aliveAfterDestroy = false)
        }
        val controller = RootController(executor, store)

        assertEquals(RootOutcome.Timeout, controller.run(RootCommandCategory.CaptureSettings, "PAYLOAD").outcome)
        repeat(20) {
            assertEquals(RootOutcome.CircuitOpen, controller.run(RootCommandCategory.ApplyGuard, "PAYLOAD").outcome)
        }

        assertEquals(1, executor.callCount.get())
        assertEquals(1, controller.circuit().failureCount)
    }

    @Test
    fun concurrentTickAndButtonRequestsAreSingleFlight() {
        val store = InMemoryRootSafetyStore()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = FakeRootExecutor {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            execution(it, RootOutcome.Success)
        }
        val controller = RootController(executor, store)
        val pool = Executors.newFixedThreadPool(8)

        val futures = (1..8).map { index ->
            pool.submit<RootResult> {
                controller.run(
                    if (index % 2 == 0) RootCommandCategory.ApplyGuard else RootCommandCategory.Test,
                    "PAYLOAD",
                )
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        release.countDown()
        futures.forEach { assertTrue(it.get(3, TimeUnit.SECONDS).ok) }
        pool.shutdownNow()

        assertEquals(1, executor.maxActive.get())
        assertEquals(8, executor.callCount.get())
    }

    @Test
    fun serviceReplacementAndOldControllerShareOneGlobalFlight() {
        val globalLock = ReentrantLock(true)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        fun executor() = FakeRootExecutor {
            val nowActive = active.incrementAndGet()
            maxActive.updateAndGet { current -> maxOf(current, nowActive) }
            try {
                Thread.sleep(50)
                execution(it, RootOutcome.Success)
            } finally {
                active.decrementAndGet()
            }
        }
        val oldController = RootController(executor(), InMemoryRootSafetyStore(), globalLock)
        val rebuiltController = RootController(executor(), InMemoryRootSafetyStore(), globalLock)
        val pool = Executors.newFixedThreadPool(2)

        val oldRequest = pool.submit<RootResult> {
            oldController.run(RootCommandCategory.ApplyGuard, "PAYLOAD")
        }
        val rebuiltRequest = pool.submit<RootResult> {
            rebuiltController.run(RootCommandCategory.Test, "PAYLOAD")
        }
        assertTrue(oldRequest.get(2, TimeUnit.SECONDS).ok)
        assertTrue(rebuiltRequest.get(2, TimeUnit.SECONDS).ok)
        pool.shutdownNow()

        assertEquals(1, maxActive.get())
    }

    @Test
    fun startStickyReconstructionPreservesOpenCircuit() {
        val store = InMemoryRootSafetyStore()
        val firstExecutor = FakeRootExecutor {
            execution(it, RootOutcome.Timeout, code = -2, aliveAfterDestroy = false)
        }
        RootController(firstExecutor, store).run(RootCommandCategory.Test, "id")
        val rebuiltExecutor = FakeRootExecutor { execution(it, RootOutcome.Success) }
        val rebuiltController = RootController(rebuiltExecutor, store)

        val result = rebuiltController.run(RootCommandCategory.ApplyGuard, "PAYLOAD")

        assertEquals(RootOutcome.CircuitOpen, result.outcome)
        assertEquals(0, rebuiltExecutor.callCount.get())
    }

    @Test
    fun bootReceiverStyleReconstructionPreservesOpenCircuit() {
        val store = InMemoryRootSafetyStore()
        store.open(RootOutcome.DestroyFailed)
        val executor = FakeRootExecutor { execution(it, RootOutcome.Success) }
        val bootStartedController = RootController(executor, store)

        val result = bootStartedController.run(RootCommandCategory.RestoreSettings, "PAYLOAD")

        assertEquals(RootOutcome.CircuitOpen, result.outcome)
        assertEquals(0, executor.callCount.get())
    }

    @Test
    fun onlyExplicitManualRetryClearsCircuit() {
        val store = InMemoryRootSafetyStore()
        store.open(RootOutcome.Timeout)
        val executor = FakeRootExecutor { execution(it, RootOutcome.Success) }
        val controller = RootController(executor, store)

        assertEquals(RootOutcome.CircuitOpen, controller.run(RootCommandCategory.Test, "id").outcome)
        val retried = controller.run(RootCommandCategory.Test, "id", manualRetry = true)

        assertTrue(retried.ok)
        assertFalse(controller.circuit().open)
        assertEquals(1, executor.callCount.get())
    }

    @Test
    fun manualRetryUsesCooldownAndOnlyOneAttemptPerIncident() {
        val now = AtomicLong(1_000L)
        val store = InMemoryRootSafetyStore()
        val executor = FakeRootExecutor { request ->
            if (request.category == RootCommandCategory.CaptureSettings) {
                execution(request, RootOutcome.Timeout, code = -2, aliveAfterDestroy = false)
            } else {
                execution(request, RootOutcome.Timeout, code = -2, aliveAfterDestroy = false)
            }
        }
        val controller = RootController(
            executor = executor,
            safetyStore = store,
            clock = now::get,
            manualRetryCooldownMillis = 5_000L,
        )

        assertEquals(
            RootOutcome.Timeout,
            controller.run(RootCommandCategory.CaptureSettings, "PAYLOAD").outcome,
        )
        assertEquals(
            RootOutcome.CircuitOpen,
            controller.run(RootCommandCategory.Test, "id", manualRetry = true).outcome,
        )
        assertEquals(1, executor.callCount.get())

        now.set(6_000L)
        assertEquals(
            RootOutcome.Timeout,
            controller.run(RootCommandCategory.Test, "id", manualRetry = true).outcome,
        )
        repeat(10) {
            assertEquals(
                RootOutcome.CircuitOpen,
                controller.run(RootCommandCategory.Test, "id", manualRetry = true).outcome,
            )
        }
        assertEquals(2, executor.callCount.get())
        assertTrue(controller.circuit().manualRetryConsumed)
    }

    @Test
    fun consumedManualRetryRemainsBlockedAfterControllerReconstruction() {
        val store = InMemoryRootSafetyStore()
        store.open(RootOutcome.Timeout)
        val firstExecutor = FakeRootExecutor {
            execution(it, RootOutcome.Timeout, code = -2, aliveAfterDestroy = false)
        }
        val first = RootController(firstExecutor, store, manualRetryCooldownMillis = 0L)
        assertEquals(
            RootOutcome.Timeout,
            first.run(RootCommandCategory.Test, "id", manualRetry = true).outcome,
        )

        val rebuiltExecutor = FakeRootExecutor { execution(it, RootOutcome.Success) }
        val rebuilt = RootController(rebuiltExecutor, store, manualRetryCooldownMillis = 0L)
        assertEquals(
            RootOutcome.CircuitOpen,
            rebuilt.run(RootCommandCategory.Test, "id", manualRetry = true).outcome,
        )
        assertEquals(0, rebuiltExecutor.callCount.get())
    }

    @Test
    fun invalidSuccessfulOutputIsConvertedToExceptionAndOpensCircuit() {
        val store = InMemoryRootSafetyStore()
        val executor = FakeRootExecutor { execution(it, RootOutcome.Success, stdout = "unexpected") }
        val controller = RootController(executor, store)

        val result = controller.run(
            RootCommandCategory.CaptureSettings,
            "PAYLOAD",
            outputValidator = { it.startsWith("UDG_SETTING_") },
        )

        assertEquals(RootOutcome.Exception, result.outcome)
        assertTrue(controller.circuit().open)
        assertEquals(1, executor.callCount.get())
    }

    @Test
    fun structuredDiagnosticsContainMetadataButNoCommandOrOutput() {
        val store = InMemoryRootSafetyStore()
        val executor = FakeRootExecutor {
            execution(it, RootOutcome.Success, stdout = "SENSITIVE_OUTPUT")
        }
        val controller = RootController(executor, store, requestId = { "request-42" })

        controller.run(RootCommandCategory.Test, "SENSITIVE_COMMAND")
        val log = store.diagnostics.single().structuredLog()

        assertTrue(log.contains("requestId=request-42"))
        assertTrue(log.contains("category=test"))
        assertFalse(log.contains("SENSITIVE_COMMAND"))
        assertFalse(log.contains("SENSITIVE_OUTPUT"))
    }
}
