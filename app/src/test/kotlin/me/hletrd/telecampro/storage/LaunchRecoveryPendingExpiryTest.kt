package me.hletrd.telecampro.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/**
 * AGG3-4 (CRIT3-2, DES3-2): a row launch recovery KEEPS pending used to be left untouched, so
 * MediaProvider's pending expiry (about a week from insert) silently deleted a take the status copy
 * promised would be retained. Recovery now re-writes `IS_PENDING = 1` on a kept URI a later launch
 * can still adopt; adopted, deleted, and DISCARD-owned rows never receive it. AGG4-4 bounds it: a
 * row whose bytes were read and are undecidable (unknown MIME) is kept but NOT re-armed, so
 * MediaProvider's expiry stays its terminal. Whether the update re-arms `DATE_EXPIRES` on a given
 * OEM provider stays PENDING DEVICE.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchRecoveryPendingExpiryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `only a kept row a later launch can adopt has its pending flag re-asserted`() {
        val suffix = UUID.randomUUID().toString()
        val authority = "recovery-expiry-$suffix"
        val imageBase = Uri.parse("content://$authority/images")
        val rows = mapOf(
            // No conservative structural probe exists for webp: INDETERMINATE on read bytes, kept
            // pending but NOT re-armed — every later launch reaches the same answer (AGG4-4).
            KEPT to Row("image/webp", 16L),
            // A JPEG whose descriptor cannot be opened this launch: a transient probe failure on a
            // row a later launch can still adopt, so its expiry IS re-armed.
            TRANSIENT to Row("image/jpeg", 16L),
            // Zero bytes is provably incomplete: deleted.
            DELETED to Row("image/jpeg", 0L),
            // A durable COMPLETE marker adopts: published with IS_PENDING = 0.
            ADOPTED to Row("image/jpeg", 16L),
            // A durable DISCARD marker belongs to the DISCARD stage: kept, but not re-armed.
            DISCARDED to Row("image/webp", 16L),
        )
        val provider = RecordingProvider(imageBase, rows)
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val journal = PendingDiscardJournal(
            context = context,
            databaseName = "recovery-expiry-$suffix.db",
            legacyPreferences = context.getSharedPreferences("recovery-expiry-$suffix", Context.MODE_PRIVATE),
        )
        assertTrue(MediaStoreWriter.markWriteComplete(context, Uri.parse("$imageBase/$ADOPTED")).durable)
        // The legacy preference DISCARD value: exact-identity SQLite marks need a provider
        // identity reader, and both spellings resolve to the same PendingJournalState.DISCARD.
        assertTrue(
            context.getSharedPreferences("pending_media_journal", Context.MODE_PRIVATE).edit()
                .putString("$imageBase/$DISCARDED", PendingDiscardJournal.LEGACY_DISCARD_VALUE)
                .commit(),
        )

        val batch = MediaStoreWriter.cleanupOrphanedPendingBatch(
            context = context,
            cursor = OrphanRecoveryCursor(preflightComplete = true)
                .withAfterId(OrphanRecoveryCollection.VIDEO, OrphanRecoveryCursor.COLLECTION_COMPLETE),
            discardJournal = journal,
            targets = listOf(OrphanRecoveryTarget(imageBase, OrphanRecoveryCollection.IMAGES)),
        )

        assertEquals(1, batch.report.adopted)
        assertEquals(1, batch.report.deleted)
        assertEquals(3, batch.report.retained)
        assertEquals(listOf(1), provider.pendingWrites[TRANSIENT])
        assertFalse(KEPT in provider.pendingWrites)
        assertEquals(listOf(0), provider.pendingWrites[ADOPTED])
        assertFalse(DELETED in provider.pendingWrites)
        assertFalse(DISCARDED in provider.pendingWrites)
    }

    @Test
    fun `an adoptable row whose publish keeps failing re-arms its expiry`() {
        // AGG4-5: ADOPT whose IS_PENDING=0 update fails leaves the row pending; it used to skip the
        // re-arm and reach MediaProvider's expiry.
        val suffix = UUID.randomUUID().toString()
        val authority = "recovery-expiry-publish-$suffix"
        val imageBase = Uri.parse("content://$authority/images")
        val provider = RecordingProvider(imageBase, mapOf(ADOPTED to Row("image/jpeg", 16L)))
        provider.refusePublish += ADOPTED
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val journal = PendingDiscardJournal(
            context = context,
            databaseName = "recovery-expiry-publish-$suffix.db",
            legacyPreferences = context.getSharedPreferences("recovery-expiry-publish-$suffix", Context.MODE_PRIVATE),
        )
        assertTrue(MediaStoreWriter.markWriteComplete(context, Uri.parse("$imageBase/$ADOPTED")).durable)

        val batch = MediaStoreWriter.cleanupOrphanedPendingBatch(
            context = context,
            cursor = OrphanRecoveryCursor(preflightComplete = true)
                .withAfterId(OrphanRecoveryCollection.VIDEO, OrphanRecoveryCursor.COLLECTION_COMPLETE),
            discardJournal = journal,
            targets = listOf(OrphanRecoveryTarget(imageBase, OrphanRecoveryCollection.IMAGES)),
        )

        assertEquals(0, batch.report.adopted)
        assertEquals(1, batch.report.retained)
        assertEquals(setOf(RecoveryFailureClass.PUBLISH), batch.report.failureClasses)
        // Three refused publish attempts, then exactly one expiry re-arm.
        assertEquals(listOf(0, 0, 0, 1), provider.pendingWrites[ADOPTED])
    }

    @Test
    fun `a failed re-assert is reported once per uri and cause and never throws`() {
        val suffix = UUID.randomUUID().toString()
        val authority = "recovery-expiry-fail-$suffix"
        val imageBase = Uri.parse("content://$authority/images")
        val provider = RecordingProvider(imageBase, emptyMap())
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val warnings = mutableListOf<String>()
        val uri = Uri.parse("$imageBase/$KEPT")

        repeat(2) {
            assertFalse(MediaStoreWriter.reassertPending(context, uri) { message, _ -> warnings += message })
        }
        assertEquals(1, warnings.size)

        provider.rows[KEPT] = Row("image/webp", 16L)
        assertTrue(MediaStoreWriter.reassertPending(context, uri) { message, _ -> warnings += message })
        provider.rows.remove(KEPT)
        assertFalse(MediaStoreWriter.reassertPending(context, uri) { message, _ -> warnings += message })
        assertEquals("a relapse after success reports again", 2, warnings.size)
    }

    private data class Row(val mime: String, val size: Long)

    private class RecordingProvider(
        private val imageBase: Uri,
        initialRows: Map<Long, Row>,
    ) : ContentProvider() {
        val rows = initialRows.toSortedMap()
        val pendingWrites = mutableMapOf<Long, MutableList<Int>>()
        val refusePublish = mutableSetOf<Long>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection ?: arrayOf(MediaStore.MediaColumns._ID)
            val ids = when {
                uri == imageBase -> {
                    val afterId = selectionArgs?.lastOrNull()?.toLongOrNull() ?: Long.MIN_VALUE
                    rows.keys.filter { it > afterId }
                }
                uri.toString().startsWith("$imageBase/") ->
                    listOfNotNull(uri.lastPathSegment?.toLongOrNull()?.takeIf { it in rows })
                else -> emptyList()
            }
            return MatrixCursor(columns).apply {
                ids.forEach { id ->
                    val row = rows.getValue(id)
                    addRow(Array<Any?>(columns.size) { index ->
                        when (columns[index]) {
                            MediaStore.MediaColumns._ID -> id
                            MediaStore.MediaColumns.IS_PENDING -> 1
                            MediaStore.MediaColumns.MIME_TYPE -> row.mime
                            MediaStore.MediaColumns.SIZE -> row.size
                            MediaStore.MediaColumns.DISPLAY_NAME -> "IMG_LEGACY_$id"
                            else -> null
                        }
                    })
                }
            }
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
            return if (rows.remove(id) != null) 1 else 0
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
            if (id !in rows) return 0
            val pending = values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
            pending?.let { pendingWrites.getOrPut(id) { mutableListOf() } += it }
            return if (pending == 0 && id in refusePublish) 0 else 1
        }

        override fun getType(uri: Uri): String? = rows[uri.lastPathSegment?.toLongOrNull()]?.mime

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    }

    private companion object {
        const val KEPT = 1L
        const val DELETED = 2L
        const val ADOPTED = 3L
        const val DISCARDED = 4L
        const val TRANSIENT = 5L
    }
}
