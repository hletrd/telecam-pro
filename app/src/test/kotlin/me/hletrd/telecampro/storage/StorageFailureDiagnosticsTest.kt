package me.hletrd.telecampro.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.FileNotFoundException
import java.util.UUID
import me.hletrd.telecampro.camera.RESERVED_DIAGNOSTIC_ROW_BUDGET
import me.hletrd.telecampro.camera.processReservedDiagnosticLogBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog

/**
 * "A save that fails with no app log line is the signature of this class of defect." The provider
 * open helpers and publish exhaustion used to swallow their cause. Each failure EVENT now spends
 * exactly one reserved row (never one per retry). The reserved owner is process-global, so other
 * host tests may already have spent it: the assertions are relational to its remaining capacity.
 */
@RunWith(RobolectricTestRunner::class)
class StorageFailureDiagnosticsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a failed provider open logs its cause once`() {
        val uri = register()
        val budgetOpen = reservedBudgetOpen()

        assertNull(MediaStoreWriter.openParcelFd(context, uri, "rw"))
        assertNull(MediaStoreWriter.openOutputStream(context, uri))

        val rows = rowsFor(uri)
        assertTrue(rows.size <= 2)
        if (budgetOpen) {
            assertEquals(2, rows.size)
            rows.forEach { assertTrue(it.throwable is FileNotFoundException) }
        }
    }

    @Test
    fun `publish exhaustion logs the last cause once, not once per attempt`() {
        val uri = register()
        val suffix = UUID.randomUUID().toString()
        val journal = PendingDiscardJournal(
            context = context,
            databaseName = "publish-diag-$suffix.db",
            legacyPreferences = context.getSharedPreferences("publish-diag-$suffix", Context.MODE_PRIVATE),
        )
        val budgetOpen = reservedBudgetOpen()

        assertFalse(MediaStoreWriter.publish(context, uri, journal))

        val rows = rowsFor(uri).filter { it.msg.startsWith("publish exhausted") }
        assertTrue(rows.size <= 1)
        if (budgetOpen) {
            assertEquals(1, rows.size)
            assertTrue(rows.single().throwable?.message.orEmpty().contains("matched 0 rows"))
        }
    }

    @Test
    fun `retrying the same failing uri spends one row per site, not one per retry`() {
        // AGG2-29: sibling opens of one save and recovery's page retries hit the SAME URI; each
        // used to spend a reserved row. Change-gated per (site, URI, failure class) now.
        val uri = register()
        val suffix = UUID.randomUUID().toString()
        val journal = PendingDiscardJournal(
            context = context,
            databaseName = "publish-gate-$suffix.db",
            legacyPreferences = context.getSharedPreferences("publish-gate-$suffix", Context.MODE_PRIVATE),
        )
        val budgetOpen = processReservedDiagnosticLogBudget.usedRows() + 3 <= RESERVED_DIAGNOSTIC_ROW_BUDGET

        repeat(4) {
            assertNull(MediaStoreWriter.openParcelFd(context, uri, "rw"))
            assertNull(MediaStoreWriter.openOutputStream(context, uri))
        }
        repeat(2) { assertFalse(MediaStoreWriter.publish(context, uri, journal)) }

        val rows = rowsFor(uri)
        assertTrue("rows=${rows.map { it.msg }}", rows.size <= 3)
        if (budgetOpen) {
            assertEquals(1, rows.count { it.msg.startsWith("open rw failed") })
            assertEquals(1, rows.count { it.msg.startsWith("open output stream failed") })
            assertEquals(1, rows.count { it.msg.startsWith("publish exhausted") })
        }
    }

    private fun reservedBudgetOpen(): Boolean =
        processReservedDiagnosticLogBudget.usedRows() + 2 <= RESERVED_DIAGNOSTIC_ROW_BUDGET

    private fun rowsFor(uri: Uri): List<ShadowLog.LogItem> =
        ShadowLog.getLogsForTag("MediaStoreWriter").filter { it.msg.contains(uri.toString()) }

    private fun register(): Uri {
        val authority = "storage-diag-${UUID.randomUUID()}"
        val provider = RefusingProvider()
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return Uri.parse("content://$authority/images/7")
    }

    /** A provider whose row exists for nobody: opens throw, updates match zero rows. */
    private class RefusingProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            throw FileNotFoundException("row revoked: $uri")

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun getType(uri: Uri): String? = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    }
}
