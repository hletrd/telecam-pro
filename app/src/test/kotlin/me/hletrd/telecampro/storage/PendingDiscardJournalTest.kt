package me.hletrd.telecampro.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class PendingDiscardJournalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `sqlite cursor pages in stable uri order with one bounded continuation row`() {
        val journal = newJournal()
        listOf(
            "content://row/05",
            "content://row/01",
            "content://row/04",
            "content://row/02",
        ).forEach { assertTrue(journal.mark(it)) }

        val first = journal.page(afterKey = null, batchLimit = 2)
        val second = journal.page(afterKey = first.nextAfterKey, batchLimit = 2)

        assertEquals(listOf("content://row/01", "content://row/02"), first.keys)
        assertEquals("content://row/02", first.nextAfterKey)
        assertEquals(3, first.rowsRead)
        assertTrue(first.hasMore)
        assertEquals(listOf("content://row/04", "content://row/05"), second.keys)
        assertEquals("content://row/05", second.nextAfterKey)
        assertEquals(2, second.rowsRead)
        assertFalse(second.hasMore)

        val empty = journal.page(afterKey = "content://row/99", batchLimit = 2)
        assertTrue(empty.keys.isEmpty())
        assertEquals("content://row/99", empty.nextAfterKey)
        assertEquals(0, empty.rowsRead)
        assertFalse(empty.hasMore)
        assertThrows(IllegalArgumentException::class.java) {
            journal.page(afterKey = null, batchLimit = 0)
        }
    }

    @Test
    fun `ten thousand legacy markers migrate but a page reads only batch plus one`() {
        val suffix = UUID.randomUUID().toString()
        val preferences = context.getSharedPreferences("legacy-$suffix", Context.MODE_PRIVATE)
        val editor = preferences.edit()
        repeat(10_000) { index ->
            editor.putString("content://bulk/${index.toString().padStart(5, '0')}", "discard")
        }
        editor.putString("content://registered", "registered")
        editor.putString("content://complete", "complete")
        assertTrue(editor.commit())
        val journal = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = preferences,
        )

        val first = journal.page(afterKey = null, batchLimit = 64)

        assertEquals(64, first.keys.size)
        assertEquals(65, first.rowsRead)
        assertTrue(first.hasMore)
        assertEquals("content://bulk/00000", first.keys.first())
        assertEquals("content://bulk/00063", first.keys.last())
        assertEquals("registered", preferences.getString("content://registered", null))
        assertEquals("complete", preferences.getString("content://complete", null))
        assertEquals(
            2,
            preferences.all.size,
        )
    }

    @Test
    fun `cleanup interruption retains both copies and retry completes migration without loss`() {
        val suffix = UUID.randomUUID().toString()
        val preferences = context.getSharedPreferences("legacy-$suffix", Context.MODE_PRIVATE)
        val uri = "content://legacy/00001"
        assertTrue(preferences.edit().putString(uri, "discard").commit())
        val interrupted = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = preferences,
            removeLegacyEntries = { false },
        )

        assertEquals(listOf(uri), interrupted.page(afterKey = null, batchLimit = 8).keys)
        assertTrue(interrupted.contains(uri))
        assertEquals("discard", preferences.getString(uri, null))

        val relaunched = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = preferences,
        )
        assertEquals(listOf(uri), relaunched.page(afterKey = null, batchLimit = 8).keys)
        assertTrue(relaunched.contains(uri))
        assertFalse(preferences.contains(uri))
    }

    @Test
    fun `failed media delete retains marker and exact successful retry removes it`() {
        val authority = "discard-retention-${UUID.randomUUID()}"
        val provider = RetainingProvider()
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.parse("content://$authority/rows/1")

        assertEquals(
            PendingOutputDiscardResult.RECOVERY_MARKED,
            MediaStoreWriter.discardPendingOutput(context, uri),
        )
        assertTrue(PendingDiscardJournal(context).contains(uri.toString()))

        provider.allowDelete = true
        assertTrue(MediaStoreWriter.delete(context, uri))
        assertFalse(PendingDiscardJournal(context).contains(uri.toString()))
    }

    private fun newJournal(): PendingDiscardJournal {
        val suffix = UUID.randomUUID().toString()
        return PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = context.getSharedPreferences("legacy-$suffix", Context.MODE_PRIVATE),
        )
    }

    private class RetainingProvider : ContentProvider() {
        var allowDelete = false
        private var exists = true

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf("_id")).apply {
            if (exists) addRow(arrayOf(1L))
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (!allowDelete || !exists) return 0
            exists = false
            return 1
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
