package io.github.nongfsq.usbdebugguard

import android.content.Context

interface GuardSettings {
    fun serviceEnabled(): Boolean
    fun setServiceEnabled(enabled: Boolean)
    fun requireAdb(): Boolean
    fun lockOnDisconnect(): Boolean
    fun dismissKeyguard(): Boolean
    fun guardMode(): GuardMode
    fun guardedBrightness(): Int
    fun guarded(): Boolean
    fun setGuarded(guarded: Boolean)
    fun setLastStatus(status: GuardStatus)
    fun setLastAction(action: String)
    fun snapshotPending(): Boolean
    fun guardMutationStarted(): Boolean
    fun markGuardMutationStarted()
    fun saveSnapshot(snapshot: DisplaySettingsSnapshot)
    fun loadSnapshot(): DisplaySettingsSnapshot
    fun clearSnapshot()
    fun stopPending(): Boolean
    fun setStopPending(pending: Boolean)
}

class AndroidGuardSettings(private val context: Context) : GuardSettings {
    override fun serviceEnabled(): Boolean = GuardPrefs.serviceEnabled(context)

    override fun setServiceEnabled(enabled: Boolean) {
        GuardPrefs.setServiceEnabled(context, enabled)
    }

    override fun requireAdb(): Boolean = GuardPrefs.requireAdb(context)

    override fun lockOnDisconnect(): Boolean = GuardPrefs.lockOnDisconnect(context)

    override fun dismissKeyguard(): Boolean = GuardPrefs.dismissKeyguard(context)

    override fun guardMode(): GuardMode = GuardPrefs.guardMode(context)

    override fun guardedBrightness(): Int = GuardPrefs.guardedBrightness(context)

    override fun guarded(): Boolean = GuardPrefs.isGuarded(context)

    override fun setGuarded(guarded: Boolean) {
        GuardPrefs.setGuarded(context, guarded)
    }

    override fun setLastStatus(status: GuardStatus) {
        GuardPrefs.setLastStatus(context, status)
    }

    override fun setLastAction(action: String) {
        GuardPrefs.setLastAction(context, action)
    }

    override fun snapshotPending(): Boolean = GuardPrefs.snapshotPending(context)

    override fun guardMutationStarted(): Boolean = GuardPrefs.guardMutationStarted(context)

    override fun markGuardMutationStarted() {
        GuardPrefs.markGuardMutationStarted(context)
    }

    override fun saveSnapshot(snapshot: DisplaySettingsSnapshot) {
        GuardPrefs.saveSnapshot(context, snapshot)
    }

    override fun loadSnapshot(): DisplaySettingsSnapshot = GuardPrefs.loadSnapshot(context)

    override fun clearSnapshot() {
        GuardPrefs.clearSnapshot(context)
    }

    override fun stopPending(): Boolean = GuardPrefs.stopPending(context)

    override fun setStopPending(pending: Boolean) {
        GuardPrefs.setStopPending(context, pending)
    }
}
