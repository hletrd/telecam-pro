package me.hletrd.telecampro.storage

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `blocked retirement for one family does not block durable mark for another`() {
        val blockedFamily = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_021_000_000L, 21L)
        val independentFamily = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_022_000_000L, 22L)
        assertTrue(MediaStoreWriter.markFamilyDeleted(context, blockedFamily))
        val queryEntered = CountDownLatch(1)
        val allowQuery = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val retirement = executor.submit<FamilyDeletionRetirementResult> {
                MediaStoreWriter.retireFamilyDeletionMarker(context, blockedFamily, true) {
                    queryEntered.countDown()
                    assertTrue(allowQuery.await(2, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(queryEntered.await(2, TimeUnit.SECONDS))

            val independentMark = executor.submit<FamilyDeletionMarkResult> {
                MediaStoreWriter.markFamilyDeletedResult(context, independentFamily)
            }
            assertEquals(
                FamilyDeletionMarkResult.DURABLE,
                independentMark.get(2, TimeUnit.SECONDS),
            )
            assertTrue(MediaStoreWriter.isFamilyDeleted(context, independentFamily))
            assertFalse(retirement.isDone)

            allowQuery.countDown()
            assertEquals(
                FamilyDeletionRetirementResult.RETIRED,
                retirement.get(2, TimeUnit.SECONDS),
            )
        } finally {
            allowQuery.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `same family remark waits for retirement and preserves newer delete intent`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_023_000_000L, 23L)
        assertTrue(MediaStoreWriter.markFamilyDeleted(context, family))
        val queryEntered = CountDownLatch(1)
        val allowQuery = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val retirement = executor.submit<FamilyDeletionRetirementResult> {
                MediaStoreWriter.retireFamilyDeletionMarker(context, family, true) {
                    queryEntered.countDown()
                    assertTrue(allowQuery.await(2, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(queryEntered.await(2, TimeUnit.SECONDS))
            val remark = executor.submit<FamilyDeletionMarkResult> {
                MediaStoreWriter.markFamilyDeletedResult(context, family)
            }
            assertThrows(TimeoutException::class.java) {
                remark.get(100, TimeUnit.MILLISECONDS)
            }

            allowQuery.countDown()
            assertEquals(
                FamilyDeletionRetirementResult.RETIRED,
                retirement.get(2, TimeUnit.SECONDS),
            )
            assertEquals(FamilyDeletionMarkResult.DURABLE, remark.get(2, TimeUnit.SECONDS))
            assertTrue(MediaStoreWriter.isFamilyDeleted(context, family))
        } finally {
            allowQuery.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `replacement engine delete wins after old engine stale family precheck`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_024_000_000L, 24L)
        val events = mutableListOf<String>()

        // Engine A performed the former bare precheck before Engine B committed Delete.
        assertFalse(MediaStoreWriter.isFamilyDeleted(context, family))
        assertEquals(FamilyDeletionMarkResult.DURABLE, MediaStoreWriter.markFamilyDeletedResult(context, family))

        val result = MediaStoreWriter.withFamilyPublicationAuthority(
            context = context,
            family = family,
            deleted = {
                events += "discard"
                "deleted"
            },
            unavailable = { "unavailable" },
            live = {
                events += "publish"
                "published"
            },
        )

        assertEquals("deleted", result)
        assertEquals(listOf("discard"), events)
    }

    @Test
    fun `publication first owns provider and callback interval before family delete`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_025_000_000L, 25L)
        val publicationEntered = CountDownLatch(1)
        val allowCallbackToFinish = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val publication = executor.submit<String> {
                MediaStoreWriter.withFamilyPublicationAuthority(
                    context = context,
                    family = family,
                    deleted = { error("delete did not win") },
                    unavailable = { error("journal unavailable") },
                    live = {
                        events += "provider-publish"
                        publicationEntered.countDown()
                        assertTrue(allowCallbackToFinish.await(2, TimeUnit.SECONDS))
                        events += "saved-callback"
                        "published"
                    },
                )
            }
            assertTrue(publicationEntered.await(2, TimeUnit.SECONDS))
            val deletion = executor.submit<FamilyDeletionMarkResult> {
                val marked = MediaStoreWriter.markFamilyDeletedResult(context, family)
                events += "delete-mark"
                marked
            }
            assertThrows(TimeoutException::class.java) {
                deletion.get(100, TimeUnit.MILLISECONDS)
            }

            allowCallbackToFinish.countDown()
            assertEquals("published", publication.get(2, TimeUnit.SECONDS))
            assertEquals(FamilyDeletionMarkResult.DURABLE, deletion.get(2, TimeUnit.SECONDS))
            assertEquals(listOf("provider-publish", "saved-callback", "delete-mark"), events)
        } finally {
            allowCallbackToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `blocked family publication does not block unrelated delete mark`() {
        val blockedFamily = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_026_000_000L, 26L)
        val independentFamily = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_027_000_000L, 27L)
        val publicationEntered = CountDownLatch(1)
        val allowPublication = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val publication = executor.submit<Boolean> {
                MediaStoreWriter.withFamilyPublicationAuthority(
                    context = context,
                    family = blockedFamily,
                    deleted = { false },
                    unavailable = { false },
                    live = {
                        publicationEntered.countDown()
                        assertTrue(allowPublication.await(2, TimeUnit.SECONDS))
                        true
                    },
                )
            }
            assertTrue(publicationEntered.await(2, TimeUnit.SECONDS))

            assertEquals(
                FamilyDeletionMarkResult.DURABLE,
                executor.submit<FamilyDeletionMarkResult> {
                    MediaStoreWriter.markFamilyDeletedResult(context, independentFamily)
                }.get(2, TimeUnit.SECONDS),
            )
            assertFalse(publication.isDone)

            allowPublication.countDown()
            assertTrue(publication.get(2, TimeUnit.SECONDS))
        } finally {
            allowPublication.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `queued publication prevents retirement from erasing its family veto`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_028_000_000L, 28L)
        assertTrue(MediaStoreWriter.markFamilyDeleted(context, family))
        val queryEntered = CountDownLatch(1)
        val allowQuery = CountDownLatch(1)
        val publicationRegistered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val retirement = executor.submit<FamilyDeletionRetirementResult> {
                MediaStoreWriter.retireFamilyDeletionMarker(context, family, true) {
                    queryEntered.countDown()
                    assertTrue(allowQuery.await(2, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(queryEntered.await(2, TimeUnit.SECONDS))
            val publication = executor.submit<String> {
                MediaStoreWriter.withFamilyPublicationAuthority(
                    context = context,
                    family = family,
                    deleted = { "discard" },
                    unavailable = { "unavailable" },
                    live = { "publish" },
                    publicationRegistered = { publicationRegistered.countDown() },
                )
            }
            assertTrue(publicationRegistered.await(2, TimeUnit.SECONDS))

            allowQuery.countDown()
            assertEquals(FamilyDeletionRetirementResult.RETAINED, retirement.get(2, TimeUnit.SECONDS))
            assertEquals("discard", publication.get(2, TimeUnit.SECONDS))
            assertTrue(MediaStoreWriter.isFamilyDeleted(context, family))
        } finally {
            allowQuery.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `publication failure releases exact family authority for deletion`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_029_000_000L, 29L)

        assertFalse(
            MediaStoreWriter.withFamilyPublicationAuthority(
                context = context,
                family = family,
                deleted = { true },
                unavailable = { true },
                live = { false },
            ),
        )
        assertEquals(FamilyDeletionMarkResult.DURABLE, MediaStoreWriter.markFamilyDeletedResult(context, family))
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
        assertFalse(query.selection.contains(MediaStore.MediaColumns.IS_PENDING))
    }

    @Test
    fun `legacy oversized journal recovery takes only one bounded query batch`() {
        val entries = (0 until 200).associate { "family-$it" to "owner" }

        val batch = boundedDeletedFamilyBatch(entries, MediaStoreWriter.MAX_DELETED_FAMILY_MARKERS)

        assertEquals(MediaStoreWriter.MAX_DELETED_FAMILY_MARKERS, batch.entries.size)
        assertTrue(batch.hasMore)
    }
}
