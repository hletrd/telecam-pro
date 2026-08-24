package me.hletrd.telecampro.ui.review

import me.hletrd.telecampro.R

import me.hletrd.telecampro.camera.MediaDeleteScope
import me.hletrd.telecampro.storage.MediaProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaReviewSizingTest {

    private data class HorizontalRect(val start: Float, val end: Float)

    private fun bottomRects(windowWidthDp: Float, rtl: Boolean): Pair<HorizontalRect, HorizontalRect> {
        val edge = 14f
        val actionWidth = 48f
        val metadataWidth = reviewBottomMetadataMaxWidthDp(windowWidthDp, actionVisible = true)
        return if (rtl) {
            HorizontalRect(windowWidthDp - edge - metadataWidth, windowWidthDp - edge) to
                HorizontalRect(edge, edge + actionWidth)
        } else {
            HorizontalRect(edge, edge + metadataWidth) to
                HorizontalRect(windowWidthDp - edge - actionWidth, windowWidthDp - edge)
        }
    }

    @Test
    fun `narrow review widths reserve exact metadata space for the action`() {
        val expected = mapOf(
            320f to 232f,
            340f to 252f,
            360f to 272f,
            411f to 280f,
        )
        expected.forEach { (windowWidth, metadataWidth) ->
            assertEquals(
                "$windowWidth dp metadata width",
                metadataWidth,
                reviewBottomMetadataMaxWidthDp(windowWidth, actionVisible = true),
                0f,
            )
        }

        assertEquals(280f, reviewBottomMetadataMaxWidthDp(320f, actionVisible = false), 0f)
        assertEquals(280f, reviewBottomMetadataMaxWidthDp(411f, actionVisible = false), 0f)
    }

    @Test
    fun `metadata and action rectangles stay disjoint in LTR and RTL`() {
        for (windowWidth in listOf(320f, 340f, 360f, 411f)) {
            for (rtl in listOf(false, true)) {
                val (metadata, action) = bottomRects(windowWidth, rtl)
                val gap = if (rtl) metadata.start - action.end else action.start - metadata.end
                assertTrue("$windowWidth dp rtl=$rtl gap=$gap", gap >= 12f)
                assertTrue("metadata escaped window: $metadata", metadata.start >= 0f && metadata.end <= windowWidth)
                assertTrue("action escaped window: $action", action.start >= 0f && action.end <= windowWidth)
            }
        }
    }

    @Test
    fun `review bottom geometry fails closed for invalid widths`() {
        assertEquals(0f, reviewBottomMetadataMaxWidthDp(Float.NaN, actionVisible = true), 0f)
        assertEquals(0f, reviewBottomMetadataMaxWidthDp(0f, actionVisible = true), 0f)
        assertEquals(0f, reviewBottomMetadataMaxWidthDp(40f, actionVisible = true), 0f)
    }

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
        assertEquals(
            R.string.a11y_review_legacy_unverified_media,
            galleryReviewContentDescription(
                hasMedia = true,
                kind = ReviewMediaKind.STILL,
                provenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
            ),
        )
    }

    @Test
    fun `review provenance copy appears only for unverifiable legacy format media`() {
        assertNull(reviewProvenanceLabel(MediaProvenance.APP_OWNED))
        assertEquals(
            R.string.review_legacy_unverified_provenance,
            reviewProvenanceLabel(MediaProvenance.LEGACY_FORMAT_UNVERIFIED),
        )
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
        assertEquals(R.string.a11y_zoom_4x, reviewZoomActionResource(1f))
        assertEquals("→4×", reviewZoomControlLabel(1f))

        assertEquals(8f, nextReviewScale(4f))
        assertEquals(R.string.a11y_zoom_8x, reviewZoomActionResource(4f))
        assertEquals("→8×", reviewZoomControlLabel(4f))

        assertEquals(1f, nextReviewScale(8f))
        assertEquals(R.string.a11y_reset_zoom, reviewZoomActionResource(8f))
        assertEquals("→1×", reviewZoomControlLabel(8f))
    }

    @Test
    fun `video playback resources withhold transport while preparing`() {
        assertNull(videoPlaybackActionResource(VideoPlaybackUiState.PREPARING))
        assertNull(videoPlaybackToggleTarget(VideoPlaybackUiState.PREPARING))
        assertEquals(
            R.string.a11y_preparing_video,
            videoPlaybackStateResource(VideoPlaybackUiState.PREPARING),
        )
        assertEquals(
            R.string.a11y_pause_video,
            videoPlaybackActionResource(VideoPlaybackUiState.PLAYING),
        )
        assertEquals(
            R.string.a11y_playing,
            videoPlaybackStateResource(VideoPlaybackUiState.PLAYING),
        )
        assertEquals(
            VideoPlaybackUiState.PAUSED,
            videoPlaybackToggleTarget(VideoPlaybackUiState.PLAYING),
        )
        assertEquals(
            R.string.a11y_play_video,
            videoPlaybackActionResource(VideoPlaybackUiState.PAUSED),
        )
        assertEquals(
            R.string.a11y_paused,
            videoPlaybackStateResource(VideoPlaybackUiState.PAUSED),
        )
        assertEquals(
            VideoPlaybackUiState.PLAYING,
            videoPlaybackToggleTarget(VideoPlaybackUiState.PAUSED),
        )
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
