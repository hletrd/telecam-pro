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
    fun reconstructedPriorFamily_deletesEverySeededSibling() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        val outputs = listOf(
            PriorCaptureOutput("shot.heic", CaptureOutputKind.DISPLAYABLE),
            PriorCaptureOutput("shot.jpg", CaptureOutputKind.DISPLAYABLE),
            PriorCaptureOutput("shot.dng", CaptureOutputKind.RAW),
        )

        assertTrue(tracker.seedPriorCapture(outputs, preferredOutput = "shot.heic"))
        assertTrue(tracker.isCurrentReviewOutput("shot.heic"))
        assertEquals(setOf("shot.heic", "shot.jpg", "shot.dng"), tracker.takeForDelete("shot.heic"))
    }

    @Test
    fun firstLiveCapture_alwaysSupersedesPriorProcessSeed() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.seedPriorCapture(
            listOf(PriorCaptureOutput("seed.dng", CaptureOutputKind.RAW)),
            preferredOutput = "seed.dng",
        )

        assertEquals(
            CaptureOutputDecision.REVIEW,
            tracker.record(0, "live.dng", CaptureOutputKind.RAW),
        )
        assertTrue(tracker.isCurrentReviewOutput("live.dng"))
    }

    @Test
    fun lateRestoreSeed_neverDisplacesAlreadyPublishedLiveCapture() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(1, "live.heic", CaptureOutputKind.DISPLAYABLE)

        assertFalse(
            tracker.seedPriorCapture(
                listOf(PriorCaptureOutput("seed.heic", CaptureOutputKind.DISPLAYABLE)),
                preferredOutput = "seed.heic",
            ),
        )
        assertTrue(tracker.isCurrentReviewOutput("live.heic"))
    }

    @Test
    fun partialFamilyDelete_hasEveryKnownFailureVisibleToTheCaller() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.seedPriorCapture(
            listOf(
                PriorCaptureOutput("shot.heic", CaptureOutputKind.DISPLAYABLE),
                PriorCaptureOutput("shot.dng", CaptureOutputKind.RAW),
            ),
            preferredOutput = "shot.heic",
        )
        val attempted = tracker.takeForDelete("shot.heic")
        val results = attempted.associateWith { it != "shot.dng" }

        assertEquals(setOf("shot.heic", "shot.dng"), attempted)
        assertFalse(results.values.all { it })
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
    fun openReviewPin_survivesProductionLimitWhileLiveCapturesAdvance() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 8)
        tracker.record(1, "frozen.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.record(1, "frozen.dng", CaptureOutputKind.RAW)
        assertTrue(tracker.pinForReview("frozen.heic"))

        for (captureId in 2..12) {
            tracker.record(captureId, "$captureId.heic", CaptureOutputKind.DISPLAYABLE)
        }

        assertTrue(tracker.isCurrentReviewOutput("12.heic"))
        assertEquals(
            setOf("frozen.heic", "frozen.dng"),
            tracker.takeForDelete("frozen.heic"),
        )
    }

    @Test
    fun releasingPin_trimsTheOldFamilyBackOutOfOrdinaryHistory() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 1)
        tracker.record(1, "old.heic", CaptureOutputKind.DISPLAYABLE)
        assertTrue(tracker.pinForReview("old.heic"))
        tracker.record(2, "new.heic", CaptureOutputKind.DISPLAYABLE)

        tracker.releaseReviewPin("old.heic")

        assertFalse(tracker.pinForReview("old.heic"))
        assertEquals(setOf("old.heic"), tracker.takeForDelete("old.heic"))
    }

    @Test
    fun replacingPin_ignoresAStaleCloseAndKeepsTheReplacement() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 1)
        tracker.record(1, "first.heic", CaptureOutputKind.DISPLAYABLE)
        assertTrue(tracker.pinForReview("first.heic"))
        tracker.record(2, "second.heic", CaptureOutputKind.DISPLAYABLE)
        assertTrue(tracker.pinForReview("second.heic"))

        tracker.releaseReviewPin("first.heic")
        tracker.record(3, "third.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.record(4, "fourth.heic", CaptureOutputKind.DISPLAYABLE)

        assertEquals(setOf("second.heic"), tracker.takeForDelete("second.heic"))
        assertFalse(tracker.pinForReview("first.heic"))
    }

    @Test
    fun deletingPinnedFamily_consumesPinAndTombstonesLateSibling() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 1)
        tracker.record(7, "shot.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.record(7, "shot.dng", CaptureOutputKind.RAW)
        assertTrue(tracker.pinForReview("shot.heic"))

        assertEquals(setOf("shot.heic", "shot.dng"), tracker.takeForDelete("shot.heic"))
        assertEquals(
            CaptureOutputDecision.DELETE,
            tracker.record(7, "late.jpg", CaptureOutputKind.DISPLAYABLE),
        )
        tracker.releaseReviewPin("shot.heic")
        assertFalse(tracker.pinForReview("shot.heic"))
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
