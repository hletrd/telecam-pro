package com.hletrd.findx9tele.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOutputTrackerTest {

    @Test
    fun deleteByDisplayedOutput_returnsEveryKnownSibling() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        assertFalse(tracker.record(7, "shot.heic"))
        assertFalse(tracker.record(7, "shot.jpg"))
        assertFalse(tracker.record(7, "shot.dng"))

        assertEquals(setOf("shot.heic", "shot.jpg", "shot.dng"), tracker.takeForDelete("shot.jpg"))
    }

    @Test
    fun lateSiblingOfDeletedCapture_isRejected() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)
        tracker.record(7, "shot.heic")
        tracker.takeForDelete("shot.heic")

        assertTrue(tracker.record(7, "late.dng"))
    }

    @Test
    fun unknownSeededOutput_deletesOnlyItself() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 4)

        assertEquals(setOf("old-gallery-item"), tracker.takeForDelete("old-gallery-item"))
    }

    @Test
    fun boundedHistory_evictsOldReverseMapping() {
        val tracker = CaptureOutputTracker<String>(maxCaptureHistory = 1)
        tracker.record(1, "old.heic")
        tracker.record(2, "new.heic")

        assertEquals(setOf("old.heic"), tracker.takeForDelete("old.heic"))
        assertFalse(tracker.record(1, "old-late.dng"))
    }
}
