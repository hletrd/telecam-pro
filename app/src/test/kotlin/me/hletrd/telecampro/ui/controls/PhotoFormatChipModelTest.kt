package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import me.hletrd.telecampro.camera.dngOnlySubstitution
import me.hletrd.telecampro.ui.overlays.compactPhotoFormatLabel
import me.hletrd.telecampro.ui.overlays.photoFormatLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGG4-66 / AGG4-69 / AGG4-70 / AGG4-67: the Output row, the OSD and the Ready status read the
 * accepted session's answer, keep the request as the edit base, and treat a reopen as a reopen.
 */
class PhotoFormatChipModelTest {
    private val dngOnly = PhotoFormats(heif = false, jpeg = false, dngRaw = true)
    private val heifOnly = PhotoFormats(heif = true, jpeg = false, dngRaw = false)
    private val processedOnly = PhotoSessionOutputs(processed = true)

    private fun model(
        request: PhotoFormats,
        outputs: PhotoSessionOutputs,
        cameraReady: Boolean = true,
        rawAvailable: Boolean,
        reopenInProgress: Boolean = !cameraReady,
    ) = photoFormatChipModel(request, outputs, cameraReady, reopenInProgress, rawAvailable, heifAvailable = true, heifStandIn = true)

    @Test fun `FRONT DNG-only request shows the HEIF the session writes and keeps DNG intent`() {
        // FRONT: rawSelectable is false, the session has a processed reader only.
        val m = model(dngOnly, processedOnly, rawAvailable = false)
        assertEquals(PhotoFormats(heif = true, jpeg = false, dngRaw = true), m.displayed)
        assertFalse("the one processed format written cannot be switched off", m.heifEnabled)
        assertTrue(m.jpegEnabled)
        assertFalse(m.dngEnabled)
        assertEquals(R.string.output_raw_unavailable, m.caption)
        // AGG5-54 / UX5-6: the chips are pick-many. A JPEG tap BESIDE the lit stand-in adds JPEG to
        // what the row shows; keeping the request's untouched `heif = false` re-rendered HEIF unlit,
        // so "add JPEG" acted like a radio button and silently stopped the HEIF this route writes.
        assertEquals(PhotoFormats(heif = true, jpeg = true, dngRaw = true), m.toggled(PhotoFormatAxis.JPEG))
    }

    @Test fun `hi-res session shows the passthrough JPEG it writes and offers no HEIF`() {
        val m = model(heifOnly, PhotoSessionOutputs(processed = true, hiRes = true), rawAvailable = false)
        assertEquals(PhotoFormats(heif = false, jpeg = true, dngRaw = false), m.displayed)
        assertFalse(m.heifEnabled)
        assertFalse("the only written format stays on", m.jpegEnabled)
    }

    // AGG5-52 / UX5-4: on a READY session the DNG route move has already happened; a settled
    // session without RAW is not "switching to a single lens".
    @Test fun `drop-RAW rung shows the processed stand-in and says RAW is unavailable`() {
        val m = model(dngOnly, processedOnly, rawAvailable = true)
        assertEquals(PhotoFormats(heif = true, jpeg = false, dngRaw = true), m.displayed)
        assertEquals(R.string.output_raw_unavailable, m.caption)
        // The HEIF is a session stand-in, not a requested sibling: switching DNG off would leave
        // the request with no format at all, so the DNG chip stays locked on.
        assertFalse(m.dngEnabled)
        // Tapping the stand-in HEIF off folds to "no processed axis" — the request is unchanged — so
        // the stand-in is not offered at all (AGG6-26): it was enabled and "checked" with a tap that
        // did nothing. FRONT already disabled it; now every RAW-less route agrees.
        assertEquals(dngOnly, m.toggled(PhotoFormatAxis.HEIF))
        assertFalse(m.heifEnabled)
        assertTrue("a tap BESIDE the stand-in stays live (AGG5-54)", m.jpegEnabled)
    }

    // AGG5-53: the stand-in follows the processed encoder this device has — HEIF needs HEVC.
    @Test fun `without an HEVC encoder the stand-in is JPEG and is not offered either`() {
        val m = photoFormatChipModel(
            dngOnly, processedOnly, true, false, true, heifAvailable = false, heifStandIn = false,
        )
        assertEquals(PhotoFormats(heif = false, jpeg = true, dngRaw = true), m.displayed)
        assertFalse(m.heifEnabled)
        assertFalse(m.jpegEnabled)
        assertEquals(dngOnly, m.toggled(PhotoFormatAxis.JPEG))
        assertTrue(CameraUiState(encoderInventoryLoaded = false, heifAvailable = false).heifStandInAvailable)
        assertFalse(CameraUiState(encoderInventoryLoaded = true, heifAvailable = false).heifStandInAvailable)
        assertTrue(CameraUiState(encoderInventoryLoaded = true, heifAvailable = true).heifStandInAvailable)
    }

    @Test fun `a reopen reads as reconfiguring and keeps the chips on the request`() {
        val request = PhotoFormats(heif = true, jpeg = false, dngRaw = true)
        val m = model(request, PhotoSessionOutputs(), cameraReady = false, rawAvailable = true)
        assertEquals(request, m.displayed)
        assertEquals(R.string.status_camera_reconfiguring, m.caption)
        assertTrue(m.heifEnabled)
        assertTrue(m.jpegEnabled)
        assertTrue(m.dngEnabled)
        assertEquals(PhotoFormats(heif = true, jpeg = true, dngRaw = true), m.toggled(PhotoFormatAxis.JPEG))
        // Without RAW the old cascade said "Still capture unavailable" for every reopen.
        assertEquals(
            R.string.status_camera_reconfiguring,
            model(heifOnly, PhotoSessionOutputs(), cameraReady = false, rawAvailable = false).caption,
        )
    }

    // AGG5-55 / UX5-7: not-Ready is broader than a reopen. Cold start, pause and the exhausted
    // "reopen the app" terminal keep the request and the chips but make no "reconfiguring" claim.
    @Test fun `not ready without a reopen condition carries no reconfiguring caption`() {
        val request = PhotoFormats(heif = true, jpeg = false, dngRaw = true)
        val m = model(request, PhotoSessionOutputs(), cameraReady = false, rawAvailable = true, reopenInProgress = false)
        assertNull(m.caption)
        assertEquals(request, m.displayed)
        assertTrue(m.heifEnabled && m.jpegEnabled && m.dngEnabled)
        assertNull(model(heifOnly, PhotoSessionOutputs(), cameraReady = false, rawAvailable = false, reopenInProgress = false).caption)
        // The condition only matters while not Ready: a Ready session keeps its own caption.
        assertNull(model(heifOnly, processedOnly, cameraReady = true, rawAvailable = true, reopenInProgress = true).caption)
    }

    @Test fun `a tap beside a stand-in on a DNG-only request adopts the stand-in, other taps do not`() {
        val dropRaw = model(dngOnly, processedOnly, rawAvailable = true)
        assertEquals(PhotoFormats(heif = true, jpeg = true, dngRaw = true), dropRaw.toggled(PhotoFormatAxis.JPEG))
        // A request that already names a processed axis keeps per-axis editing (AGG3-18).
        val hiRes = model(heifOnly, PhotoSessionOutputs(processed = true, hiRes = true), rawAvailable = false)
        assertEquals(PhotoFormats(heif = true, jpeg = false, dngRaw = true), hiRes.toggled(PhotoFormatAxis.DNG))
        val both = model(PhotoFormats(heif = true, dngRaw = true), PhotoSessionOutputs(processed = true, raw = true), rawAvailable = true)
        assertEquals(PhotoFormats(heif = true, jpeg = true, dngRaw = true), both.toggled(PhotoFormatAxis.JPEG))
    }

    @Test fun `accepted sessions keep the established captions`() {
        assertEquals(
            R.string.output_10_bit_video_stills_off,
            model(heifOnly, PhotoSessionOutputs(hlg = true), rawAvailable = false).caption,
        )
        assertEquals(
            R.string.status_still_capture_unavailable,
            model(heifOnly, PhotoSessionOutputs(), rawAvailable = false).caption,
        )
        assertEquals(
            R.string.output_processed_unavailable_dng_only,
            model(PhotoFormats(heif = true, dngRaw = true), PhotoSessionOutputs(raw = true), rawAvailable = true).caption,
        )
        val both = model(PhotoFormats(heif = true, dngRaw = true), PhotoSessionOutputs(processed = true, raw = true), rawAvailable = true)
        assertNull(both.caption)
        assertEquals(PhotoFormats(heif = true, dngRaw = true), both.displayed)
        assertEquals(PhotoFormats(heif = true, jpeg = true, dngRaw = true), both.toggled(PhotoFormatAxis.JPEG))
        assertEquals(PhotoFormats(heif = false, jpeg = false, dngRaw = true), both.toggled(PhotoFormatAxis.HEIF))
        // No HEIF encoder: the chip is not offered even when the session could carry it.
        assertFalse(
            photoFormatChipModel(PhotoFormats(heif = false, jpeg = true), processedOnly, true, false, false, heifAvailable = false, heifStandIn = false)
                .heifEnabled,
        )
    }

    @Test fun `the OSD says nothing is written on a Ready session without a still target`() {
        val ready = CameraUiState(cameraReady = true, photoFormats = heifOnly, photoSessionOutputs = PhotoSessionOutputs())
        assertEquals(PhotoFormats(heif = false, jpeg = false, dngRaw = false), ready.osdPhotoFormats)
        assertEquals("--", photoFormatLabel(ready.osdPhotoFormats))
        assertEquals("--", compactPhotoFormatLabel(ready))
        // Reopening keeps the request; an accepted still session shows its effective set.
        assertEquals(heifOnly, ready.copy(cameraReady = false).osdPhotoFormats)
        assertEquals(
            PhotoFormats(heif = true, jpeg = false, dngRaw = false),
            CameraUiState(cameraReady = true, photoFormats = dngOnly, photoSessionOutputs = processedOnly).osdPhotoFormats,
        )
    }

    @Test fun `DNG-only substitution is a state the ViewModel announces only on its edge`() {
        assertTrue(dngOnlySubstitution(PhotoFormats(heif = true, dngRaw = true), PhotoSessionOutputs(raw = true)))
        assertFalse(dngOnlySubstitution(dngOnly, PhotoSessionOutputs(raw = true)))
        assertFalse(dngOnlySubstitution(PhotoFormats(heif = true, dngRaw = true), PhotoSessionOutputs(processed = true, raw = true)))
        assertFalse(dngOnlySubstitution(heifOnly, PhotoSessionOutputs()))
    }
}
