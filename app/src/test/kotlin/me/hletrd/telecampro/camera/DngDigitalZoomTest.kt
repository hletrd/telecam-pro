package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner decision 2026-10-25: a DNG never carries digital zoom (Camera2 never crops RAW_SENSOR by
 * CONTROL_ZOOM_RATIO), so the operator is told once when the next still's DNG stops matching its
 * HEIF/JPEG framing. Device fact behind it: TELE + Hasselblad 300 mm at 60× wrote HEIF/JPEG at
 * DigitalZoomRatio 4.6 beside a full-array DNG.
 */
class DngDigitalZoomTest {

    private val fullSession = PhotoSessionOutputs(processed = true, raw = true)
    private val heifAndDng = PhotoFormats(heif = true, jpeg = false, dngRaw = true)

    /** PMA110 TELE photo with DNG on, Ready: the user-reported route. Local 2.33 ≈ the 30× preset. */
    private val teleDng = CameraUiState(
        mode = CaptureMode.PHOTO,
        cameraReady = true,
        teleconverterMode = true,
        lens = LensChoice.TELE3X,
        photoFormats = heifAndDng,
        photoSessionOutputs = fullSession,
        controls = ManualControls(zoomRatio = 2.33f),
    )

    private fun CameraUiState.framing(rawForcesStandalone: Boolean = true) =
        dngZoomFraming(rawForcesStandalone = rawForcesStandalone)

    @Test
    fun `a zoomed standalone route writing DNG skips the digital zoom`() {
        assertEquals(DngZoomFraming.SKIPS_ZOOM, teleDng.framing())
        // GENERIC law too: TELE pins the standalone 3× whatever the RAW law says.
        assertEquals(DngZoomFraming.SKIPS_ZOOM, teleDng.framing(rawForcesStandalone = false))
        // PMA110 non-TELE photo: wanting DNG is what moves the route onto the standalone lens, so
        // the wire is lens-local there (5× unified on the 3× lens = local 1.67).
        val standaloneMain = teleDng.copy(teleconverterMode = false, controls = ManualControls(zoomRatio = 1.67f))
        assertEquals(DngZoomFraming.SKIPS_ZOOM, standaloneMain.framing())
        // DNG-only still writes the DNG.
        val dngOnly = teleDng.copy(photoFormats = PhotoFormats(heif = false, jpeg = false, dngRaw = true))
        assertEquals(DngZoomFraming.SKIPS_ZOOM, dngOnly.framing())
    }

    @Test
    fun `native lens zoom matches and the hysteresis band is neither`() {
        // 13× on the 300 mm kit is lens-local 1.0: the DNG is exactly the processed framing.
        assertEquals(DngZoomFraming.MATCHES, teleDng.copy(controls = ManualControls(zoomRatio = 1f)).framing())
        assertEquals(DngZoomFraming.MATCHES, teleDng.copy(controls = ManualControls(zoomRatio = 1.0005f)).framing())
        assertEquals(DngZoomFraming.MARGINAL, teleDng.copy(controls = ManualControls(zoomRatio = 1.005f)).framing())
        assertEquals(DngZoomFraming.MARGINAL, teleDng.copy(controls = ManualControls(zoomRatio = 1.01f)).framing())
        assertEquals(DngZoomFraming.SKIPS_ZOOM, teleDng.copy(controls = ManualControls(zoomRatio = 1.02f)).framing())
    }

    @Test
    fun `no DNG in the next still matches`() {
        assertEquals(DngZoomFraming.MATCHES, teleDng.copy(photoFormats = PhotoFormats()).framing())
        // A drop-RAW rung: the request wants DNG, the accepted session cannot write it.
        assertEquals(
            DngZoomFraming.MATCHES,
            teleDng.copy(photoSessionOutputs = PhotoSessionOutputs(processed = true)).framing(),
        )
        // Hi-res: the passthrough JPEG is the only still lane, RAW is forced off.
        assertEquals(
            DngZoomFraming.MATCHES,
            teleDng.copy(photoSessionOutputs = fullSession.copy(hiRes = true)).framing(),
        )
    }

    @Test
    fun `a logical seamless route is never claimed`() {
        // GENERIC carries RAW on the logical camera; its main-relative wire ratio cannot say which
        // sensor field the RAW is, so a 3× there stays silent.
        val logicalGeneric = teleDng.copy(
            teleconverterMode = false,
            lens = LensChoice.TELE3X,
            controls = ManualControls(zoomRatio = 3f),
        )
        assertEquals(DngZoomFraming.MATCHES, logicalGeneric.framing(rawForcesStandalone = false))
    }

    @Test
    fun `sessions without RAW never claim`() {
        // A still-less session (the 10-bit HLG10 shape drops BOTH still readers) writes no DNG.
        assertEquals(
            DngZoomFraming.MATCHES,
            teleDng.copy(photoSessionOutputs = PhotoSessionOutputs(hlg = true)).framing(),
        )
        // FRONT: RAW is excluded from the session plan by scope; the processed reader remains.
        val front = teleDng.copy(
            teleconverterMode = false,
            facing = CameraFacing.FRONT,
            activeCameraRoute = CameraRoute.FRONT,
            photoSessionOutputs = PhotoSessionOutputs(processed = true),
        )
        assertEquals(DngZoomFraming.MATCHES, front.framing())
    }

    @Test
    fun `a full-screen modal holds the decision until the viewfinder is back`() {
        // The DNG chip lives in the settings sheet: turning it on at 30x must announce when the sheet
        // closes, not behind it.
        val notice = DngDigitalZoomNotice()
        val dngOff = teleDng.copy(photoFormats = teleDng.photoFormats.copy(dngRaw = false))
        assertFalse(notice.observe(dngOff.framing()))                                  // baseline
        assertEquals(DngZoomFraming.NO_STILL, teleDng.copy(cameraInputBlocked = true).framing())
        assertFalse(notice.observe(teleDng.copy(cameraInputBlocked = true).framing())) // sheet open
        assertTrue(notice.observe(teleDng.framing()))                                   // sheet closed
        // Off then on inside ONE sheet visit is no net change: silent.
        assertFalse(notice.observe(dngOff.copy(cameraInputBlocked = true).framing()))
        assertFalse(notice.observe(teleDng.copy(cameraInputBlocked = true).framing()))
        assertFalse(notice.observe(teleDng.framing()))
    }

    @Test
    fun `announced only in PHOTO on a Ready session`() {
        assertEquals(DngZoomFraming.NO_STILL, teleDng.copy(cameraReady = false).framing())
        val videoStandby = teleDng.copy(mode = CaptureMode.VIDEO, teleconverterMode = false)
        assertEquals(DngZoomFraming.NO_STILL, videoStandby.framing())
        // VIDEO stays quiet even while recording, although the in-REC snapshot's DNG is unzoomed too.
        assertEquals(DngZoomFraming.NO_STILL, videoStandby.copy(isRecording = true).framing(rawForcesStandalone = false))
    }

    @Test
    fun `the bare predicate needs all three facts and a finite ratio`() {
        assertTrue(dngSkipsDigitalZoom(stillWritesDng = true, wireZoomLensLocal = true, wireZoomRatio = 4.67f))
        assertFalse(dngSkipsDigitalZoom(stillWritesDng = false, wireZoomLensLocal = true, wireZoomRatio = 4.67f))
        assertFalse(dngSkipsDigitalZoom(stillWritesDng = true, wireZoomLensLocal = false, wireZoomRatio = 4.67f))
        assertFalse(dngSkipsDigitalZoom(stillWritesDng = true, wireZoomLensLocal = true, wireZoomRatio = 1f))
        assertFalse(dngSkipsDigitalZoom(stillWritesDng = true, wireZoomLensLocal = true, wireZoomRatio = Float.NaN))
    }

    @Test
    fun `the notice announces once on entry and ticks inside the state never repost`() {
        val notice = DngDigitalZoomNotice()
        assertFalse(notice.observe(DngZoomFraming.MATCHES))
        assertTrue("entering announces", notice.observe(DngZoomFraming.SKIPS_ZOOM))
        assertFalse("a zoom tick inside the state", notice.observe(DngZoomFraming.SKIPS_ZOOM))
        assertFalse("the hysteresis band does not re-arm", notice.observe(DngZoomFraming.MARGINAL))
        assertFalse(notice.observe(DngZoomFraming.SKIPS_ZOOM))
        assertFalse("leaving is silent", notice.observe(DngZoomFraming.MATCHES))
        assertTrue("leaving re-armed it", notice.observe(DngZoomFraming.SKIPS_ZOOM))
    }

    @Test
    fun `a reopen holds the latch either way`() {
        val latched = DngDigitalZoomNotice()
        latched.observe(DngZoomFraming.MATCHES)
        assertTrue(latched.observe(DngZoomFraming.SKIPS_ZOOM))
        assertFalse(latched.observe(DngZoomFraming.NO_STILL))
        assertFalse("an aspect reopen into the same state is not news", latched.observe(DngZoomFraming.SKIPS_ZOOM))

        val armed = DngDigitalZoomNotice()
        armed.observe(DngZoomFraming.MATCHES)
        assertFalse(armed.observe(DngZoomFraming.NO_STILL))
        assertTrue("a door that lands in the state announces at its Ready", armed.observe(DngZoomFraming.SKIPS_ZOOM))
    }

    @Test
    fun `the first still-capable observation is a silent baseline`() {
        val restored = DngDigitalZoomNotice()
        assertFalse("cold start has no still yet", restored.observe(DngZoomFraming.NO_STILL))
        assertFalse("launching into the state is a restore, not an entry", restored.observe(DngZoomFraming.SKIPS_ZOOM))
        assertFalse(restored.observe(DngZoomFraming.MATCHES))
        assertTrue(restored.observe(DngZoomFraming.SKIPS_ZOOM))

        val marginalStart = DngDigitalZoomNotice()
        assertFalse(marginalStart.observe(DngZoomFraming.MARGINAL))
        assertTrue("a baseline inside the band is armed", marginalStart.observe(DngZoomFraming.SKIPS_ZOOM))
    }
}
