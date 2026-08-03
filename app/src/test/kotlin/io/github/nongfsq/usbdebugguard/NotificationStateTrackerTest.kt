package io.github.nongfsq.usbdebugguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStateTrackerTest {
    @Test
    fun notificationUpdatesOnlyWhenVisibleStateOrDiagnosticChanges() {
        val tracker = NotificationStateTracker()
        val waiting = NotificationState(GuardStatus.WaitingUsb, false, 0, null)

        assertTrue(tracker.shouldNotify(waiting))
        assertFalse(tracker.shouldNotify(waiting))
        assertTrue(tracker.shouldNotify(waiting.copy(status = GuardStatus.WaitingAdb)))
        assertFalse(tracker.shouldNotify(waiting.copy(status = GuardStatus.WaitingAdb)))
        assertTrue(
            tracker.shouldNotify(
                NotificationState(GuardStatus.CircuitOpen, true, 1, RootOutcome.Timeout)
            )
        )
        assertFalse(
            tracker.shouldNotify(
                NotificationState(GuardStatus.CircuitOpen, true, 1, RootOutcome.Timeout)
            )
        )
        assertTrue(
            tracker.shouldNotify(
                NotificationState(GuardStatus.CircuitOpen, true, 2, RootOutcome.DestroyFailed)
            )
        )
    }
}
