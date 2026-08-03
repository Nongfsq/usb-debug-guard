package io.github.nongfsq.usbdebugguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySettingsSnapshotTest {
    @Test
    fun namedProtocolIgnoresUnrelatedBannerWithoutShiftingValues() {
        val output = """
            KernelSU banner
            UDG_SETTING_stay_on=7
            UDG_SETTING_brightness_mode=1
            UDG_SETTING_screen_brightness=177
            UDG_SETTING_screen_off_timeout=45000
        """.trimIndent()

        val snapshot = DisplaySettingsSnapshot.fromProtocol(output)

        assertEquals("7", snapshot?.stayOnWhilePluggedIn)
        assertEquals("177", snapshot?.screenBrightness)
        assertEquals("45000", snapshot?.screenOffTimeout)
    }

    @Test
    fun missingDuplicateOrNonNumericFieldsAreRejected() {
        assertNull(DisplaySettingsSnapshot.fromProtocol("UDG_SETTING_stay_on=0"))
        assertNull(
            DisplaySettingsSnapshot.fromProtocol(
                """
                    UDG_SETTING_stay_on=0
                    UDG_SETTING_stay_on=1
                    UDG_SETTING_brightness_mode=1
                    UDG_SETTING_screen_brightness=120
                    UDG_SETTING_screen_off_timeout=30000
                """.trimIndent()
            )
        )
        assertNull(
            DisplaySettingsSnapshot.fromProtocol(
                """
                    UDG_SETTING_stay_on=0
                    UDG_SETTING_brightness_mode=1
                    UDG_SETTING_screen_brightness=120;input keyevent POWER
                    UDG_SETTING_screen_off_timeout=30000
                """.trimIndent()
            )
        )
    }

    @Test
    fun restoreCommandUsesFailFastChainingOnly() {
        val command = DisplaySettingsSnapshot("0", "1", "120", "30000").restoreCommand()

        assertTrue(command.contains(" && "))
        assertTrue(";" !in command)
    }

    @Test
    fun absentOriginalSettingIsRestoredByDeletingIt() {
        val output = """
            UDG_SETTING_stay_on=null
            UDG_SETTING_brightness_mode=1
            UDG_SETTING_screen_brightness=120
            UDG_SETTING_screen_off_timeout=30000
        """.trimIndent()

        val command = DisplaySettingsSnapshot.fromProtocol(output)?.restoreCommand().orEmpty()

        assertTrue(command.startsWith("settings delete global stay_on_while_plugged_in"))
    }
}
