package io.github.nongfsq.usbdebugguard

enum class LocaleMode(val value: String) {
    System("system"),
    English("en"),
    SimplifiedChinese("zh-CN");

    companion object {
        fun from(value: String?): LocaleMode =
            entries.firstOrNull { it.value == value } ?: System
    }
}
