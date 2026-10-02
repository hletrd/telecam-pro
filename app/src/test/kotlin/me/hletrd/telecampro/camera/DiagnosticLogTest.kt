package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * The exact contract is pinned against FRESH owners through [DiagnosticLogDoors]: the process
 * owners are shared with every Robolectric class in the sandbox, so by the time this class runs
 * they may already be exhausted (which made exact-count assertions vacuous) or be spent mid-test by
 * a leftover background thread (which made them flaky). The production [DiagnosticLog] binding is
 * checked only relationally, as a secondary assertion that survives any order.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticLogTest {
    @Test
    fun `each door spends exactly one row from its own owner and logs it`() {
        val tag = uniqueTag("doors")
        val recurring = ProcessDiagnosticLogBudget(RECURRING_DIAGNOSTIC_ROW_BUDGET)
        val reserved = ProcessDiagnosticLogBudget(RESERVED_DIAGNOSTIC_ROW_BUDGET)
        val doors = DiagnosticLogDoors(recurring, reserved)

        doors.d(tag, "recurring debug row")
        doors.i(tag, "recurring information row")
        assertEquals(2, recurring.usedRows())
        assertEquals(0, reserved.usedRows())

        doors.w(tag, "reserved warning row")
        doors.w(tag, "reserved warning row with failure", IllegalStateException("failure"))
        doors.e(tag, "reserved error row")
        doors.e(tag, "reserved error row with failure", IllegalStateException("failure"))
        assertEquals(2, recurring.usedRows())
        assertEquals(4, reserved.usedRows())

        assertEquals(6, ShadowLog.getLogsForTag(tag).size)
    }

    @Test
    fun `an exhausted owner suppresses its rows and leaves the other owner untouched`() {
        val tag = uniqueTag("exhausted")
        val recurring = ProcessDiagnosticLogBudget(1)
        val reserved = ProcessDiagnosticLogBudget(1)
        val doors = DiagnosticLogDoors(recurring, reserved)

        doors.d(tag, "admitted recurring row")
        doors.i(tag, "suppressed recurring row")
        doors.d(tag, "suppressed recurring row")
        doors.w(tag, "admitted reserved row")
        doors.w(tag, "suppressed reserved row", IllegalStateException("failure"))
        doors.e(tag, "suppressed reserved row")

        assertEquals(1, recurring.usedRows())
        assertEquals(1, reserved.usedRows())
        assertEquals(
            listOf("admitted recurring row", "admitted reserved row"),
            ShadowLog.getLogsForTag(tag).map { it.msg },
        )
    }

    @Test
    fun `an admitted failure row carries its throwable`() {
        val tag = uniqueTag("failure")
        val failure = IllegalStateException("finder draw failed")
        val doors = DiagnosticLogDoors(
            ProcessDiagnosticLogBudget(RECURRING_DIAGNOSTIC_ROW_BUDGET),
            ProcessDiagnosticLogBudget(RESERVED_DIAGNOSTIC_ROW_BUDGET),
        )

        doors.w(tag, "reserved warning row with failure", failure)
        doors.e(tag, "reserved error row with failure", failure)

        val rows = ShadowLog.getLogsForTag(tag)
        assertEquals(2, rows.size)
        rows.forEach { row -> assertEquals(failure, row.throwable) }
    }

    @Test
    fun `a gated emission is charged exactly once`() {
        // AGG5-9: the pre-gate IS the charge. Before the fix the gated producers emitted through the
        // `DiagnosticLog as Log` alias, whose door charged the same owner again (2 rows per line),
        // and on the last row the gate took it while the door refused, so nothing was logged.
        val tag = uniqueTag("gated")
        val recurring = ProcessDiagnosticLogBudget(1)

        if (recurringDiagnosticAllowed(debugEnabled = true, budget = recurring)) {
            android.util.Log.i(tag, "gated row")
        }

        assertEquals(1, recurring.usedRows())
        assertEquals(listOf("gated row"), ShadowLog.getLogsForTag(tag).map { it.msg })
    }

    @Test
    fun `the evidence door spends the reserve only after the shared rows are gone`() {
        val tag = uniqueTag("evidence")
        val shared = ProcessDiagnosticLogBudget(1)
        val evidence = ProcessDiagnosticLogBudget(1)
        val doors = DiagnosticLogDoors(shared, ProcessDiagnosticLogBudget(1), evidence)

        doors.evidence(tag, "shared row")
        assertEquals(1, shared.usedRows())
        assertEquals(0, evidence.usedRows())

        // An ordinary recurring producer can never reach the reserve.
        doors.i(tag, "suppressed recurring row")
        assertEquals(0, evidence.usedRows())

        doors.evidence(tag, "reserve row")
        doors.evidence(tag, "suppressed evidence row")
        assertEquals(1, evidence.usedRows())
        assertEquals(
            listOf("shared row", "reserve row"),
            ShadowLog.getLogsForTag(tag).map { it.msg },
        )
    }

    @Test
    fun `the evidence reserve is carved out of the recurring class, not added to it`() {
        assertEquals(
            RECURRING_DIAGNOSTIC_ROW_BUDGET,
            SHARED_RECURRING_DIAGNOSTIC_ROW_BUDGET + EVIDENCE_DIAGNOSTIC_ROW_RESERVE,
        )
        // RG6-12: pin the CONFIGURED owners, not just a usage bound — a revert of the shared owner
        // to the whole 180-row class, or of the split slices to one 12, must fail here.
        assertEquals(168, SHARED_RECURRING_DIAGNOSTIC_ROW_BUDGET)
        assertEquals(SHARED_RECURRING_DIAGNOSTIC_ROW_BUDGET, processDiagnosticLogBudget.maxRows)
        assertEquals(
            EVIDENCE_DIAGNOSTIC_ROW_RESERVE,
            processStartupEvidenceDiagnosticLogBudget.maxRows + processFrameGapEvidenceDiagnosticLogBudget.maxRows,
        )
        assertEquals(6, processStartupEvidenceDiagnosticLogBudget.maxRows)
        assertEquals(6, processFrameGapEvidenceDiagnosticLogBudget.maxRows)
        assertEquals(RESERVED_DIAGNOSTIC_ROW_BUDGET, processReservedDiagnosticLogBudget.maxRows)
        assertTrue(processDiagnosticLogBudget.usedRows() <= SHARED_RECURRING_DIAGNOSTIC_ROW_BUDGET)
    }

    @Test
    fun `the default evidence door is the startup slice and the default gate the FrameGap slice`() {
        // AGG6-4: the two NEGATIVE-evidence producers must never share a slice. Fresh exhausted
        // shared owners force each default onto its reserve; at most one process row is spent.
        val exhausted = ProcessDiagnosticLogBudget(1).also { assertTrue(it.tryAcquire()) }
        val startupBefore = processStartupEvidenceDiagnosticLogBudget.usedRows()
        val frameGapBefore = processFrameGapEvidenceDiagnosticLogBudget.usedRows()
        DiagnosticLogDoors(recurring = exhausted).evidence(uniqueTag("startup"), "cold start row")
        assertEquals(frameGapBefore, processFrameGapEvidenceDiagnosticLogBudget.usedRows())
        assertEquals(
            minOf(startupBefore + 1, STARTUP_EVIDENCE_DIAGNOSTIC_ROW_RESERVE),
            processStartupEvidenceDiagnosticLogBudget.usedRows(),
        )
        val startupAfter = processStartupEvidenceDiagnosticLogBudget.usedRows()
        evidenceDiagnosticAllowed(debugEnabled = true, shared = exhausted)
        assertEquals(startupAfter, processStartupEvidenceDiagnosticLogBudget.usedRows())
        assertEquals(
            minOf(frameGapBefore + 1, FRAME_GAP_EVIDENCE_DIAGNOSTIC_ROW_RESERVE),
            processFrameGapEvidenceDiagnosticLogBudget.usedRows(),
        )
    }

    @Test
    fun `production binding never exceeds the process ceilings`() {
        // Secondary, order-independent check of the real process owners: whatever earlier classes
        // spent, the production doors can only add at most one row per call and never pass a cap.
        val tag = uniqueTag("process")
        DiagnosticLog.d(tag, "recurring debug row")
        DiagnosticLog.i(tag, "recurring information row")
        DiagnosticLog.evidence(tag, "evidence row")
        DiagnosticLog.w(tag, "reserved warning row")
        // The GL FrameGap summary takes the defaulted process owners; a release build (debug off)
        // must never spend a row from either of them.
        val sharedBefore = processDiagnosticLogBudget.usedRows()
        val evidenceBefore = processFrameGapEvidenceDiagnosticLogBudget.usedRows()
        assertFalse(evidenceDiagnosticAllowed(debugEnabled = false))
        assertEquals(sharedBefore, processDiagnosticLogBudget.usedRows())
        assertEquals(evidenceBefore, processFrameGapEvidenceDiagnosticLogBudget.usedRows())

        assertTrue(processDiagnosticLogBudget.usedRows() <= RECURRING_DIAGNOSTIC_ROW_BUDGET)
        assertTrue(processReservedDiagnosticLogBudget.usedRows() <= RESERVED_DIAGNOSTIC_ROW_BUDGET)
        assertTrue(
            RECURRING_DIAGNOSTIC_ROW_BUDGET + RESERVED_DIAGNOSTIC_ROW_BUDGET <=
                COLOR_OS_PROCESS_LOG_ROW_LIMIT,
        )
        // A row of THIS tag reaches logcat at most once per door call.
        assertTrue(ShadowLog.getLogsForTag(tag).size <= 4)
    }

    private fun uniqueTag(suffix: String) = "DiagnosticLogTest.$suffix.${System.nanoTime()}"
}
