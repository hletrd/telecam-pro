package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
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
    fun `production binding never exceeds the process ceilings`() {
        // Secondary, order-independent check of the real process owners: whatever earlier classes
        // spent, the production doors can only add at most one row per call and never pass a cap.
        val tag = uniqueTag("process")
        DiagnosticLog.d(tag, "recurring debug row")
        DiagnosticLog.w(tag, "reserved warning row")

        assertTrue(processDiagnosticLogBudget.usedRows() <= RECURRING_DIAGNOSTIC_ROW_BUDGET)
        assertTrue(processReservedDiagnosticLogBudget.usedRows() <= RESERVED_DIAGNOSTIC_ROW_BUDGET)
        assertTrue(
            RECURRING_DIAGNOSTIC_ROW_BUDGET + RESERVED_DIAGNOSTIC_ROW_BUDGET <=
                COLOR_OS_PROCESS_LOG_ROW_LIMIT,
        )
        // A row of THIS tag reaches logcat at most once per door call.
        assertTrue(ShadowLog.getLogsForTag(tag).size <= 2)
    }

    private fun uniqueTag(suffix: String) = "DiagnosticLogTest.$suffix.${System.nanoTime()}"
}
