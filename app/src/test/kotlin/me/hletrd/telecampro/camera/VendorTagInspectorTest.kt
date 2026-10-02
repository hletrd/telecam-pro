package me.hletrd.telecampro.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * AGG5-37 / DB5-16: the debug capability dump is a set of facts, logged ONCE per process at INFO,
 * so Engine re-creations can no longer spend the reserved warning/error owner.
 */
@RunWith(RobolectricTestRunner::class)
class VendorTagInspectorTest {
    @Test
    fun `a dump claim is granted exactly once per owner`() {
        val claim = AtomicBoolean(false)

        assertTrue(VendorTagInspector.claimDump(claim))
        assertFalse(VendorTagInspector.claimDump(claim))
        assertFalse(VendorTagInspector.claimDump(claim))
    }

    @Test
    fun `repeated dumps add no rows and never spend the warning level`() {
        val manager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(CameraManager::class.java)

        VendorTagInspector.logAll(manager)
        val afterFirst = ShadowLog.getLogsForTag(VendorTagInspector.TAG).size
        VendorTagInspector.logAll(manager)
        VendorTagInspector.logAll(manager)

        val rows = ShadowLog.getLogsForTag(VendorTagInspector.TAG)
        assertEquals(afterFirst, rows.size)
        assertTrue(rows.none { it.type >= Log.WARN })
    }

    // AGG6-21: the dump spends at most its fixed share of the shared owner, keeps one slot for an
    // honest truncation note, and charges nothing beyond it.
    @Test
    fun `a capped dump spends only its share and notes the truncation`() {
        val shared = ProcessDiagnosticLogBudget(SHARED_RECURRING_DIAGNOSTIC_ROW_BUDGET)
        val rows = CapabilityDumpRows(maxRows = 4)
        val admitted = (1..10).count { rows.admit { recurringDiagnosticAllowed(true, shared) } }
        assertEquals(3, admitted)
        assertTrue(rows.truncated)
        assertTrue(rows.admitTruncationNote { recurringDiagnosticAllowed(true, shared) })
        assertFalse(rows.admitTruncationNote { recurringDiagnosticAllowed(true, shared) })
        assertEquals(4, rows.emitted)
        assertEquals(4, shared.usedRows())
        assertEquals(20, CAPABILITY_DUMP_ROW_SHARE)
    }

    @Test
    fun `a dump refused by the shared owner keeps no claim and charges nothing more`() {
        val exhausted = ProcessDiagnosticLogBudget(1).also { assertTrue(it.tryAcquire()) }
        val claim = AtomicBoolean(false)
        assertTrue(VendorTagInspector.claimDump(claim))
        val rows = CapabilityDumpRows()
        var consulted = 0
        assertFalse(rows.admit { consulted++; recurringDiagnosticAllowed(true, exhausted) })
        assertFalse(rows.admit { consulted++; true })
        assertEquals(1, consulted)
        assertFalse(rows.truncated)
        assertFalse(rows.admitTruncationNote { true })
        rows.settleClaim(claim)
        // The claim is returned: a later Engine start may still take the dump.
        assertTrue(VendorTagInspector.claimDump(claim))

        // A dump that admitted a row keeps its claim for the process.
        val kept = CapabilityDumpRows()
        assertTrue(kept.admit { true })
        kept.settleClaim(claim)
        assertFalse(VendorTagInspector.claimDump(claim))
    }
}
