package me.hletrd.telecampro.ui.overlays

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimecodeTest {
    @Test fun `minutes and seconds under an hour`() {
        assertEquals("00:00", recordingTimecode(0L))
        assertEquals("01:23", recordingTimecode(83_999L))
        assertEquals("59:59", recordingTimecode(3_599_999L))
    }

    // UI review 2026-11-14: a take past an hour read "62:03".
    @Test fun `hours from the first hour`() {
        assertEquals("1:00:00", recordingTimecode(3_600_000L))
        assertEquals("1:02:03", recordingTimecode(3_723_000L))
        assertEquals("10:00:01", recordingTimecode(36_001_000L))
    }
}
