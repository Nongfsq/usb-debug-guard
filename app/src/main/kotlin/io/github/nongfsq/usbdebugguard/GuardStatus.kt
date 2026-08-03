package io.github.nongfsq.usbdebugguard

enum class GuardStatus(val value: String) {
    Protected("protected"),
    Idle("idle"),
    WaitingUsb("waiting_usb"),
    WaitingAdb("waiting_adb"),
    RootRequired("root_required"),
    RootUnavailable("root_unavailable"),
    CircuitOpen("circuit_open"),
    RestoreFailed("restore_failed"),
    Unknown("unknown");

    companion object {
        fun from(value: String?): GuardStatus = entries.firstOrNull { it.value == value } ?: Unknown
    }
}
