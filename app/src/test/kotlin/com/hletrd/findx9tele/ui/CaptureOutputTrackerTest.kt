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
    fun wholeFamilyDelete_returnsEveryKnownSiblingForTheCallerToAttempt() {
        // takeForDelete has no per-output success/failure concept — delete outcomes live at the
        // MediaStore caller. What THIS seam must guarantee is that every known sibling of the
        // capture is surfaced for the attempt, so a partial failure upstream can never be caused
        // by the tracker withholding a family member. (An earlier second assertion here was
        // tautological: it recomputed a locally-built map and could never fail on its own.)
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.seedPriorCapture(
            listOf(
                PriorCaptureOutput("shot.heic", CaptureOutputKind.DISPLAYABLE),
                PriorCaptureOutput("shot.dng", CaptureOutputKind.RAW),
            ),
            preferredOutput = "shot.heic",
        )
        assertEquals(setOf("shot.heic", "shot.dng"), tracker.takeForDelete("shot.heic"))
    }

    @Test
    fun partialFamilyDelete_restoresOnlySurvivorsAndKeepsLateOutputsTombstoned() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(21, "shot.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.record(21, "shot.jpg", CaptureOutputKind.DISPLAYABLE)
        tracker.record(21, "shot.dng", CaptureOutputKind.RAW)

        val plan = tracker.beginDelete("shot.heic")
        assertEquals(setOf("shot.heic", "shot.jpg", "shot.dng"), plan.outputs)
        assertEquals(
            "shot.jpg",
            tracker.restoreDeleteSurvivors(plan, setOf("shot.jpg", "shot.dng")),
        )
        assertTrue(tracker.isCurrentReviewOutput("shot.jpg"))
        assertEquals(setOf("shot.jpg", "shot.dng"), tracker.takeForDelete("shot.jpg"))
        assertEquals(
            CaptureOutputDecision.DELETE,
            tracker.record(21, "late.heic", CaptureOutputKind.DISPLAYABLE),
        )
    }

    @Test
    fun partialFamilyDelete_doesNotDisplaceNewerCaptureThatArrivedDuringResolverWork() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(31, "old.heic", CaptureOutputKind.DISPLAYABLE)
        val plan = tracker.beginDelete("old.heic")
        tracker.record(32, "new.heic", CaptureOutputKind.DISPLAYABLE)

        assertEquals(null, tracker.restoreDeleteSurvivors(plan, setOf("old.heic")))
        assertTrue(tracker.isCurrentReviewOutput("new.heic"))
        assertEquals(setOf("old.heic"), tracker.takeForDelete("old.heic"))
    }

    @Test
    fun failedFileOnlyDelete_restoresAReviewableFileOnlyEntry() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        val plan = tracker.beginDelete("external.jpg")

        assertEquals(
            "external.jpg",
            tracker.restoreDeleteSurvivors(plan, setOf("external.jpg")),
        )
        assertTrue(tracker.isCurrentReviewOutput("external.jpg"))
        assertEquals(setOf("external.jpg"), tracker.takeForDelete("external.jpg"))
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

    @Test
    fun retriedPriorSeed_replacesTheExistingPriorFamilyWithoutMerging() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.seedPriorCapture(
            listOf(
                PriorCaptureOutput("first.heic", CaptureOutputKind.DISPLAYABLE),
                PriorCaptureOutput("first.dng", CaptureOutputKind.RAW),
            ),
            preferredOutput = "first.heic",
        )

        // A caller retrying restoration re-resolves a different family: replacement is
        // deterministic — the two prior families must never merge into one delete group.
        assertTrue(
            tracker.seedPriorCapture(
                listOf(PriorCaptureOutput("second.heic", CaptureOutputKind.DISPLAYABLE)),
                preferredOutput = "second.heic",
            ),
        )
        assertTrue(tracker.isCurrentReviewOutput("second.heic"))
        // The first family was fully unlinked: its outputs now delete as unknown single files.
        assertEquals(setOf("first.heic"), tracker.takeForDelete("first.heic"))
        assertFalse(tracker.isCurrentReviewOutput("first.heic"))
    }

    @Test
    fun tombstoneCapOverflow_forgetsOnlyTheOldestDeletedCapture() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4, maxTombstones = 1)
        tracker.record(1, "one.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.record(2, "two.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.takeForDelete("one.heic")
        // The second delete overflows the 1-entry tombstone budget and evicts capture 1's stone.
        tracker.takeForDelete("two.heic")

        assertEquals(
            CaptureOutputDecision.DELETE,
            tracker.record(2, "late-two.jpg", CaptureOutputKind.DISPLAYABLE),
        )
        // Capture 1's tombstone aged out: its late sibling is treated as a fresh output again
        // (bounded memory is the contract — only the newest deletions stay rejected).
        assertEquals(
            CaptureOutputDecision.REVIEW,
            tracker.record(1, "late-one.jpg", CaptureOutputKind.DISPLAYABLE),
        )
    }

    @Test
    fun fileOnlyRestore_returnsNullWhenALiveCaptureClaimedTheOutputDuringBinderIo() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        val plan = tracker.beginDelete("external.jpg")
        // During the asynchronous MediaStore attempt a live capture claims the same output; the
        // file-only re-seed must fail instead of demoting the live family to the prior slot.
        tracker.record(1, "external.jpg", CaptureOutputKind.DISPLAYABLE)

        assertEquals(null, tracker.restoreDeleteSurvivors(plan, setOf("external.jpg")))
        assertTrue(tracker.isCurrentReviewOutput("external.jpg"))
        assertEquals(
            CaptureOutputDecision.TRACK_ONLY,
            tracker.record(1, "external.dng", CaptureOutputKind.RAW),
        )
    }

    @Test
    fun retriedRestore_replacesTheStillPresentRestoredFamilyEntry() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(41, "shot.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.record(41, "shot.jpg", CaptureOutputKind.DISPLAYABLE)
        tracker.record(41, "shot.dng", CaptureOutputKind.RAW)
        val plan = tracker.beginDelete("shot.heic")

        // First resolver pass restores two survivors; a retry of the same plan then confirms only
        // one. The still-present restored family must be REPLACED, its dropped sibling unlinked.
        assertEquals("shot.jpg", tracker.restoreDeleteSurvivors(plan, setOf("shot.jpg", "shot.dng")))
        assertEquals("shot.jpg", tracker.restoreDeleteSurvivors(plan, setOf("shot.jpg")))
        assertTrue(tracker.isCurrentReviewOutput("shot.jpg"))
        assertEquals(setOf("shot.jpg"), tracker.takeForDelete("shot.jpg"))
    }

    @Test
    fun rawOnlySurvivors_withoutThePreferredOutput_fallBackToTheFirstRetained() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(51, "shot.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.record(51, "shot.dng", CaptureOutputKind.RAW)
        val plan = tracker.beginDelete("shot.heic")

        // Only the RAW sibling survived: with the preferred (displayable) owner gone and no other
        // displayable survivor, review truthfully falls back to the RAW file itself.
        assertEquals("shot.dng", tracker.restoreDeleteSurvivors(plan, setOf("shot.dng")))
        assertTrue(tracker.isCurrentReviewOutput("shot.dng"))
    }

    // The exact post-delete interleaving from the cycle-10 review: a late sibling of an id the
    // tracker evicts DURING its own record() must never become the review owner — the UI would
    // publish a URI whose family no longer exists, pinForReview would fail, and delete would
    // silently degrade to the displayed file.
    @Test
    fun lateSiblingEvictedByItsOwnRecord_neverBecomesReviewOwner() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 2)
        // 1. Fill history: id 5 is evicted, id 10 owns review.
        tracker.record(5, "a5.heic", CaptureOutputKind.DISPLAYABLE)
        tracker.record(6, "a6.heic", CaptureOutputKind.DISPLAYABLE)
        assertEquals(
            CaptureOutputDecision.REVIEW,
            tracker.record(10, "a10.heic", CaptureOutputKind.DISPLAYABLE),
        )
        // 2. Pin id 10 for the open review; it stops consuming an ordinary slot.
        assertTrue(tracker.pinForReview("a10.heic"))
        // 3. A late first output for id 7 fills both ordinary slots (6 and 7).
        assertEquals(
            CaptureOutputDecision.TRACK_ONLY,
            tracker.record(7, "a7.heic", CaptureOutputKind.DISPLAYABLE),
        )
        // 4. Delete the pinned id 10: the review owner clears while 6 and 7 stay at the limit.
        assertEquals(setOf("a10.heic"), tracker.takeForDelete("a10.heic"))
        // 5. A late sibling of the long-evicted id 5 arrives: record() inserts it and its own
        //    trim immediately evicts it again — it must NOT claim the now-null review owner.
        assertEquals(
            CaptureOutputDecision.TRACK_ONLY,
            tracker.record(5, "b5.dng", CaptureOutputKind.RAW),
        )
        assertFalse(tracker.isCurrentReviewOutput("b5.dng"))
        // The evicted sibling is no longer a managed family: pinning must truthfully fail so the
        // UI can promise file-only deletion, and delete returns only the displayed file.
        assertFalse(tracker.pinForReview("b5.dng"))
        assertEquals(setOf("b5.dng"), tracker.takeForDelete("b5.dng"))
        // The retained ordinary families are untouched and can still claim review normally.
        assertEquals(
            CaptureOutputDecision.REVIEW,
            tracker.record(11, "a11.heic", CaptureOutputKind.DISPLAYABLE),
        )
        assertTrue(tracker.isCurrentReviewOutput("a11.heic"))
    }
}
