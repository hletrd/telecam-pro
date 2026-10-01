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
import me.hletrd.telecampro.camera.DiagnosticLogDoors
import me.hletrd.telecampro.camera.ProcessDiagnosticLogBudget
import me.hletrd.telecampro.camera.RESERVED_DIAGNOSTIC_ROW_BUDGET
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
 * exactly one reserved row (never one per retry). Each test binds FRESH budgets through the
 * writer's `warn` seam, so the counts are exact and unconditional: the process-global reserved owner
 * may already be spent by other classes in the shared sandbox, and its daemon retry threads could
 * race a relational assertion (TE2-2).
 */
@RunWith(RobolectricTestRunner::class)
class StorageFailureDiagnosticsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a failed provider open logs its cause once`() {
        val uri = register()
        val reserved = ProcessDiagnosticLogBudget(10)
        val warn = warnThrough(reserved)

        assertNull(MediaStoreWriter.openParcelFd(context, uri, "rw", warn))
        assertNull(MediaStoreWriter.openOutputStream(context, uri, warn))

        val rows = rowsFor(uri)
        assertEquals(2, rows.size)
        assertEquals(2, reserved.usedRows())
        rows.forEach { assertTrue(it.throwable is FileNotFoundException) }
    }

    @Test
    fun `a spent reserved budget suppresses the row instead of overrunning the quota`() {
        val uri = register()
        val reserved = ProcessDiagnosticLogBudget(1)
        val warn = warnThrough(reserved)

        assertNull(MediaStoreWriter.openParcelFd(context, uri, "rw", warn))
        assertNull(MediaStoreWriter.openOutputStream(context, uri, warn))

        val rows = rowsFor(uri)
        assertEquals(1, rows.size)
        assertTrue(rows.single().msg.startsWith("open rw failed"))
        assertEquals(1, reserved.usedRows())
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
        val reserved = ProcessDiagnosticLogBudget(10)

        assertFalse(MediaStoreWriter.publish(context, uri, journal, warnThrough(reserved)))

        val rows = rowsFor(uri)
        assertEquals(1, rows.size)
        assertTrue(rows.single().msg.startsWith("publish exhausted"))
        assertTrue(rows.single().throwable?.message.orEmpty().contains("matched 0 rows"))
        // Three attempts, one row: exhaustion spends the reserved owner once, never per retry.
        assertEquals(1, reserved.usedRows())
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
        val reserved = ProcessDiagnosticLogBudget(RESERVED_DIAGNOSTIC_ROW_BUDGET)
        val warn = warnThrough(reserved)

        repeat(4) {
            assertNull(MediaStoreWriter.openParcelFd(context, uri, "rw", warn))
            assertNull(MediaStoreWriter.openOutputStream(context, uri, warn))
        }
        repeat(2) { assertFalse(MediaStoreWriter.publish(context, uri, journal, warn)) }

        val rows = rowsFor(uri)
        assertEquals("rows=${rows.map { it.msg }}", 3, rows.size)
        assertEquals(1, rows.count { it.msg.startsWith("open rw failed") })
        assertEquals(1, rows.count { it.msg.startsWith("open output stream failed") })
        assertEquals(1, rows.count { it.msg.startsWith("publish exhausted") })
        assertEquals(3, reserved.usedRows())
    }

    private fun warnThrough(reserved: ProcessDiagnosticLogBudget): (String, Throwable?) -> Unit {
        val doors = DiagnosticLogDoors(recurring = ProcessDiagnosticLogBudget(1), reserved = reserved)
        return { message, failure -> doors.w("MediaStoreWriter", message, failure) }
    }

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
