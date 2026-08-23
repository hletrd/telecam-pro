package me.hletrd.telecampro.ui.review

import me.hletrd.telecampro.R

import me.hletrd.telecampro.camera.MediaDeleteScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaReviewSizingTest {

    @Test
    fun `delete confirmation promises siblings only for a proven capture family`() {
        assertEquals(
            MediaDeleteConfirmationCopy(
                title = R.string.review_delete_capture_title,
                body = R.string.review_delete_family_body,
            ),
            mediaDeleteConfirmationCopy(MediaDeleteScope.CAPTURE_FAMILY, raw = false),
        )
        // FILE_ONLY must state the SCOPE, not restate the title: this is the degraded path where
        // siblings survive, so "Only this file" is the one fact the family dialog does not promise.
        assertEquals(
            MediaDeleteConfirmationCopy(
                title = R.string.review_delete_raw_file_title,
                body = R.string.review_delete_file_body,
            ),
            mediaDeleteConfirmationCopy(MediaDeleteScope.FILE_ONLY, raw = true),
        )
    }

    @Test
    fun `DNG MIME aliases classify as raw without pixel decoding`() {
        assertEquals(ReviewMediaKind.RAW, reviewMediaKind("image/x-adobe-dng"))
        assertEquals(ReviewMediaKind.RAW, reviewMediaKind("IMAGE/DNG"))
        assertEquals(ReviewMediaKind.RAW, reviewMediaKind("application/x-adobe-dng; version=1"))
    }

    @Test
    fun `video and ordinary image MIME types retain their review kinds`() {
        assertEquals(ReviewMediaKind.VIDEO, reviewMediaKind("video/mp4"))
        assertEquals(ReviewMediaKind.STILL, reviewMediaKind("image/heic"))
        assertEquals(ReviewMediaKind.STILL, reviewMediaKind(null))
    }

    @Test
    fun `gallery semantics truthfully identify raw photo and video owners`() {
        assertEquals(R.string.a11y_no_capture_to_review, galleryReviewContentDescription(false, null))
        assertEquals(R.string.a11y_review_last_capture, galleryReviewContentDescription(true, null))
        assertEquals(R.string.a11y_review_last_raw, galleryReviewContentDescription(true, ReviewMediaKind.RAW))
        assertEquals(R.string.a11y_review_last_photo, galleryReviewContentDescription(true, ReviewMediaKind.STILL))
        assertEquals(R.string.a11y_review_last_video, galleryReviewContentDescription(true, ReviewMediaKind.VIDEO))
    }

    @Test
    fun `raw metadata stays truthful when dimensions are unavailable`() {
        // DECIMAL megabytes, matching the label and StatusInfoPill's remaining-shots budget (which
        // sizes a DNG at 26_000_000 bytes). The old binary divisor under an "MB" label reported a
        // 26 MB DNG as 24.8 MB — two byte bases for the same file, one of them mislabelled.
        assertEquals("RAW · 1.0 MB", reviewMetadataLine(raw = true, width = 0, height = 0, sizeBytes = 1_000_000L))
        assertEquals(
            "RAW · 8192×6144 · 2.0 MB",
            reviewMetadataLine(raw = true, width = 8192, height = 6144, sizeBytes = 2_000_000L),
        )
        assertEquals(
            "RAW · 26.0 MB",
            reviewMetadataLine(raw = true, width = 0, height = 0, sizeBytes = 26_000_000L),
        )
    }

    @Test
    fun `still zoom cycle exposes the next visible action`() {
        // The button's visual label is arrow-prefixed: it names the NEXT magnification while the
        // top pill names the CURRENT one, and a bare "N×" made the two contradict (cycle-6 D-12).
        assertEquals(4f, nextReviewScale(1f))
        assertEquals("Zoom 4×", reviewZoomActionLabel(1f))
        assertEquals("→4×", reviewZoomControlLabel(1f))

        assertEquals(8f, nextReviewScale(4f))
        assertEquals("Zoom 8×", reviewZoomActionLabel(4f))
        assertEquals("→8×", reviewZoomControlLabel(4f))

        assertEquals(1f, nextReviewScale(8f))
        assertEquals("Reset zoom", reviewZoomActionLabel(8f))
        assertEquals("→1×", reviewZoomControlLabel(8f))
    }

    @Test
    fun `still zoom state describes current magnification`() {
        assertEquals("Zoom 1×", reviewZoomStateDescription(1f))
        assertEquals("Zoom 4×", reviewZoomStateDescription(4f))
        assertEquals("Zoom 8×", reviewZoomStateDescription(8f))
        assertEquals("Zoom 2.5×", reviewZoomStateDescription(2.5f))
    }

    @Test
    fun `video playback labels expose action and current state`() {
        assertEquals("Pause video", videoPlaybackActionLabel(playing = true))
        assertEquals("Playing", videoPlaybackStateDescription(playing = true))
        assertEquals("Play video", videoPlaybackActionLabel(playing = false))
        assertEquals("Paused", videoPlaybackStateDescription(playing = false))
    }

    @Test
    fun `video thumbnail always requests a square provider bound`() {
        assertEquals(
            ProviderThumbnailRequest(240, 240),
            providerThumbnailRequest(maxDim = 240),
        )
    }

    @Test
    fun `unknown source metadata cannot enlarge the provider request`() {
        // The request deliberately has no source-width/source-height input. Unknown or malformed
        // metadata therefore cannot select an unscaled decoder fallback.
        assertEquals(ProviderThumbnailRequest(240, 240), providerThumbnailRequest(240))
    }

    @Test
    fun `oversized provider output is rejected for the placeholder`() {
        val request = requireNotNull(providerThumbnailRequest(240))

        assertTrue(providerThumbnailFitsRequest(width = 240, height = 135, request))
        assertTrue(providerThumbnailFitsRequest(width = 135, height = 240, request))
        assertFalse(providerThumbnailFitsRequest(width = 3840, height = 2160, request))
        assertFalse(providerThumbnailFitsRequest(width = 241, height = 135, request))
    }

    @Test
    fun `unknown provider output dimensions are rejected for the placeholder`() {
        val request = requireNotNull(providerThumbnailRequest(240))

        assertFalse(providerThumbnailFitsRequest(width = 0, height = 135, request))
        assertFalse(providerThumbnailFitsRequest(width = 135, height = 0, request))
        assertFalse(providerThumbnailFitsRequest(width = -1, height = 135, request))
    }

    @Test
    fun `nonpositive max dimension has no thumbnail request`() {
        assertNull(providerThumbnailRequest(maxDim = 0))
        assertNull(providerThumbnailRequest(maxDim = -1))
    }

    @Test
    fun `one pixel bound remains a bounded provider request`() {
        assertEquals(
            ProviderThumbnailRequest(1, 1),
            providerThumbnailRequest(maxDim = 1),
        )
    }
}
