package io.github.nongfsq.usbdebugguard

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.util.concurrent.locks.ReentrantLock

class AndroidRootSafetyStore(context: Context) : RootSafetyStore {
    private val appContext = context.applicationContext

    override fun snapshot(): RootCircuitSnapshot {
        val prefs = GuardPrefs.prefs(appContext)
        return RootCircuitSnapshot(
            open = prefs.getBoolean(KEY_CIRCUIT_OPEN, false),
            reason = RootOutcome.from(prefs.getString(KEY_CIRCUIT_REASON, null)),
            failureCount = prefs.getInt(KEY_FAILURE_COUNT, 0),
            lastOutcome = RootOutcome.from(prefs.getString(KEY_LAST_OUTCOME, null)),
            manualRetryConsumed = prefs.getBoolean(KEY_MANUAL_RETRY_CONSUMED, false),
            retryAllowedAtMillis = prefs.getLong(KEY_RETRY_ALLOWED_AT, 0L),
        )
    }

    @Synchronized
    override fun open(reason: RootOutcome, retryAllowedAtMillis: Long): RootCircuitSnapshot {
        val current = snapshot()
        val continuingIncident = current.failureCount > 0
        val next = RootCircuitSnapshot(
            open = true,
            reason = reason,
            failureCount = current.failureCount + 1,
            lastOutcome = reason,
            manualRetryConsumed = if (continuingIncident) current.manualRetryConsumed else false,
            retryAllowedAtMillis = if (continuingIncident) {
                current.retryAllowedAtMillis
            } else {
                retryAllowedAtMillis
            },
        )
        GuardPrefs.prefs(appContext).edit(commit = true) {
            putBoolean(KEY_CIRCUIT_OPEN, true)
            putString(KEY_CIRCUIT_REASON, reason.value)
            putInt(KEY_FAILURE_COUNT, next.failureCount)
            putString(KEY_LAST_OUTCOME, reason.value)
            putBoolean(KEY_MANUAL_RETRY_CONSUMED, next.manualRetryConsumed)
            putLong(KEY_RETRY_ALLOWED_AT, next.retryAllowedAtMillis)
        }
        return next
    }

    @Synchronized
    override fun tryBeginManualRetry(nowMillis: Long): RootCircuitSnapshot? {
        val current = snapshot()
        if (!current.open || current.manualRetryConsumed || nowMillis < current.retryAllowedAtMillis) {
            return null
        }
        val next = current.copy(manualRetryConsumed = true)
        GuardPrefs.prefs(appContext).edit(commit = true) {
            putBoolean(KEY_MANUAL_RETRY_CONSUMED, true)
        }
        return next
    }

    @Synchronized
    override fun closeAfterSuccess(): RootCircuitSnapshot {
        val next = RootCircuitSnapshot(lastOutcome = RootOutcome.Success)
        GuardPrefs.prefs(appContext).edit(commit = true) {
            putBoolean(KEY_CIRCUIT_OPEN, false)
            remove(KEY_CIRCUIT_REASON)
            putInt(KEY_FAILURE_COUNT, 0)
            putString(KEY_LAST_OUTCOME, RootOutcome.Success.value)
            putBoolean(KEY_MANUAL_RETRY_CONSUMED, false)
            putLong(KEY_RETRY_ALLOWED_AT, 0L)
        }
        return next
    }

    override fun record(diagnostic: RootDiagnostic) {
        Log.i(LOG_TAG, diagnostic.structuredLog())
        GuardPrefs.prefs(appContext).edit {
            putString(KEY_LAST_REQUEST_ID, diagnostic.requestId)
            putString(KEY_LAST_CATEGORY, diagnostic.category.value)
            putLong(KEY_LAST_STARTED_AT, diagnostic.startedAtMillis)
            putLong(KEY_LAST_ENDED_AT, diagnostic.endedAtMillis)
            putLong(KEY_LAST_PID, diagnostic.pid ?: -1L)
            putString(KEY_LAST_OUTCOME, diagnostic.outcome.value)
            putString(KEY_LAST_ALIVE, diagnostic.aliveAfterDestroy?.toString() ?: "not-applicable")
            putBoolean(KEY_LAST_CIRCUIT_OPEN, diagnostic.circuitOpen)
            putInt(KEY_LAST_FAILURE_COUNT, diagnostic.failureCount)
        }
    }

    companion object {
        private const val LOG_TAG = "UsbDebugGuardRoot"
        private const val KEY_CIRCUIT_OPEN = "root_circuit_open"
        private const val KEY_CIRCUIT_REASON = "root_circuit_reason"
        private const val KEY_FAILURE_COUNT = "root_failure_count"
        private const val KEY_MANUAL_RETRY_CONSUMED = "root_manual_retry_consumed"
        private const val KEY_RETRY_ALLOWED_AT = "root_retry_allowed_at"
        private const val KEY_LAST_OUTCOME = "root_last_outcome"
        private const val KEY_LAST_REQUEST_ID = "root_last_request_id"
        private const val KEY_LAST_CATEGORY = "root_last_category"
        private const val KEY_LAST_STARTED_AT = "root_last_started_at"
        private const val KEY_LAST_ENDED_AT = "root_last_ended_at"
        private const val KEY_LAST_PID = "root_last_pid"
        private const val KEY_LAST_ALIVE = "root_last_alive_after_destroy"
        private const val KEY_LAST_CIRCUIT_OPEN = "root_last_circuit_open"
        private const val KEY_LAST_FAILURE_COUNT = "root_last_failure_count"
    }
}

object RootRuntime {
    private val lifecycleLock = Any()
    private val globalExecutionLock = ReentrantLock(true)

    @Volatile
    private var controller: RootController? = null

    fun get(context: Context): RootController {
        controller?.let { return it }
        return synchronized(lifecycleLock) {
            controller ?: RootController(
                executor = ProcessRootExecutor(),
                safetyStore = AndroidRootSafetyStore(context.applicationContext),
                executionLock = globalExecutionLock,
            ).also { controller = it }
        }
    }

    fun shutdown() {
        val current = synchronized(lifecycleLock) {
            controller.also { controller = null }
        }
        current?.close()
    }
}
