package com.hletrd.findx9tele.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOutputTrackerTest {

    @Test
    fun deleteByDisplayedOutput_returnsEveryKnownSibling() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(7, "shot.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.record(7, "shot.jpg", CaptureOutputKind.DISPLAYABLE)
        tracker.record(7, "shot.dng", CaptureOutputKind.RAW)

        assertEquals(setOf("shot.heic", "shot.jpg", "shot.dng"), tracker.takeForDelete("shot.jpg"))
    }

    @Test
    fun lateSiblingOfDeletedCapture_isRejected() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(7, "shot.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.takeForDelete("shot.heic")

        assertEquals(
            CaptureOutputDecision.DELETE,
            tracker.record(7, "late.dng", CaptureOutputKind.RAW),
        )
    }

    @Test
    fun unknownSeededOutput_deletesOnlyItself() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)

        assertEquals(setOf("old-gallery-item"), tracker.takeForDelete("old-gallery-item"))
    }

    @Test
    fun boundedHistory_evictsOldReverseMapping() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 1)
        tracker.record(1, "old.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.record(2, "new.heic", CaptureOutputKind.DISPLAYABLE)

        assertEquals(setOf("old.heic"), tracker.takeForDelete("old.heic"))
        assertEquals(
            CaptureOutputDecision.TRACK_ONLY,
            tracker.record(1, "old-late.dng", CaptureOutputKind.RAW),
        )
        assertTrue(tracker.isCurrentReviewOutput("new.heic"))
    }

    @Test
    fun newerRawOnlyCapture_ownsReview() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(3, "older.heic", CaptureOutputKind.DISPLAYABLE)

        assertEquals(
            CaptureOutputDecision.REVIEW,
            tracker.record(4, "newer.dng", CaptureOutputKind.RAW),
        )
        assertTrue(tracker.isCurrentReviewOutput("newer.dng"))
    }

    @Test
    fun processedSibling_upgradesRawPlaceholderForSameCapture() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        assertEquals(
            CaptureOutputDecision.REVIEW,
            tracker.record(7, "shot.dng", CaptureOutputKind.RAW),
        )

        assertEquals(
            CaptureOutputDecision.REVIEW,
            tracker.record(7, "shot.heic", CaptureOutputKind.DISPLAYABLE),
        )
        assertTrue(tracker.isCurrentReviewOutput("shot.heic"))
        assertFalse(tracker.isCurrentReviewOutput("shot.dng"))
    }

    @Test
    fun rawSibling_neverDisplacesProcessedPeer() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(7, "shot.heic", CaptureOutputKind.DISPLAYABLE)

        assertEquals(
            CaptureOutputDecision.TRACK_ONLY,
            tracker.record(7, "shot.dng", CaptureOutputKind.RAW),
        )
        assertTrue(tracker.isCurrentReviewOutput("shot.heic"))
    }

    @Test
    fun olderLateOutput_neverDisplacesNewerRawCapture() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(9, "newer.dng", CaptureOutputKind.RAW)

        assertEquals(
            CaptureOutputDecision.TRACK_ONLY,
            tracker.record(8, "older.heic", CaptureOutputKind.DISPLAYABLE),
        )
        assertTrue(tracker.isCurrentReviewOutput("newer.dng"))
    }

    @Test
    fun newerVideo_displacesOlderCaptureAndRejectsItsLateRaw() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(11, "photo.heic", CaptureOutputKind.DISPLAYABLE)

        assertEquals(
            CaptureOutputDecision.REVIEW,
            tracker.record(12, "clip.mp4", CaptureOutputKind.DISPLAYABLE),
        )
        assertEquals(
            CaptureOutputDecision.TRACK_ONLY,
            tracker.record(11, "photo.dng", CaptureOutputKind.RAW),
        )
        assertTrue(tracker.isCurrentReviewOutput("clip.mp4"))
    }

    @Test
    fun deletingRawPlaceholder_tombstonesProcessedUpgrade() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(14, "shot.dng", CaptureOutputKind.RAW)

        assertEquals(setOf("shot.dng"), tracker.takeForDelete("shot.dng"))
        assertEquals(
            CaptureOutputDecision.DELETE,
            tracker.record(14, "late.heic", CaptureOutputKind.DISPLAYABLE),
        )
        assertFalse(tracker.isCurrentReviewOutput("shot.dng"))
    }

    @Test
    fun deletingRawUriAfterUpgrade_returnsBothAndClearsReviewOwner() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(15, "shot.dng", CaptureOutputKind.RAW)
        tracker.record(15, "shot.heic", CaptureOutputKind.DISPLAYABLE)

        assertEquals(setOf("shot.dng", "shot.heic"), tracker.takeForDelete("shot.dng"))
        assertFalse(tracker.isCurrentReviewOutput("shot.heic"))
    }
}
