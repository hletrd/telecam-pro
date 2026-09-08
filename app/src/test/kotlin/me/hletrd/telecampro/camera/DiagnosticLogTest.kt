package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * `DiagnosticLog` is the only door production code has to logcat, and both of its owners are
 * PROCESS-GLOBAL: every other host test that runs in this JVM before us may already have spent
 * rows. The contract that survives any order is therefore relational — a row reaches logcat if
 * and only if its owner admitted it, and neither owner ever exceeds its ceiling.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticLogTest {
    @Test
    fun `every door logs exactly the rows its process owner admitted`() {
        val tag = "DiagnosticLogTest.${System.nanoTime()}"
        val recurringBefore = processDiagnosticLogBudget.usedRows()
        val reservedBefore = processReservedDiagnosticLogBudget.usedRows()

        DiagnosticLog.d(tag, "recurring debug row")
        DiagnosticLog.i(tag, "recurring information row")
        DiagnosticLog.w(tag, "reserved warning row")
        DiagnosticLog.w(tag, "reserved warning row with failure", IllegalStateException("failure"))
        DiagnosticLog.e(tag, "reserved error row")

        val recurringAdmitted = processDiagnosticLogBudget.usedRows() - recurringBefore
        val reservedAdmitted = processReservedDiagnosticLogBudget.usedRows() - reservedBefore
        assertTrue("recurring doors consume at most one row each", recurringAdmitted in 0..2)
        assertTrue("reserved doors consume at most one row each", reservedAdmitted in 0..3)
        assertTrue(processDiagnosticLogBudget.usedRows() <= RECURRING_DIAGNOSTIC_ROW_BUDGET)
        assertTrue(processReservedDiagnosticLogBudget.usedRows() <= RESERVED_DIAGNOSTIC_ROW_BUDGET)
        assertEquals(
            "a row reaches logcat exactly when its owner admitted it",
            recurringAdmitted + reservedAdmitted,
            ShadowLog.getLogsForTag(tag).size,
        )
    }

    @Test
    fun `an admitted failure row carries its throwable`() {
        val tag = "DiagnosticLogTest.failure.${System.nanoTime()}"
        val failure = IllegalStateException("finder draw failed")
        val before = processReservedDiagnosticLogBudget.usedRows()

        DiagnosticLog.w(tag, "reserved warning row with failure", failure)

        val rows = ShadowLog.getLogsForTag(tag)
        val admitted = processReservedDiagnosticLogBudget.usedRows() - before
        assertEquals(admitted, rows.size)
        rows.forEach { row -> assertEquals(failure, row.throwable) }
    }
}
