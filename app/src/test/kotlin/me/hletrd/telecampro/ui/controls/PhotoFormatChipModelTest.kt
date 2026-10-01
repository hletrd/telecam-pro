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
    ) = photoFormatChipModel(request, outputs, cameraReady, rawAvailable, heifAvailable = true)

    @Test fun `FRONT DNG-only request shows the HEIF the session writes and keeps DNG intent`() {
        // FRONT: rawSelectable is false, the session has a processed reader only.
        val m = model(dngOnly, processedOnly, rawAvailable = false)
        assertEquals(PhotoFormats(heif = true, jpeg = false, dngRaw = true), m.displayed)
        assertFalse("the one processed format written cannot be switched off", m.heifEnabled)
        assertTrue(m.jpegEnabled)
        assertFalse(m.dngEnabled)
        assertEquals(R.string.output_raw_unavailable, m.caption)
        // A JPEG tap edits only that axis of the REQUEST: HEIF (a session stand-in) is not adopted
        // into the rear DNG-only workflow — the outcome AGG3-18 exists to prevent.
        assertEquals(PhotoFormats(heif = false, jpeg = true, dngRaw = true), m.toggled(PhotoFormatAxis.JPEG))
    }

    @Test fun `hi-res session shows the passthrough JPEG it writes and offers no HEIF`() {
        val m = model(heifOnly, PhotoSessionOutputs(processed = true, hiRes = true), rawAvailable = false)
        assertEquals(PhotoFormats(heif = false, jpeg = true, dngRaw = false), m.displayed)
        assertFalse(m.heifEnabled)
        assertFalse("the only written format stays on", m.jpegEnabled)
    }

    @Test fun `drop-RAW rung shows the processed stand-in and the switching caption`() {
        val m = model(dngOnly, processedOnly, rawAvailable = true)
        assertEquals(PhotoFormats(heif = true, jpeg = false, dngRaw = true), m.displayed)
        assertEquals(R.string.output_switching_single_lens, m.caption)
        // The HEIF is a session stand-in, not a requested sibling: switching DNG off would leave
        // the request with no format at all, so the DNG chip stays locked on.
        assertFalse(m.dngEnabled)
        // Tapping the stand-in HEIF off folds to "no processed axis" — the request is unchanged.
        assertEquals(dngOnly, m.toggled(PhotoFormatAxis.HEIF))
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
            photoFormatChipModel(PhotoFormats(heif = false, jpeg = true), processedOnly, true, false, heifAvailable = false)
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
