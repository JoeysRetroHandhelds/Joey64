package com.joeysretro.joey64.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModesTest {
    @Test fun makersModesAreKept() {
        // Seen on our devices 2026-11-18: AYN / Retroid, AYANEO / KONKR, Anbernic.
        for (n in listOf("performance_mode", "fan_mode", "open_performance_mode", "cpu_mode", "custom_fan_speed",
                         "is_quick_set_performance_and_fan_enable", "yt_large_cpu_max_frequencies"))
            assertTrue(n, DeviceModes.keep(n))
    }

    @Test fun othersAreLeftOut() {
        for (n in listOf("yt_gamepad_turbo_a", "ayaneo/share/equalizer_bass_boost", "fan_thermal_management_area",
                         "screen_brightness", "flip_button_layout", "abxy_mode", "performancemode_tile", "superfan"))
            assertFalse(n, DeviceModes.keep(n))
    }

    @Test fun lineReadsAsTheLog() {
        assertEquals("fan_mode=4 performance_mode=1 power_saver=off",
            DeviceModes.line(linkedMapOf("fan_mode" to "4", "performance_mode" to "1", "power_saver" to "off")))
        assertEquals("none", DeviceModes.line(emptyMap()))
    }
}
