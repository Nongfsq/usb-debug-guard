package io.github.nongfsq.usbdebugguard

data class DisplaySettingsSnapshot(
    val stayOnWhilePluggedIn: String,
    val brightnessMode: String,
    val screenBrightness: String,
    val screenOffTimeout: String,
) {
    fun restoreCommand(): String = listOf(
        "settings put global stay_on_while_plugged_in ${stayOnWhilePluggedIn.safeNumber("0")}",
        "settings put system screen_brightness_mode ${brightnessMode.safeNumber("1")}",
        "settings put system screen_brightness ${screenBrightness.safeNumber("120")}",
        "settings put system screen_off_timeout ${screenOffTimeout.safeNumber("30000")}",
    ).joinToString("; ")

    companion object {
        val fallback = DisplaySettingsSnapshot("0", "1", "120", "30000")
    }
}

private fun String?.safeNumber(fallback: String): String {
    if (isNullOrBlank() || this == "null") return fallback
    val cleaned = filter { it.isDigit() || it == '-' }
    return cleaned.ifBlank { fallback }
}
