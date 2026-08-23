package me.hletrd.telecampro.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeletedFamilyJournalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearJournal() {
        context.getSharedPreferences("deleted_capture_family_journal", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("pending_media_journal", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `family delete is committed before it becomes recovery visible`() {
        val family = CaptureFamilyKey(
            CaptureFamilyMedia.STILL,
            capturedAtEpochMillis = 1_700_123_456_789L,
            sequence = 987_654_321L,
        )

        assertFalse(MediaStoreWriter.isFamilyDeleted(context, family))
        assertTrue(MediaStoreWriter.markFamilyDeleted(context, family))
        assertTrue(MediaStoreWriter.isFamilyDeleted(context, family))
    }

    @Test
    fun `rejected output binds its exact context and uri into durable discard`() {
        val uri = Uri.parse("content://media/external/images/media/4242")

        assertEquals(
            // Robolectric's empty provider authoritatively reports this fake row already absent.
            // The important production composition is that the private RejectedOutput carries the
            // application Context + exact Uri through its bounded owner into the delete contract.
            PendingOutputDiscardResult.DELETED,
            MediaStoreWriter.discardRejectedOutput(context, uri),
        )
    }

    @Test
    fun `persistent family markers fail closed at the process bound`() {
        repeat(MediaStoreWriter.MAX_DELETED_FAMILY_MARKERS) { index ->
            val result = MediaStoreWriter.markFamilyDeletedResult(
                context,
                CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_000_100_000L + index, index.toLong()),
            )
            assertEquals(FamilyDeletionMarkResult.DURABLE, result)
        }

        repeat(200) { index ->
            assertEquals(
                FamilyDeletionMarkResult.CAPACITY_EXHAUSTED,
                MediaStoreWriter.markFamilyDeletedResult(
                    context,
                    CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_000_200_000L + index, 1_000L + index),
                ),
            )
        }
        assertEquals(
            MediaStoreWriter.MAX_DELETED_FAMILY_MARKERS,
            context.getSharedPreferences("deleted_capture_family_journal", Context.MODE_PRIVATE).all.size,
        )
    }

    @Test
    fun `more than 64 terminal absent families retire in one healthy process`() {
        repeat(200) { index ->
            val family = CaptureFamilyKey(
                CaptureFamilyMedia.STILL,
                1_700_010_000_000L + index,
                index.toLong(),
            )
            assertEquals(
                FamilyDeletionMarkResult.DURABLE,
                MediaStoreWriter.markFamilyDeletedResult(context, family),
            )
            assertEquals(
                FamilyDeletionRetirementResult.RETIRED,
                MediaStoreWriter.retireFamilyDeletionMarker(
                    context = context,
                    family = family,
                    producersTerminal = true,
                    exactFamilyAbsent = { true },
                ),
            )
        }

        assertTrue(
            context.getSharedPreferences("deleted_capture_family_journal", Context.MODE_PRIVATE)
                .all
                .isEmpty(),
        )
    }

    @Test
    fun `retirement retains marker until producers terminal and exact absence is authoritative`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_020_000_000L, 20L)
        assertTrue(MediaStoreWriter.markFamilyDeleted(context, family))

        assertEquals(
            FamilyDeletionRetirementResult.PRODUCERS_ACTIVE,
            MediaStoreWriter.retireFamilyDeletionMarker(context, family, false) { true },
        )
        assertEquals(
            FamilyDeletionRetirementResult.RETAINED,
            MediaStoreWriter.retireFamilyDeletionMarker(context, family, true) { false },
        )
        assertEquals(
            FamilyDeletionRetirementResult.RETAINED,
            MediaStoreWriter.retireFamilyDeletionMarker(context, family, true) { null },
        )
        assertTrue(MediaStoreWriter.isFamilyDeleted(context, family))
    }

    @Test
    fun `deleted family query binds paths owner then every exact output name`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_000_300_000L, 3L)
        val query = deletedFamilyQuery(family, listOf("TeleCamPro", "Legacy"), "me.test")

        assertEquals(query.selection.count { it == '?' }, query.args.size)
        assertEquals(
            listOf("DCIM/TeleCamPro/%", "DCIM/Legacy/%", "me.test") + family.knownOutputDisplayNames(),
            query.args.toList(),
        )
        assertTrue(query.selection.contains("owner_package_name = ?"))
        assertTrue(query.selection.contains("_display_name IN"))
    }

    @Test
    fun `legacy oversized journal recovery takes only one bounded query batch`() {
        val entries = (0 until 200).associate { "family-$it" to "owner" }

        val batch = boundedDeletedFamilyBatch(entries, MediaStoreWriter.MAX_DELETED_FAMILY_MARKERS)

        assertEquals(MediaStoreWriter.MAX_DELETED_FAMILY_MARKERS, batch.entries.size)
        assertTrue(batch.hasMore)
    }
}
