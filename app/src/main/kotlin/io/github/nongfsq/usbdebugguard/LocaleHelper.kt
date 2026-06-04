package io.github.nongfsq.usbdebugguard

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {
    fun wrap(context: Context): Context {
        val mode = GuardPrefs.localeMode(context)
        if (mode == LocaleMode.System || Build.VERSION.SDK_INT >= 33) {
            return context
        }
        val locale = mode.locale()
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration)
    }

    fun apply(context: Context, mode: LocaleMode) {
        GuardPrefs.setLocaleMode(context, mode)
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            manager.applicationLocales = if (mode == LocaleMode.System) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(mode.value)
            }
        }
    }

    private fun LocaleMode.locale(): Locale = when (this) {
        LocaleMode.English -> Locale.ENGLISH
        LocaleMode.SimplifiedChinese -> Locale.SIMPLIFIED_CHINESE
        LocaleMode.System -> Locale.getDefault()
    }
}
