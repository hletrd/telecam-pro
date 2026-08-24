package me.hletrd.telecampro.storage

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
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
    fun `process rescan rechecks producer leases and recovers marker capacity`() {
        val leased = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_010_100_000L, 201L)
        val terminal = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_010_100_001L, 202L)
        assertEquals(FamilyDeletionMarkResult.DURABLE, MediaStoreWriter.markFamilyDeletedResult(context, leased))
        assertEquals(FamilyDeletionMarkResult.DURABLE, MediaStoreWriter.markFamilyDeletedResult(context, terminal))
        val producer = MediaStoreWriter.registerStillFamilyProducer(leased)

        val first = MediaStoreWriter.retireCurrentProcessFamilyDeletions(context)

        assertEquals(FamilyDeletionRetirementResult.PRODUCERS_ACTIVE, first[leased])
        assertEquals(FamilyDeletionRetirementResult.RETIRED, first[terminal])
        assertTrue(MediaStoreWriter.isFamilyDeleted(context, leased))
        assertFalse(MediaStoreWriter.isFamilyDeleted(context, terminal))
        assertEquals(1, context.getSharedPreferences("deleted_capture_family_journal", Context.MODE_PRIVATE).all.size)

        producer.close()
        val second = MediaStoreWriter.retireCurrentProcessFamilyDeletions(context)

        assertEquals(FamilyDeletionRetirementResult.RETIRED, second[leased])
        assertFalse(MediaStoreWriter.isFamilyDeleted(context, leased))
        assertTrue(context.getSharedPreferences("deleted_capture_family_journal", Context.MODE_PRIVATE).all.isEmpty())
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
            FamilyDeletionRetirementResult.RETRYABLE,
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
    fun `replacement engine cannot retire delete before old engine future sibling is terminal`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_024_100_000L, 241L)
        // Engine A registers before Camera2 receives the still. At this point its processed/DNG
        // continuations may not have created a row or publication claim yet.
        val oldEngineProducer = MediaStoreWriter.registerStillFamilyProducer(family)
        var absenceQueried = false

        try {
            // Engine B restores the already-published first sibling and commits whole-family Delete.
            assertEquals(FamilyDeletionMarkResult.DURABLE, MediaStoreWriter.markFamilyDeletedResult(context, family))
            assertEquals(
                FamilyDeletionRetirementResult.PRODUCERS_ACTIVE,
                MediaStoreWriter.retireFamilyDeletionMarker(context, family, true) {
                    absenceQueried = true
                    true
                },
            )
            assertFalse(absenceQueried)

            // The future old-Engine sibling arrives only now. It must still see Engine B's marker
            // and enter discard instead of becoming visible in Gallery.
            assertEquals(
                "discard",
                MediaStoreWriter.withFamilyPublicationAuthority(
                    context = context,
                    family = family,
                    deleted = { "discard" },
                    unavailable = { "unavailable" },
                    live = { "publish" },
                ),
            )
        } finally {
            // Models every processed/DNG rejection, exception, or accepted continuation reaching
            // its exactly-once terminal edge after Engine replacement.
            oldEngineProducer.close()
        }

        assertEquals(
            FamilyDeletionRetirementResult.RETIRED,
            MediaStoreWriter.retireFamilyDeletionMarker(context, family, true) { true },
        )
    }

    @Test
    fun `family producer leases are exact ref counted and idempotent`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_024_200_000L, 242L)
        val firstLaneOwner = MediaStoreWriter.registerStillFamilyProducer(family)
        val secondLaneOwner = MediaStoreWriter.registerStillFamilyProducer(family)
        assertEquals(FamilyDeletionMarkResult.DURABLE, MediaStoreWriter.markFamilyDeletedResult(context, family))

        firstLaneOwner.close()
        firstLaneOwner.close()
        assertEquals(
            FamilyDeletionRetirementResult.PRODUCERS_ACTIVE,
            MediaStoreWriter.retireFamilyDeletionMarker(context, family, true) { true },
        )

        secondLaneOwner.close()
        assertEquals(
            FamilyDeletionRetirementResult.RETIRED,
            MediaStoreWriter.retireFamilyDeletionMarker(context, family, true) { true },
        )
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
    fun `blocked family commit does not block unrelated publication marker read`() {
        val blockedFamily = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_027_100_000L, 271L)
        val independentFamily = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_027_200_000L, 272L)
        val commitEntered = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val markerStore = BlockingFamilyMarkerStore(commitEntered, allowCommit)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val blockedMark = executor.submit<FamilyDeletionMarkResult> {
                MediaStoreWriter.markFamilyDeletedResult(blockedFamily, markerStore)
            }
            assertTrue(commitEntered.await(2, TimeUnit.SECONDS))

            val publication = executor.submit<String> {
                MediaStoreWriter.withFamilyPublicationAuthority(
                    family = independentFamily,
                    markerStore = markerStore,
                    deleted = { "discard" },
                    unavailable = { "unavailable" },
                    live = { "published-and-callback-complete" },
                )
            }
            assertEquals("published-and-callback-complete", publication.get(2, TimeUnit.SECONDS))
            assertFalse(blockedMark.isDone)

            allowCommit.countDown()
            assertEquals(FamilyDeletionMarkResult.DURABLE, blockedMark.get(2, TimeUnit.SECONDS))
        } finally {
            allowCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `blocked marker removal does not block unrelated publication or producer admission`() {
        val blockedFamily = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_027_300_000L, 273L)
        val independentFamily = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_027_400_000L, 274L)
        val removeEntered = CountDownLatch(1)
        val allowRemove = CountDownLatch(1)
        val markerStore = BlockingRemoveFamilyMarkerStore(removeEntered, allowRemove)
        val executor = Executors.newFixedThreadPool(3)

        try {
            assertEquals(
                FamilyDeletionMarkResult.DURABLE,
                MediaStoreWriter.markFamilyDeletedResult(blockedFamily, markerStore),
            )
            val retirement = executor.submit<FamilyDeletionRetirementResult> {
                MediaStoreWriter.retireFamilyDeletionMarker(
                    family = blockedFamily,
                    producersTerminal = true,
                    markerStore = markerStore,
                    exactFamilyAbsent = { true },
                )
            }
            assertTrue(removeEntered.await(2, TimeUnit.SECONDS))

            val publication = executor.submit<String> {
                MediaStoreWriter.withFamilyPublicationAuthority(
                    family = independentFamily,
                    markerStore = markerStore,
                    deleted = { "discard" },
                    unavailable = { "unavailable" },
                    live = { "published-and-callback-complete" },
                )
            }
            assertEquals("published-and-callback-complete", publication.get(2, TimeUnit.SECONDS))

            val producer = executor.submit<CaptureFamilyProducerLease> {
                MediaStoreWriter.registerStillFamilyProducer(independentFamily)
            }.get(2, TimeUnit.SECONDS)
            producer.close()
            assertFalse(retirement.isDone)

            allowRemove.countDown()
            assertEquals(FamilyDeletionRetirementResult.RETIRED, retirement.get(2, TimeUnit.SECONDS))
        } finally {
            allowRemove.countDown()
            executor.shutdown()
        }
    }

    @Test
    fun `producer lease installed at registry admission survives retirement query`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_027_500_000L, 275L)
        assertTrue(MediaStoreWriter.markFamilyDeleted(context, family))
        val queryEntered = CountDownLatch(1)
        val allowQuery = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var producer: CaptureFamilyProducerLease? = null

        try {
            val retirement = executor.submit<FamilyDeletionRetirementResult> {
                MediaStoreWriter.retireFamilyDeletionMarker(context, family, true) {
                    queryEntered.countDown()
                    assertTrue(allowQuery.await(2, TimeUnit.SECONDS))
                    true
                }
            }
            assertTrue(queryEntered.await(2, TimeUnit.SECONDS))
            producer = MediaStoreWriter.registerStillFamilyProducer(family)

            allowQuery.countDown()
            assertEquals(
                FamilyDeletionRetirementResult.PRODUCERS_ACTIVE,
                retirement.get(2, TimeUnit.SECONDS),
            )
            assertTrue(MediaStoreWriter.isFamilyDeleted(context, family))
        } finally {
            producer?.close()
            allowQuery.countDown()
            executor.shutdown()
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
            assertEquals(FamilyDeletionRetirementResult.RETRYABLE, retirement.get(2, TimeUnit.SECONDS))
            assertEquals("discard", publication.get(2, TimeUnit.SECONDS))
            assertTrue(MediaStoreWriter.isFamilyDeleted(context, family))
        } finally {
            allowQuery.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `publication claim is visible at registry admission before family monitor wait`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_028_100_000L, 281L)
        assertTrue(MediaStoreWriter.markFamilyDeleted(context, family))
        val admissionInstalled = CountDownLatch(1)
        val allowMonitorWait = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val publication = executor.submit<String> {
                MediaStoreWriter.withFamilyPublicationAuthority(
                    context = context,
                    family = family,
                    deleted = { "discard" },
                    unavailable = { "unavailable" },
                    live = { "publish" },
                    publicationRegistered = {
                        admissionInstalled.countDown()
                        assertTrue(allowMonitorWait.await(2, TimeUnit.SECONDS))
                    },
                )
            }
            assertTrue(admissionInstalled.await(2, TimeUnit.SECONDS))

            // The publisher has joined the registry but deliberately has not attempted the family
            // monitor yet. Its claim must already be visible to retirement at this exact boundary.
            assertEquals(
                FamilyDeletionRetirementResult.RETRYABLE,
                MediaStoreWriter.retireFamilyDeletionMarker(context, family, true) { true },
            )
            assertTrue(MediaStoreWriter.isFamilyDeleted(context, family))

            allowMonitorWait.countDown()
            assertEquals("discard", publication.get(2, TimeUnit.SECONDS))
        } finally {
            allowMonitorWait.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `admissions after retirement seal wait and become post retirement work`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_028_200_000L, 282L)
        val removeEntered = CountDownLatch(1)
        val allowRemove = CountDownLatch(1)
        val markerStore = BlockingRemoveFamilyMarkerStore(removeEntered, allowRemove)
        val executor = Executors.newFixedThreadPool(3)

        assertEquals(
            FamilyDeletionMarkResult.DURABLE,
            MediaStoreWriter.markFamilyDeletedResult(family, markerStore),
        )

        try {
            val retirement = executor.submit<FamilyDeletionRetirementResult> {
                MediaStoreWriter.retireFamilyDeletionMarker(
                    family = family,
                    producersTerminal = true,
                    markerStore = markerStore,
                    exactFamilyAbsent = { true },
                )
            }
            assertTrue(removeEntered.await(2, TimeUnit.SECONDS))

            val producer = executor.submit<CaptureFamilyProducerLease> {
                MediaStoreWriter.registerStillFamilyProducer(family)
            }
            val publication = executor.submit<String> {
                MediaStoreWriter.withFamilyPublicationAuthority(
                    family = family,
                    markerStore = markerStore,
                    deleted = { "discard" },
                    unavailable = { "unavailable" },
                    live = { "post-retirement-publish" },
                )
            }

            assertThrows(TimeoutException::class.java) {
                producer.get(100, TimeUnit.MILLISECONDS)
            }
            assertThrows(TimeoutException::class.java) {
                publication.get(100, TimeUnit.MILLISECONDS)
            }

            allowRemove.countDown()
            assertEquals(FamilyDeletionRetirementResult.RETIRED, retirement.get(2, TimeUnit.SECONDS))
            producer.get(2, TimeUnit.SECONDS).close()
            assertEquals("post-retirement-publish", publication.get(2, TimeUnit.SECONDS))
        } finally {
            allowRemove.countDown()
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
    fun `transient marker removal is retryable and later retirement reclaims capacity`() {
        val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_700_028_300_000L, 283L)
        val markerStore = FlakyRemoveFamilyMarkerStore()
        assertEquals(
            FamilyDeletionMarkResult.DURABLE,
            MediaStoreWriter.markFamilyDeletedResult(family, markerStore),
        )

        assertEquals(
            FamilyDeletionRetirementResult.RETRYABLE,
            MediaStoreWriter.retireFamilyDeletionMarker(
                family = family,
                producersTerminal = true,
                markerStore = markerStore,
                exactFamilyAbsent = { true },
            ),
        )
        assertEquals(1, markerStore.size())

        assertEquals(
            FamilyDeletionRetirementResult.RETIRED,
            MediaStoreWriter.retireFamilyDeletionMarker(
                family = family,
                producersTerminal = true,
                markerStore = markerStore,
                exactFamilyAbsent = { true },
            ),
        )
        assertEquals(0, markerStore.size())
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

    private class BlockingFamilyMarkerStore(
        private val commitEntered: CountDownLatch,
        private val allowCommit: CountDownLatch,
    ) : FamilyDeletionMarkerStore {
        private val markers = ConcurrentHashMap<String, String>()

        override fun contains(key: String): Boolean = markers.containsKey(key)

        override fun size(): Int = markers.size

        override fun put(key: String, owner: String): Boolean {
            commitEntered.countDown()
            check(allowCommit.await(2, TimeUnit.SECONDS))
            markers[key] = owner
            return true
        }

        override fun remove(key: String): Boolean {
            markers.remove(key)
            return true
        }
    }

    private class BlockingRemoveFamilyMarkerStore(
        private val removeEntered: CountDownLatch,
        private val allowRemove: CountDownLatch,
    ) : FamilyDeletionMarkerStore {
        private val markers = ConcurrentHashMap<String, String>()

        override fun contains(key: String): Boolean = markers.containsKey(key)

        override fun size(): Int = markers.size

        override fun put(key: String, owner: String): Boolean {
            markers[key] = owner
            return true
        }

        override fun remove(key: String): Boolean {
            removeEntered.countDown()
            check(allowRemove.await(2, TimeUnit.SECONDS))
            markers.remove(key)
            return true
        }
    }

    private class FlakyRemoveFamilyMarkerStore : FamilyDeletionMarkerStore {
        private val markers = ConcurrentHashMap<String, String>()
        private var removalAttempts = 0

        override fun contains(key: String): Boolean = markers.containsKey(key)

        override fun size(): Int = markers.size

        override fun put(key: String, owner: String): Boolean {
            markers[key] = owner
            return true
        }

        override fun remove(key: String): Boolean {
            removalAttempts += 1
            if (removalAttempts == 1) return false
            markers.remove(key)
            return true
        }
    }
}
