package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two PMA110 HAL faults were written as universal law and only came out under review once the app
 * became multi-device (2026-08-02): the logical camera's un-allocatable JPEG blob, and RAW's
 * standalone-only requirement. Both are DeviceProfile-gated now. These pin BOTH sides — PMA110
 * unchanged, spec devices getting the guaranteed/legal shape.
 */
class DeviceRouteLawsTest {

    private fun plan(
        attempt: Int,
        logical: Boolean = true,
        front: Boolean = false,
        standalone: Boolean = false,
        yuvRequired: Boolean,
        rawStandaloneOnly: Boolean,
    ) = sessionAttemptPlan(
        attempt = attempt,
        wantHlg = false,
        supportsRaw = true,
        standalone = standalone,
        logicalMultiCamera = logical,
        frontRoute = front,
        yuvStillRequired = yuvRequired,
        rawStandaloneOnly = rawStandaloneOnly,
    )

    // ---- YUV still lane -------------------------------------------------------------------------

    @Test
    fun `PMA110 keeps the YUV lane on every rung of the logical route`() {
        for (attempt in 0..2) {
            assertTrue(
                "attempt $attempt",
                plan(attempt, yuvRequired = true, rawStandaloneOnly = true).useYuvStill,
            )
        }
    }

    @Test
    fun `a spec device trades YUV for HAL JPEG before giving up on stills`() {
        val p = { a: Int -> plan(a, yuvRequired = false, rawStandaloneOnly = false) }
        // Rungs 0-1 keep YUV (it is what feeds the pseudo-ZSL ring)…
        assertTrue(p(0).useYuvStill)
        assertTrue(p(1).useYuvStill)
        // …then the ladder falls back to the always-guaranteed PRIV+JPEG combination, WITH a still
        // target — previously this rung was byte-identical to the last and the next stop was
        // preview-only, i.e. no stills at all on a device that rejects PRIV+YUV(MAXIMUM).
        assertFalse(p(2).useYuvStill)
        assertTrue(p(2).useJpeg)
    }

    @Test
    fun `standalone routes never take the YUV lane on any device`() {
        for (required in listOf(true, false)) {
            assertFalse(
                plan(0, logical = false, standalone = true, yuvRequired = required, rawStandaloneOnly = true)
                    .useYuvStill,
            )
        }
    }

    // ---- RAW ------------------------------------------------------------------------------------

    @Test
    fun `PMA110 still refuses RAW anywhere but a standalone camera`() {
        assertFalse(plan(0, yuvRequired = true, rawStandaloneOnly = true).useRaw)
        assertTrue(
            plan(0, logical = false, standalone = true, yuvRequired = true, rawStandaloneOnly = true).useRaw,
        )
    }

    @Test
    fun `a spec device may carry RAW on the logical or routed camera`() {
        assertTrue(plan(0, yuvRequired = false, rawStandaloneOnly = false).useRaw)
        // Routed sub-camera (standalone=false, logical=false) is legal there too.
        assertTrue(
            plan(0, logical = false, standalone = false, yuvRequired = false, rawStandaloneOnly = false).useRaw,
        )
    }

    @Test
    fun `the front route drops RAW on every device — its readers are gone by design`() {
        assertFalse(
            plan(0, logical = false, front = true, yuvRequired = false, rawStandaloneOnly = false).useRaw,
        )
    }

    // ---- route forcing --------------------------------------------------------------------------

    @Test
    fun `DNG moves PMA110 off the seamless camera but leaves a spec device on it`() {
        assertTrue(standaloneRouteWanted(videoMode = false, rawWanted = true, rawForcesStandalone = true))
        assertFalse(standaloneRouteWanted(videoMode = false, rawWanted = true, rawForcesStandalone = false))
        // VIDEO's standalone pin is an EIS decision and stays universal.
        assertTrue(standaloneRouteWanted(videoMode = true, rawWanted = false, rawForcesStandalone = false))
    }
}

/** The ZSL ring must never cost the viewfinder its frame rate (2026-08-02 review). */
class ZslStreamFluidityTest {
    @Test
    fun `PMA110's advertised 33 ms YUV duration keeps streaming`() {
        assertTrue(zslStreamKeepsPreviewFluid(33_333_333L))
    }

    @Test
    fun `an unreported duration is treated as no constraint`() {
        assertTrue(zslStreamKeepsPreviewFluid(0L))
    }

    @Test
    fun `a slow large-YUV device keeps the reader off the repeating request`() {
        // 50 ms = 20 fps, 100 ms = 10 fps: both below the 24 fps floor.
        assertFalse(zslStreamKeepsPreviewFluid(50_000_000L))
        assertFalse(zslStreamKeepsPreviewFluid(100_000_000L))
    }
}

/** Coverage the verification pass asked for: the two shapes the new code could newly emit. */
class YuvLaneShapeTest {
    @Test
    fun `a hi-res intent can never turn the logical route's YUV fail-safe off`() {
        // hi-res is standalone-only by construction, but if a bad intent ever reached the plan the
        // old caps-derived predicate still said YUV; the new one must not ask a logical camera for
        // the JPEG blob it cannot allocate.
        val p = sessionAttemptPlan(
            attempt = 0,
            wantHlg = false,
            supportsRaw = false,
            standalone = false,
            logicalMultiCamera = true,
            wantHiRes = true,
            yuvStillRequired = true,
            rawStandaloneOnly = true,
        )
        assertTrue(p.useYuvStill)
    }

    @Test
    fun `the YUV lane degrades monotonically across the TELE table`() {
        // TELE maps ladder rungs onto stream attempts 0,1,2,0,1,2,3,3 — keyed on streamAttempt the
        // lane would drop at rung 2 and come BACK at rungs 3-4.
        var seenFalse = false
        for (attempt in 0..7) {
            val on = sessionAttemptPlan(
                attempt = attempt,
                wantHlg = false,
                supportsRaw = false,
                standalone = false,
                logicalMultiCamera = true,
                teleconverterMode = true,
                yuvStillRequired = false,
                rawStandaloneOnly = false,
            ).useYuvStill
            if (!on) seenFalse = true
            assertTrue("rung $attempt re-deepened after dropping", !(seenFalse && on))
        }
    }
}

/** The offered video sizes must be usable ones — but never an EMPTY list (2026-08-02). */
class VideoSizeFloorTest {
    @Test
    fun `the floor applies while something clears it`() {
        // 1440p/1080p/720p/360p/108p as a real device advertised them.
        assertFalse(videoFloorKeepsAll(listOf(1440, 1080, 720, 360, 108)))
    }

    @Test
    fun `a device whose largest size is below the floor keeps everything`() {
        assertTrue(videoFloorKeepsAll(listOf(360, 108)))
        assertTrue(videoFloorKeepsAll(emptyList()))
    }

    @Test
    fun `PMA110 clears the floor, so nothing about its list changes`() {
        assertFalse(videoFloorKeepsAll(listOf(2160, 1080, 720)))
    }
}
