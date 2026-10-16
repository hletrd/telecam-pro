package me.hletrd.telecampro.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-08-02 AVD finding: an Activity recreation (font size, display size, dark mode, locale,
 * multi-window entry — everything outside the activity's configChanges list) destroys and recreates
 * the TextureView, and reusing the GL render chain across that left the camera streaming into an
 * input the GL thread never drew from again: black viewfinder, disabled shutter, no recovery short
 * of killing the process. Retiring the GL generation makes the replacement surface cold-start.
 */
class PreviewSurfaceLossRestartTest {

    @Test
    fun `a live started engine retires its GL generation when the preview surface dies`() {
        assertTrue(
            previewSurfaceLossRestartAllowed(
                acquisitionAllowed = true,
                started = true,
                ownerCurrent = true,
                recording = false,
            ),
        )
    }

    @Test
    fun `an active recording is exempt — the encoder owns that GL generation`() {
        // Retiring GL under a live encoder would drop the take's native graph mid-write; pause()'s
        // ordered finalize owns that teardown instead.
        assertFalse(
            previewSurfaceLossRestartAllowed(
                acquisitionAllowed = true,
                started = true,
                ownerCurrent = true,
                recording = true,
            ),
        )
    }

    @Test
    fun `never restarts before start, on a stale owner, or while acquisition is closed`() {
        assertFalse(
            previewSurfaceLossRestartAllowed(true, started = false, ownerCurrent = true, recording = false),
        )
        assertFalse(
            previewSurfaceLossRestartAllowed(true, started = true, ownerCurrent = false, recording = false),
        )
        // Terminal release / recorder quarantine already own teardown.
        assertFalse(
            previewSurfaceLossRestartAllowed(false, started = true, ownerCurrent = true, recording = false),
        )
    }
}
