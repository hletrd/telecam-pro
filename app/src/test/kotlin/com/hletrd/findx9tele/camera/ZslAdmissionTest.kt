package com.hletrd.findx9tele.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pseudo-ZSL admission gate: a buffered preview frame may serve as the still ONLY when
 * its actual sensor values match the still's intended values — bright scenes serve at zero lag,
 * fluidity-cap-diverged dark scenes honestly real-capture.
 */
class ZslAdmissionTest {

    private val nowNs = 10_000_000_000L

    private fun intent(
        manualAe: Boolean = true,
        wantProcessed: Boolean = true,
        wantRaw: Boolean = false,
        flash: FlashMode = FlashMode.OFF,
        exposureNs: Long = 8_000_000L, // 1/125 s bright-light shot
        iso: Int = 400,
        zoomRatio: Float = 2f,
        gestureActive: Boolean = false,
    ) = ZslStillIntent(manualAe, wantProcessed, wantRaw, flash, exposureNs, iso, zoomRatio, gestureActive)

    private fun frame(
        ageNs: Long = 50_000_000L,
        exposureNs: Long? = 8_000_000L,
        iso: Int? = 400,
        zoomRatio: Float? = 2f,
    ) = ZslFrameFacts(nowNs - ageNs, exposureNs, iso, zoomRatio)

    // ---- intent eligibility ----

    @Test
    fun `bright app-side single shot is eligible and a matching frame serves`() {
        assertTrue(zslIntentEligible(intent()))
        assertTrue(zslFrameAdmissible(frame(), intent(), nowNs))
    }

    @Test
    fun `HAL-AE, RAW, AE-flash, gesture, and non-processed intents never consult the ring`() {
        assertFalse(zslIntentEligible(intent(manualAe = false)))
        assertFalse(zslIntentEligible(intent(wantRaw = true)))
        assertFalse(zslIntentEligible(intent(flash = FlashMode.AUTO)))
        assertFalse(zslIntentEligible(intent(flash = FlashMode.ON)))
        assertFalse(zslIntentEligible(intent(gestureActive = true)))
        assertFalse(zslIntentEligible(intent(wantProcessed = false)))
        // TORCH lights the buffered frames exactly like the still — eligible.
        assertTrue(zslIntentEligible(intent(flash = FlashMode.TORCH)))
    }

    // ---- the honest gate: actual vs intended sensor values ----

    @Test
    fun `fluidity-diverged dark frame refuses so the real capture keeps quality`() {
        // M-mode 2 s intended still; the wire preview rides the 66.7 ms cap at raised ISO.
        val darkIntent = intent(exposureNs = 2_000_000_000L, iso = 1_600)
        val wireFrame = frame(exposureNs = 66_666_667L, iso = 12_800)
        assertFalse(zslFrameAdmissible(wireFrame, darkIntent, nowNs))
    }

    @Test
    fun `sensor values inside a sixth of a stop admit, outside refuse`() {
        // 1/6 stop ≈ ×1.122. 5% off admits; 20% off refuses — each axis independently.
        assertTrue(zslFrameAdmissible(frame(exposureNs = 8_400_000L), intent(), nowNs))
        assertFalse(zslFrameAdmissible(frame(exposureNs = 9_600_000L), intent(), nowNs))
        assertTrue(zslFrameAdmissible(frame(iso = 420), intent(), nowNs))
        assertFalse(zslFrameAdmissible(frame(iso = 480), intent(), nowNs))
    }

    @Test
    fun `zoom mismatch beyond two percent refuses`() {
        // The mid-gesture wide-aimed frame (~1.2× wider) must never become a still.
        assertTrue(zslFrameAdmissible(frame(zoomRatio = 2.03f), intent(), nowNs))
        assertFalse(zslFrameAdmissible(frame(zoomRatio = 2.4f), intent(), nowNs))
        assertFalse(zslFrameAdmissible(frame(zoomRatio = 1.67f), intent(), nowNs))
    }

    // ---- freshness and defense ----

    @Test
    fun `stale and future frames refuse`() {
        assertTrue(zslFrameAdmissible(frame(ageNs = ZSL_MAX_FRAME_AGE_NS), intent(), nowNs))
        assertFalse(zslFrameAdmissible(frame(ageNs = ZSL_MAX_FRAME_AGE_NS + 1), intent(), nowNs))
        // Negative age = clock-domain surprise → refuse (fails to "never admits", never wrong frame).
        assertFalse(zslFrameAdmissible(frame(ageNs = -1L), intent(), nowNs))
    }

    @Test
    fun `frames without complete facts refuse`() {
        assertFalse(zslFrameAdmissible(frame(exposureNs = null), intent(), nowNs))
        assertFalse(zslFrameAdmissible(frame(iso = null), intent(), nowNs))
        assertFalse(zslFrameAdmissible(frame(zoomRatio = null), intent(), nowNs))
        assertFalse(zslFrameAdmissible(frame(exposureNs = 0L), intent(), nowNs))
        assertFalse(zslFrameAdmissible(frame(iso = 0), intent(), nowNs))
        assertFalse(zslFrameAdmissible(frame(), intent(exposureNs = 0L), nowNs))
        assertFalse(zslFrameAdmissible(frame(), intent(zoomRatio = 0f), nowNs))
    }
}
