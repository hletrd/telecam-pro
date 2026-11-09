package me.hletrd.telecampro.ui.overlays

import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.offerVideoEv
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

    private fun List<ReadoutToken>.shown() = map { it.text to it.auto }

    // UI review 2026-11-09: outside M the compact view showed no exposure at all.
    @Test
    fun `P, S and ISO lead with the mode, dim what the camera chooses, and show a non-zero EV`() {
        val p = exposureReadoutTokens(ExposureMode.PROGRAM, 2.3f, 8_000_000L, 400, 4_000_000L, 800, 0.7f)
        assertEquals(
            listOf("P" to false, "F2.3" to false, "1/250s" to true, "ISO 800" to true, "+0.7" to false),
            p.shown(),
        )
        val s = exposureReadoutTokens(ExposureMode.SHUTTER, null, 1_000_000L, 400, null, 1600, 0f)
        assertEquals(listOf("S" to false, "1/1000s" to false, "ISO 1600" to true), s.shown())
        val iso = exposureReadoutTokens(ExposureMode.ISO, null, 8_000_000L, 200, null, null, -1f)
        assertEquals(listOf("ISO" to false, "1/125s" to true, "ISO 200" to false, "-1.0" to false), iso.shown())
        // M keeps its triple and never shows EV (it moves nothing there).
        val m = exposureReadoutTokens(ExposureMode.MANUAL, 2.3f, 16_666_667L, 6400, 33_000_000L, 100, 1f)
        assertEquals(listOf("F2.3" to false, "1/60s" to false, "ISO 6400" to false), m.shown())
    }

    // UI review 2026-11-09: held sideways each value turns upright as a label over a value.
    @Test
    fun `held sideways each value stacks its label over the number, the mode letter alone`() {
        val p = exposureReadoutTokens(ExposureMode.PROGRAM, 2.3f, 8_000_000L, 400, 4_000_000L, 800, 0.7f)
        assertEquals(
            listOf(null to "P", "F" to "2.3", "SS" to "1/250s", "ISO" to "800", "EV" to "+0.7"),
            p.map { it.stackLabel to it.stackValue },
        )
    }

    @Test
    fun `an untouched video bar takes the EV default once, a changed one is left alone`() {
        assertEquals(FnSlot.VIDEO_DEFAULT, offerVideoEv(FnSlot.VIDEO_DEFAULT_BEFORE_EV, offered = false))
        assertEquals(FnSlot.VIDEO_DEFAULT_BEFORE_EV, offerVideoEv(FnSlot.VIDEO_DEFAULT_BEFORE_EV, offered = true))
        val changed = FnSlot.VIDEO_DEFAULT_BEFORE_EV.reversed()
        assertEquals(changed, offerVideoEv(changed, offered = false))
    }
}
