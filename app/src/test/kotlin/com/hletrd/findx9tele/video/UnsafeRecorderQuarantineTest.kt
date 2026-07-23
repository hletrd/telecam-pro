package com.hletrd.findx9tele.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the PROCESS-GLOBAL quarantine facade's non-terminal delegations only. `close()`/
 * `retain()` are deliberately NEVER called here: the quarantine is irreversible in the test JVM
 * (process restart is the only reclamation boundary), so one such call would poison every later
 * test sharing the JVM. `retain` additionally needs a real VideoRecorder(Context) — Partition B.
 * Every test releases the leases it takes (finally-guarded) so ordering between tests and test
 * classes cannot leak a stuck process admission.
 */
class UnsafeRecorderQuarantineTest {

    @Test
    fun `recorder admission snapshots, commits, publishes, and finishes through the facade`() {
        val token = checkNotNull(UnsafeRecorderQuarantine.snapshotAdmission(Any()))
        try {
            assertTrue(UnsafeRecorderQuarantine.isAdmissionCurrent(token))
            // A second admission is refused while this lease is pending — one process recorder.
            assertNull(UnsafeRecorderQuarantine.snapshotAdmission(Any()))

            var committed = 0
            assertTrue(UnsafeRecorderQuarantine.commitAdmission(token) { committed++ })
            assertEquals(1, committed)

            var nativeRan = false
            assertTrue(UnsafeRecorderQuarantine.runNativeAcquisition { nativeRan = true })
            assertTrue(nativeRan)

            var published = false
            assertTrue(
                UnsafeRecorderQuarantine.publishAdmission(token) {
                    published = true
                    true
                },
            )
            assertTrue(published)
            // Published = ACTIVE: still current, still process-exclusive.
            assertTrue(UnsafeRecorderQuarantine.isAdmissionCurrent(token))
            assertNull(UnsafeRecorderQuarantine.snapshotAdmission(Any()))
        } finally {
            UnsafeRecorderQuarantine.finishAdmission(token)
        }
        assertFalse(UnsafeRecorderQuarantine.isAdmissionCurrent(token))
        assertFalse(UnsafeRecorderQuarantine.isActive())
    }

    @Test
    fun `abandoning a pending admission reopens the process for a fresh lease`() {
        val abandoned = checkNotNull(UnsafeRecorderQuarantine.snapshotAdmission(Any()))
        UnsafeRecorderQuarantine.abandonPendingAdmission(abandoned)
        assertFalse(UnsafeRecorderQuarantine.isAdmissionCurrent(abandoned))

        val fresh = checkNotNull(UnsafeRecorderQuarantine.snapshotAdmission(Any()))
        try {
            assertTrue(UnsafeRecorderQuarantine.isAdmissionCurrent(fresh))
        } finally {
            UnsafeRecorderQuarantine.abandonPendingAdmission(fresh)
        }
    }

    @Test
    fun `standby reservation is exclusive and releases through the facade`() {
        val standby = checkNotNull(UnsafeRecorderQuarantine.reserveStandbyAdmission(Any()))
        try {
            // Exactly one process standby mic.
            assertNull(UnsafeRecorderQuarantine.reserveStandbyAdmission(Any()))
        } finally {
            UnsafeRecorderQuarantine.finishStandbyAdmission(standby)
        }

        val reopened = checkNotNull(UnsafeRecorderQuarantine.reserveStandbyAdmission(Any()))
        UnsafeRecorderQuarantine.finishStandbyAdmission(reopened)
        assertFalse(UnsafeRecorderQuarantine.isActive())
    }
}
