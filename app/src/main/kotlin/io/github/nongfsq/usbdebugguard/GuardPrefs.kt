package io.github.nongfsq.usbdebugguard

import android.content.Context
import android.content.SharedPreferences

object GuardPrefs {
    private const val NAME = "guard"
    private const val SAVED_PREFIX = "saved_"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun serviceEnabled(context: Context): Boolean =
        prefs(context).getBoolean("service_enabled", false)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("service_enabled", enabled).apply()
    }

    fun requireAdb(context: Context): Boolean =
        prefs(context).getBoolean("require_adb", true)

    fun setRequireAdb(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("require_adb", enabled).apply()
    }

    fun lockOnDisconnect(context: Context): Boolean =
        prefs(context).getBoolean("lock_on_disconnect", true)

    fun setLockOnDisconnect(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("lock_on_disconnect", enabled).apply()
    }

    fun dismissKeyguard(context: Context): Boolean =
        prefs(context).getBoolean("dismiss_keyguard", false)

    fun setDismissKeyguard(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("dismiss_keyguard", enabled).apply()
    }

    fun guardMode(context: Context): GuardMode =
        GuardMode.from(prefs(context).getString("guard_mode", GuardMode.ScreenOff.value))

    fun setGuardMode(context: Context, mode: GuardMode) {
        prefs(context).edit().putString("guard_mode", mode.value).apply()
    }

    fun guardedBrightness(context: Context): Int =
        prefs(context).getInt("guarded_brightness", 1).coerceIn(1, 255)

    fun isGuarded(context: Context): Boolean =
        prefs(context).getBoolean("is_guarded", false)

    fun setGuarded(context: Context, guarded: Boolean) {
        prefs(context).edit().putBoolean("is_guarded", guarded).apply()
    }

    fun lastStatus(context: Context): GuardStatus =
        GuardStatus.from(prefs(context).getString("last_status", GuardStatus.Unknown.value))

    fun setLastStatus(context: Context, status: GuardStatus) {
        prefs(context).edit().putString("last_status", status.value).apply()
    }

    fun lastAction(context: Context): String =
        prefs(context).getString("last_action", "") ?: ""

    fun setLastAction(context: Context, action: String) {
        prefs(context).edit().putString("last_action", action).apply()
    }

    fun saveSnapshot(context: Context, snapshot: DisplaySettingsSnapshot) {
        prefs(context).edit()
            .putString(SAVED_PREFIX + "stay_on", snapshot.stayOnWhilePluggedIn)
            .putString(SAVED_PREFIX + "brightness_mode", snapshot.brightnessMode)
            .putString(SAVED_PREFIX + "screen_brightness", snapshot.screenBrightness)
            .putString(SAVED_PREFIX + "screen_off_timeout", snapshot.screenOffTimeout)
            .apply()
    }

    fun loadSnapshot(context: Context): DisplaySettingsSnapshot {
        val prefs = prefs(context)
        return DisplaySettingsSnapshot(
            prefs.getString(SAVED_PREFIX + "stay_on", "0") ?: "0",
            prefs.getString(SAVED_PREFIX + "brightness_mode", "1") ?: "1",
            prefs.getString(SAVED_PREFIX + "screen_brightness", "120") ?: "120",
            prefs.getString(SAVED_PREFIX + "screen_off_timeout", "30000") ?: "30000",
        )
    }
}
