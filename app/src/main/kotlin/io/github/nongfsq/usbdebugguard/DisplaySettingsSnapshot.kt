package io.github.nongfsq.usbdebugguard

data class DisplaySettingsSnapshot(
    val stayOnWhilePluggedIn: String,
    val brightnessMode: String,
    val screenBrightness: String,
    val screenOffTimeout: String,
) {
    fun restoreCommand(): String = listOf(
        restoreSetting("global", "stay_on_while_plugged_in", stayOnWhilePluggedIn, "0"),
        restoreSetting("system", "screen_brightness_mode", brightnessMode, "1"),
        restoreSetting("system", "screen_brightness", screenBrightness, "120"),
        restoreSetting("system", "screen_off_timeout", screenOffTimeout, "30000"),
    ).joinToString(" && ")

    companion object {
        val fallback = DisplaySettingsSnapshot("0", "1", "120", "30000")

        val captureCommand: String = listOf(
            "printf '${PROTOCOL_PREFIX}stay_on=' && settings get global stay_on_while_plugged_in",
            "printf '${PROTOCOL_PREFIX}brightness_mode=' && settings get system screen_brightness_mode",
            "printf '${PROTOCOL_PREFIX}screen_brightness=' && settings get system screen_brightness",
            "printf '${PROTOCOL_PREFIX}screen_off_timeout=' && settings get system screen_off_timeout",
        ).joinToString(" && ")

        fun fromProtocol(output: String): DisplaySettingsSnapshot? {
            val values = linkedMapOf<String, String>()
            output.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith(PROTOCOL_PREFIX)) return@forEach
                val separator = trimmed.indexOf('=')
                if (separator <= PROTOCOL_PREFIX.length) return null
                val key = trimmed.substring(PROTOCOL_PREFIX.length, separator)
                val value = trimmed.substring(separator + 1)
                if (key !in REQUIRED_KEYS || key in values || !value.isSettingValue()) return null
                values[key] = value
            }
            if (values.keys != REQUIRED_KEYS) return null
            return DisplaySettingsSnapshot(
                stayOnWhilePluggedIn = values.getValue("stay_on"),
                brightnessMode = values.getValue("brightness_mode"),
                screenBrightness = values.getValue("screen_brightness"),
                screenOffTimeout = values.getValue("screen_off_timeout"),
            )
        }

        private const val PROTOCOL_PREFIX = "UDG_SETTING_"
        private val REQUIRED_KEYS = linkedSetOf(
            "stay_on",
            "brightness_mode",
            "screen_brightness",
            "screen_off_timeout",
        )
    }
}

private fun restoreSetting(namespace: String, key: String, value: String?, fallback: String): String =
    if (value.equals("null", ignoreCase = true)) {
        "settings delete $namespace $key"
    } else {
        "settings put $namespace $key ${if (value != null && value.isStrictNumber()) value else fallback}"
    }

private fun String.isStrictNumber(): Boolean = matches(Regex("-?[0-9]+"))

private fun String.isSettingValue(): Boolean = isStrictNumber() || equals("null", ignoreCase = true)
