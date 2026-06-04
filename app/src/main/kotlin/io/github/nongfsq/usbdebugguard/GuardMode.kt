package io.github.nongfsq.usbdebugguard

enum class GuardMode(val value: String) {
    ScreenOff("screen_off"),
    DimAwake("dim_awake");

    companion object {
        fun from(value: String?): GuardMode = entries.firstOrNull { it.value == value } ?: ScreenOff
    }
}
