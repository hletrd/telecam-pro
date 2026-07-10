package com.hletrd.findx9tele.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

/**
 * Pins the shutter-speed readout ([formatShutterSpeed]), including the [0.667 s, 1 s) band that
 * used to display as the nonsensical "1/1s".
 */
class ShutterSpeedFormatTest {

    private fun ns(seconds: Double): Long = (seconds * 1_000_000_000L).toLong()

    @Test
    fun `decimal readout uses a dot under a comma-decimal default locale`() {
        // A comma-decimal locale (German) renders "%.1f" of 0.75 as "0,8" — the pro readouts pin
        // Locale.US so a shutter speed always reads "0.8s". Restore the prior default afterwards.
        val prior = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("0.8s", formatShutterSpeed(750_000_000L)) // decimal-seconds band
            assertEquals("2.5s", formatShutterSpeed(2_500_000_000L)) // >=1 s decimal band
            assertFalse("0.8s must not read 0,8s", formatShutterSpeed(750_000_000L).contains(','))
            assertFalse("2.5s must not read 2,5s", formatShutterSpeed(2_500_000_000L).contains(','))
        } finally {
            Locale.setDefault(prior)
        }
    }

    @Test
    fun `fast speeds snap to conventional denominators`() {
        assertEquals("1/125s", formatShutterSpeed(ns(1.0 / 128))) // exact 2^k → camera-standard 1/125
        assertEquals("1/250s", formatShutterSpeed(ns(1.0 / 250)))
        assertEquals("1/8000s", formatShutterSpeed(ns(1.0 / 8000)))
        assertEquals("1/320s", formatShutterSpeed(ns(1.0 / 300))) // 300 snaps to the nearest standard stop
    }

    @Test
    fun `sub-second band without a conventional denominator shows decimal seconds`() {
        assertEquals("0.8s", formatShutterSpeed(ns(0.75))) // used to read "1/1s"
        assertEquals("0.7s", formatShutterSpeed(ns(0.70)))
        assertEquals("0.9s", formatShutterSpeed(ns(0.90)))
    }

    @Test
    fun `half second still reads as a fraction`() {
        assertEquals("1/2s", formatShutterSpeed(ns(0.5)))
    }

    @Test
    fun `one second and slower read as seconds`() {
        assertEquals("1.0s", formatShutterSpeed(ns(1.0)))
        assertEquals("2.5s", formatShutterSpeed(ns(2.5)))
        assertEquals("30s", formatShutterSpeed(ns(30.0)))
    }
}
