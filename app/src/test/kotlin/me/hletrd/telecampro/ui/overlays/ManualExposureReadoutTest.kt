package me.hletrd.telecampro.ui.overlays

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualExposureReadoutTest {

    @Test
    fun `M readout shows aperture, shutter and ISO with photographic formatting`() {
        assertEquals("F2.6   1/250s   ISO 800", manualExposureReadout(2.59f, 4_000_000L, 800))
        assertEquals("F1.7   1.0s   ISO 12800", manualExposureReadout(1.69f, 1_000_000_000L, 12800))
    }

    @Test
    fun `an unknown aperture drops the field instead of printing F0`() {
        assertEquals("1/60s   ISO 100", manualExposureReadout(null, 16_666_667L, 100))
        assertEquals("1/60s   ISO 100", manualExposureReadout(0f, 16_666_667L, 100))
    }
}
