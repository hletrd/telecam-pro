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
import me.hletrd.telecampro.camera.executeLaunchMediaRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/**
 * Launch media recovery used to stop for good at the first persistently failing media row: the
 * media stage never set `continueAfterFailureExhaustion`, so after the retry budget the whole
 * recovery returned without advancing, and every later Images/Video page and the DISCARD stage
 * never ran — on every launch. A page that advanced its cursor may now continue past exhaustion;
 * a page that did not (its collection query failed) must still stop rather than loop.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchRecoveryProgressTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a persistently failing media row no longer starves later pages`() {
        val fixture = register(stuckIds = setOf(1L), rowIds = listOf(1L, 2L, 3L))

        val completion = executeLaunchMediaRecovery(maxFailureAttempts = 2) { cursor ->
            MediaStoreWriter.cleanupOrphanedPendingBatch(
                context = context,
                cursor = cursor.copy(preflightComplete = true),
                batchLimit = 1,
                discardJournal = fixture.journal,
                targets = fixture.targets,
            )
        }

        // Rows 2 and 3 sit on later one-row pages behind the wedged row 1.
        assertEquals(setOf(2L, 3L), fixture.provider.deleted)
        assertEquals(2, completion.report.deleted)
        assertEquals(setOf(RecoveryFailureClass.DELETE), completion.report.failureClasses)
        assertEquals(RecoveryRetryDecision.EXHAUSTED, completion.decision)
    }

    @Test
    fun `a failing collection query still stops instead of looping`() {
        val fixture = register(stuckIds = emptySet(), rowIds = listOf(1L), failImageQuery = true)
        var batches = 0

        val completion = executeLaunchMediaRecovery(maxFailureAttempts = 2) { cursor ->
            batches += 1
            check(batches < 50) { "recovery looped on a non-advancing failed query" }
            MediaStoreWriter.cleanupOrphanedPendingBatch(
                context = context,
                cursor = cursor.copy(preflightComplete = true),
                discardJournal = fixture.journal,
                targets = fixture.targets,
            )
        }

        assertEquals(RecoveryRetryDecision.EXHAUSTED, completion.decision)
        assertEquals(setOf(RecoveryFailureClass.QUERY), completion.report.failureClasses)
        assertTrue(fixture.provider.deleted.isEmpty())
        assertTrue("batches=$batches", batches <= 4)
    }

    @Test
    fun `media page continuation is granted only when that page advanced`() {
        val advancing = register(stuckIds = setOf(1L), rowIds = listOf(1L, 2L))
        val advanced = MediaStoreWriter.cleanupOrphanedPendingBatch(
            context = context,
            cursor = OrphanRecoveryCursor(preflightComplete = true),
            batchLimit = 1,
            discardJournal = advancing.journal,
            targets = advancing.targets,
        )
        assertTrue(advanced.report.retryRequired)
        assertTrue(advanced.continueAfterFailureExhaustion)

        val stuck = register(stuckIds = emptySet(), rowIds = listOf(1L), failImageQuery = true)
        val start = OrphanRecoveryCursor(preflightComplete = true)
            .withAfterId(OrphanRecoveryCollection.VIDEO, OrphanRecoveryCursor.COLLECTION_COMPLETE)
        val refused = MediaStoreWriter.cleanupOrphanedPendingBatch(
            context = context,
            cursor = start,
            discardJournal = stuck.journal,
            targets = stuck.targets,
        )
        assertTrue(refused.report.retryRequired)
        assertEquals(start, refused.nextCursor)
        assertEquals(false, refused.continueAfterFailureExhaustion)
    }

    private class Fixture(
        val provider: InvalidPendingRowsProvider,
        val targets: List<OrphanRecoveryTarget>,
        val journal: PendingDiscardJournal,
    )

    private fun register(
        stuckIds: Set<Long>,
        rowIds: List<Long>,
        failImageQuery: Boolean = false,
    ): Fixture {
        val suffix = UUID.randomUUID().toString()
        val authority = "recovery-progress-$suffix"
        val imageBase = Uri.parse("content://$authority/images")
        val videoBase = Uri.parse("content://$authority/videos")
        val provider = InvalidPendingRowsProvider(imageBase, rowIds, stuckIds, failImageQuery)
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return Fixture(
            provider = provider,
            targets = listOf(
                OrphanRecoveryTarget(imageBase, OrphanRecoveryCollection.IMAGES),
                OrphanRecoveryTarget(videoBase, OrphanRecoveryCollection.VIDEO),
            ),
            journal = PendingDiscardJournal(
                context = context,
                databaseName = "recovery-progress-$suffix.db",
                legacyPreferences = context.getSharedPreferences(
                    "recovery-progress-$suffix",
                    Context.MODE_PRIVATE,
                ),
            ),
        )
    }

    /** Zero-byte pending JPEG rows: each is provably INVALID, so recovery deletes it. */
    private class InvalidPendingRowsProvider(
        private val imageBase: Uri,
        rowIds: List<Long>,
        private val stuckIds: Set<Long>,
        private val failImageQuery: Boolean,
    ) : ContentProvider() {
        private val present = rowIds.toSortedSet()
        val deleted = linkedSetOf<Long>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection ?: arrayOf(MediaStore.MediaColumns._ID)
            val rows = when {
                uri == imageBase -> {
                    if (failImageQuery) throw IllegalStateException("provider query failed")
                    // orphanSweepPage appends the `_ID > afterId` argument last.
                    val afterId = selectionArgs?.lastOrNull()?.toLongOrNull() ?: Long.MIN_VALUE
                    present.filter { it > afterId }
                }
                uri.toString().startsWith("$imageBase/") ->
                    listOfNotNull(uri.lastPathSegment?.toLongOrNull()?.takeIf { it in present })
                else -> emptyList()
            }
            return MatrixCursor(columns).apply {
                rows.forEach { id ->
                    addRow(Array<Any?>(columns.size) { index ->
                        when (columns[index]) {
                            MediaStore.MediaColumns._ID -> id
                            MediaStore.MediaColumns.IS_PENDING -> 1
                            MediaStore.MediaColumns.MIME_TYPE -> "image/jpeg"
                            MediaStore.MediaColumns.SIZE -> 0L
                            MediaStore.MediaColumns.DISPLAY_NAME -> "IMG_LEGACY_$id.jpg"
                            else -> null
                        }
                    })
                }
            }
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
            if (id in stuckIds || id !in present) return 0
            present.remove(id)
            deleted += id
            return 1
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun getType(uri: Uri): String? = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    }
}
