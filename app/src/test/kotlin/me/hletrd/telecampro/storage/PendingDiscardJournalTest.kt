package me.hletrd.telecampro.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import me.hletrd.telecampro.camera.executeLaunchMediaRecovery
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
        assertFalse(preferences.contains("content://bulk/00000"))
        assertFalse(preferences.contains("content://bulk/00063"))
        assertTrue(preferences.contains("content://bulk/00064"))
        assertEquals(9_938, preferences.all.size)
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
            removeLegacyEntries = { error("injected cleanup interruption") },
        )

        assertEquals(listOf(uri), interrupted.page(afterKey = null, batchLimit = 8).keys)
        assertEquals(DiscardJournalLookup.PRESENT, interrupted.lookup(uri))
        assertEquals("discard", preferences.getString(uri, null))

        val relaunched = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = preferences,
        )
        assertEquals(listOf(uri), relaunched.page(afterKey = null, batchLimit = 8).keys)
        assertEquals(DiscardJournalLookup.PRESENT, relaunched.lookup(uri))
        assertFalse(preferences.contains(uri))
    }

    @Test
    fun `persistent cleanup failure never reimports the legacy set across pages`() {
        val suffix = UUID.randomUUID().toString()
        val preferences = context.getSharedPreferences("legacy-$suffix", Context.MODE_PRIVATE)
        val editor = preferences.edit()
        repeat(130) { index ->
            editor.putString("content://legacy/${index.toString().padStart(3, '0')}", "discard")
        }
        assertTrue(editor.commit())
        val cleanupSizes = mutableListOf<Int>()
        val journal = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = preferences,
            removeLegacyEntries = { keys ->
                cleanupSizes += keys.size
                error("injected persistent cleanup failure")
            },
        )

        val first = journal.page(afterKey = null, batchLimit = 64)
        val lateLegacyKey = "content://legacy/999"
        assertTrue(preferences.edit().putString(lateLegacyKey, "discard").commit())
        val second = journal.page(afterKey = first.nextAfterKey, batchLimit = 64)
        val third = journal.page(afterKey = second.nextAfterKey, batchLimit = 64)

        assertEquals(listOf(64, 64, 2), cleanupSizes)
        assertTrue(listOf(first, second, third).all { it.rowsRead <= 65 })
        assertFalse((first.keys + second.keys + third.keys).contains(lateLegacyKey))
        assertEquals(131, preferences.all.size)

        val retry = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = preferences,
        )
        var afterKey: String? = null
        do {
            val page = retry.page(afterKey = afterKey, batchLimit = 64)
            assertTrue(page.rowsRead <= 65)
            afterKey = page.nextAfterKey
        } while (page.hasMore)
        assertEquals(setOf(lateLegacyKey), preferences.all.keys)
    }

    @Test
    fun `blocked legacy cleanup does not own unrelated discard database work`() {
        val suffix = UUID.randomUUID().toString()
        val preferences = context.getSharedPreferences("legacy-$suffix", Context.MODE_PRIVATE)
        val legacyUri = "content://legacy/blocked-cleanup"
        val independentUri = "content://independent/progress"
        assertTrue(preferences.edit().putString(legacyUri, "discard").commit())
        val cleanupEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val blockedJournal = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = preferences,
            removeLegacyEntries = {
                cleanupEntered.countDown()
                assertTrue(allowCleanup.await(2, TimeUnit.SECONDS))
            },
        )
        val independentJournal = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = preferences,
            identityReader = testIdentityReader(),
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val blockedPage = executor.submit<DiscardJournalPage> {
                blockedJournal.page(afterKey = null, batchLimit = 8)
            }
            assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))

            val independentProgress = executor.submit<Boolean> {
                if (!independentJournal.mark(independentUri)) return@submit false
                if (independentJournal.lookup(independentUri) != DiscardJournalLookup.PRESENT) {
                    return@submit false
                }
                val publishLookup = independentJournal.withLookupAuthority(independentUri) { it }
                if (publishLookup != DiscardJournalLookup.PRESENT) return@submit false
                if (!independentJournal.remove(independentUri)) return@submit false
                independentJournal.lookup(independentUri) == DiscardJournalLookup.ABSENT
            }

            assertTrue(independentProgress.get(2, TimeUnit.SECONDS))
            assertFalse(blockedPage.isDone)
            allowCleanup.countDown()
            assertEquals(listOf(legacyUri), blockedPage.get(2, TimeUnit.SECONDS).keys)
        } finally {
            allowCleanup.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `blocked legacy snapshot does not own unrelated discard database work`() {
        val suffix = UUID.randomUUID().toString()
        val preferences = context.getSharedPreferences("legacy-$suffix", Context.MODE_PRIVATE)
        val legacyUri = "content://legacy/blocked-snapshot"
        val independentUri = "content://independent/snapshot-progress"
        assertTrue(preferences.edit().putString(legacyUri, "discard").commit())
        val snapshotEntered = CountDownLatch(1)
        val allowSnapshot = CountDownLatch(1)
        val blockedJournal = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = preferences,
            readLegacyEntries = {
                snapshotEntered.countDown()
                assertTrue(allowSnapshot.await(2, TimeUnit.SECONDS))
                preferences.all
            },
        )
        val independentJournal = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = preferences,
            identityReader = testIdentityReader(),
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val blockedPage = executor.submit<DiscardJournalPage> {
                blockedJournal.page(afterKey = null, batchLimit = 8)
            }
            assertTrue(snapshotEntered.await(2, TimeUnit.SECONDS))

            val independentProgress = executor.submit<Boolean> {
                if (!independentJournal.mark(independentUri)) return@submit false
                if (independentJournal.lookup(independentUri) != DiscardJournalLookup.PRESENT) {
                    return@submit false
                }
                val publishLookup = independentJournal.withLookupAuthority(independentUri) { it }
                if (publishLookup != DiscardJournalLookup.PRESENT) return@submit false
                if (!independentJournal.remove(independentUri)) return@submit false
                independentJournal.lookup(independentUri) == DiscardJournalLookup.ABSENT
            }

            assertTrue(independentProgress.get(2, TimeUnit.SECONDS))
            assertFalse(blockedPage.isDone)
            allowSnapshot.countDown()
            assertEquals(listOf(legacyUri), blockedPage.get(2, TimeUnit.SECONDS).keys)
        } finally {
            allowSnapshot.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed media delete retains marker and exact successful retry removes it`() {
        val authority = "discard-retention-${UUID.randomUUID()}"
        val provider = RetainingProvider()
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.parse("content://$authority/rows/1")
        val journal = PendingDiscardJournal(
            context = context,
            identityReader = testIdentityReader(),
        )

        assertEquals(
            PendingOutputDiscardResult.RECOVERY_MARKED,
            MediaStoreWriter.discardPendingOutput(
                context,
                PendingOutputAllocation(
                    uri,
                    CaptureFamilyKey(CaptureFamilyMedia.STILL, 1L, 1L),
                    testIdentity(),
                ),
                journal,
            ),
        )
        assertEquals(
            DiscardJournalLookup.PRESENT,
            PendingDiscardJournal(context).lookup(uri.toString()),
        )

        provider.allowDelete = true
        assertTrue(MediaStoreWriter.delete(context, uri))
        assertEquals(
            DiscardJournalLookup.ABSENT,
            PendingDiscardJournal(context).lookup(uri.toString()),
        )
    }

    @Test
    fun `publication that owns an exact uri completes before a waiting discard mark`() {
        val suffix = UUID.randomUUID().toString()
        val authority = "discard-publish-first-$suffix"
        val provider = BlockingUpdateProvider()
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.parse("content://$authority/rows/1")
        val journal = newJournal()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val published = executor.submit<Boolean> {
                MediaStoreWriter.publish(context, uri, journal)
            }
            assertTrue(provider.updateEntered.await(2, TimeUnit.SECONDS))
            val markStarted = CountDownLatch(1)
            val marked = executor.submit<Boolean> {
                markStarted.countDown()
                journal.mark(uri.toString())
            }
            assertTrue(markStarted.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) {
                marked.get(100, TimeUnit.MILLISECONDS)
            }

            provider.allowUpdate.countDown()

            assertTrue(published.get(2, TimeUnit.SECONDS))
            assertTrue(marked.get(2, TimeUnit.SECONDS))
            assertEquals(DiscardJournalLookup.PRESENT, journal.lookup(uri.toString()))
            assertEquals(1, provider.updateCalls.get())
        } finally {
            provider.allowUpdate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `discard mark that owns an exact uri blocks publication before provider io`() {
        val suffix = UUID.randomUUID().toString()
        val authority = "discard-mark-first-$suffix"
        val provider = BlockingUpdateProvider()
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.parse("content://$authority/rows/1")
        val markerCommitted = CountDownLatch(1)
        val finishMarker = CountDownLatch(1)
        val journal = PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = context.getSharedPreferences(
                "legacy-$suffix",
                Context.MODE_PRIVATE,
            ),
            removeLegacyEntries = {
                markerCommitted.countDown()
                assertTrue(finishMarker.await(2, TimeUnit.SECONDS))
            },
            identityReader = testIdentityReader(),
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val marked = executor.submit<Boolean> { journal.mark(uri.toString()) }
            assertTrue(markerCommitted.await(2, TimeUnit.SECONDS))
            val publishStarted = CountDownLatch(1)
            val published = executor.submit<Boolean> {
                publishStarted.countDown()
                MediaStoreWriter.publish(context, uri, journal)
            }
            assertTrue(publishStarted.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) {
                published.get(100, TimeUnit.MILLISECONDS)
            }
            assertEquals(0, provider.updateCalls.get())

            finishMarker.countDown()

            assertTrue(marked.get(2, TimeUnit.SECONDS))
            assertFalse(published.get(2, TimeUnit.SECONDS))
            assertEquals(DiscardJournalLookup.PRESENT, journal.lookup(uri.toString()))
            assertEquals(0, provider.updateCalls.get())
        } finally {
            finishMarker.countDown()
            provider.allowUpdate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `blocked publication for one uri does not block journal progress for another`() {
        val suffix = UUID.randomUUID().toString()
        val authority = "discard-two-uri-$suffix"
        val provider = BlockingUpdateProvider()
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val blockedUri = Uri.parse("content://$authority/rows/a")
        val independentUri = Uri.parse("content://$authority/rows/b").toString()
        val journal = newJournal()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val blockedPublication = executor.submit<Boolean> {
                MediaStoreWriter.publish(context, blockedUri, journal)
            }
            assertTrue(provider.updateEntered.await(2, TimeUnit.SECONDS))

            val independentProgress = executor.submit<Boolean> {
                if (!journal.mark(independentUri)) return@submit false
                if (journal.lookup(independentUri) != DiscardJournalLookup.PRESENT) {
                    return@submit false
                }
                if (!journal.page(afterKey = null, batchLimit = 8).keys.contains(independentUri)) {
                    return@submit false
                }
                if (!journal.remove(independentUri)) return@submit false
                journal.lookup(independentUri) == DiscardJournalLookup.ABSENT
            }

            assertTrue(independentProgress.get(2, TimeUnit.SECONDS))
            assertFalse(blockedPublication.isDone)

            provider.allowUpdate.countDown()
            assertTrue(blockedPublication.get(2, TimeUnit.SECONDS))
        } finally {
            provider.allowUpdate.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `v1 sqlite rows migrate additively as legacy and a fresh mark upgrades exact identity`() {
        val suffix = UUID.randomUUID().toString()
        val databaseName = "discard-v1-$suffix.db"
        val uri = "content://media/external_primary/images/media/1"
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL(
                "CREATE TABLE pending_discards (uri TEXT NOT NULL PRIMARY KEY)",
            )
            database.execSQL(
                "CREATE TABLE journal_metadata (" +
                    "metadata_key TEXT NOT NULL PRIMARY KEY, metadata_value TEXT NOT NULL)",
            )
            database.execSQL("INSERT INTO pending_discards (uri) VALUES (?)", arrayOf(uri))
            database.version = 1
        }
        val expected = testIdentity()
        val journal = PendingDiscardJournal(
            context = context,
            databaseName = databaseName,
            legacyPreferences = context.getSharedPreferences("legacy-v1-$suffix", Context.MODE_PRIVATE),
            identityReader = testIdentityReader(PendingDiscardIdentityRead.Present(expected)),
        )

        val migrated = journal.page(afterKey = null, batchLimit = 8).records.single()

        assertEquals(1, migrated.recordVersion)
        assertEquals(null, migrated.identity)
        assertEquals(DiscardReplayIdentity.LEGACY, journal.replayIdentity(migrated))
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL(
                "UPDATE pending_discards SET record_version = ? WHERE uri = ?",
                arrayOf<Any>(PendingDiscardJournal.IDENTITY_RECORD_VERSION, uri),
            )
        }
        val incomplete = journal.page(afterKey = null, batchLimit = 8).records.single()
        assertEquals(PendingDiscardJournal.IDENTITY_RECORD_VERSION, incomplete.recordVersion)
        assertEquals(null, incomplete.identity)
        assertEquals(DiscardReplayIdentity.UNAVAILABLE, journal.replayIdentity(incomplete))
        assertTrue(journal.mark(uri))
        val upgraded = journal.page(afterKey = null, batchLimit = 8).records.single()
        assertEquals(PendingDiscardJournal.IDENTITY_RECORD_VERSION, upgraded.recordVersion)
        assertEquals(expected, upgraded.identity)
        assertEquals(DiscardReplayIdentity.MATCH, journal.replayIdentity(upgraded))
    }

    @Test
    fun `stable identity retries exact delete and clears only after provider success`() {
        val suffix = UUID.randomUUID().toString()
        val authority = "discard-stable-$suffix"
        val uri = Uri.parse("content://$authority/images/1")
        val provider = ReplayProvider()
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val expected = testIdentity()
        val reader = MutableIdentityReader(PendingDiscardIdentityRead.Present(expected))
        val journal = replayJournal(suffix, reader)
        assertTrue(journal.mark(uri.toString()))

        val failed = MediaStoreWriter.cleanupDiscardJournalBatch(
            context = context,
            afterKey = null,
            batchLimit = 8,
            discardJournal = journal,
        )

        assertEquals(1, provider.deleteCalls)
        assertTrue(provider.lastDeleteSelection.orEmpty().contains(MediaStore.MediaColumns.GENERATION_ADDED))
        assertEquals(1, failed.report.errors)
        assertEquals(DiscardJournalLookup.PRESENT, journal.lookup(uri.toString()))

        provider.allowDelete = true
        val retried = MediaStoreWriter.cleanupDiscardJournalBatch(
            context = context,
            afterKey = null,
            batchLimit = 8,
            discardJournal = journal,
        )

        assertEquals(2, provider.deleteCalls)
        assertEquals(1, retried.report.deleted)
        assertEquals(DiscardJournalLookup.ABSENT, journal.lookup(uri.toString()))
    }

    @Test
    fun `conditional replay delete rejects uri reassignment after identity query`() {
        val suffix = UUID.randomUUID().toString()
        val authority = "discard-racing-remap-$suffix"
        val uri = Uri.parse("content://$authority/images/1")
        val provider = ReplayProvider(allowDelete = true)
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val expected = testIdentity()
        val reader = MutableIdentityReader(PendingDiscardIdentityRead.Present(expected))
        val journal = replayJournal(suffix, reader)
        assertTrue(journal.mark(uri.toString()))

        // The replay query observed the expected row, but the fake provider reassigns this URI
        // immediately before its atomic delete predicate is evaluated.
        provider.generationAdded = expected.generationAdded + 1
        val replay = MediaStoreWriter.cleanupDiscardJournalBatch(
            context = context,
            afterKey = null,
            batchLimit = 8,
            discardJournal = journal,
        )

        assertEquals(1, provider.deleteCalls)
        assertEquals(1, replay.report.errors)
        assertEquals(DiscardJournalLookup.PRESENT, journal.lookup(uri.toString()))
    }

    @Test
    fun `reassigned version changed unreadable and ambiguous identities retain markers without delete`() {
        val expected = testIdentity()
        val cases = listOf(
            PendingDiscardIdentityRead.Present(
                expected.copy(generationAdded = expected.generationAdded + 1),
            ) to DiscardReplayIdentity.MISMATCH,
            PendingDiscardIdentityRead.Present(
                expected.copy(providerVersion = "provider-v2"),
            ) to DiscardReplayIdentity.MISMATCH,
            PendingDiscardIdentityRead.Present(
                expected.copy(displayName = "IMG_TELECAM_F1_0000000000002_0000000002.jpg"),
            ) to DiscardReplayIdentity.MISMATCH,
            PendingDiscardIdentityRead.Absent(
                volumeName = expected.volumeName,
                providerVersion = "provider-v2",
            ) to DiscardReplayIdentity.MISMATCH,
            PendingDiscardIdentityRead.Unavailable to DiscardReplayIdentity.UNAVAILABLE,
            PendingDiscardIdentityRead.Ambiguous to DiscardReplayIdentity.AMBIGUOUS,
        )

        cases.forEachIndexed { index, (current, disposition) ->
            val suffix = "${UUID.randomUUID()}-$index"
            val authority = "discard-mismatch-${UUID.randomUUID()}"
            val uri = Uri.parse("content://$authority/images/1")
            val provider = ReplayProvider(allowDelete = true)
            ShadowContentResolver.registerProviderInternal(authority, provider)
            val reader = MutableIdentityReader(PendingDiscardIdentityRead.Present(expected))
            val journal = replayJournal(suffix, reader)
            assertTrue(journal.mark(uri.toString()))
            reader.current = current
            val record = journal.page(afterKey = null, batchLimit = 8).records.single()

            assertEquals(disposition, journal.replayIdentity(record))
            val replay = MediaStoreWriter.cleanupDiscardJournalBatch(
                context = context,
                afterKey = null,
                batchLimit = 8,
                discardJournal = journal,
            )

            assertEquals(0, provider.deleteCalls)
            assertEquals(1, replay.report.errors)
            assertEquals(DiscardJournalLookup.PRESENT, journal.lookup(uri.toString()))
        }
    }

    @Test
    fun `stable provider absence retires marker without issuing a delete`() {
        val suffix = UUID.randomUUID().toString()
        val authority = "discard-absent-$suffix"
        val uri = Uri.parse("content://$authority/images/1")
        val provider = ReplayProvider(allowDelete = true)
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val reader = MutableIdentityReader(PendingDiscardIdentityRead.Present(testIdentity()))
        val journal = replayJournal(suffix, reader)
        assertTrue(journal.mark(uri.toString()))
        reader.current = PendingDiscardIdentityRead.Absent(
            volumeName = "external_primary",
            providerVersion = "provider-v1",
        )

        val replay = MediaStoreWriter.cleanupDiscardJournalBatch(
            context = context,
            afterKey = null,
            batchLimit = 8,
            discardJournal = journal,
        )

        assertEquals(0, provider.deleteCalls)
        assertEquals(1, replay.report.deleted)
        assertEquals(DiscardJournalLookup.ABSENT, journal.lookup(uri.toString()))
    }

    @Test
    fun `legacy preference marker fails closed during replay and is not silently dropped`() {
        val suffix = UUID.randomUUID().toString()
        val authority = "discard-legacy-$suffix"
        val uri = Uri.parse("content://$authority/images/1")
        val provider = ReplayProvider(allowDelete = true)
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val preferences = context.getSharedPreferences("legacy-replay-$suffix", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString(uri.toString(), "discard").commit())
        val journal = PendingDiscardJournal(
            context = context,
            databaseName = "discard-legacy-$suffix.db",
            legacyPreferences = preferences,
            identityReader = testIdentityReader(),
        )

        val replay = MediaStoreWriter.cleanupDiscardJournalBatch(
            context = context,
            afterKey = null,
            batchLimit = 8,
            discardJournal = journal,
        )

        assertEquals(0, provider.deleteCalls)
        assertEquals(1, replay.report.errors)
        assertEquals(DiscardJournalLookup.PRESENT, journal.lookup(uri.toString()))
        assertEquals(DiscardReplayIdentity.LEGACY, journal.replayIdentity(
            journal.page(afterKey = null, batchLimit = 8).records.single(),
        ))
    }

    @Test
    fun `unsupported database upgrade fails closed`() {
        val suffix = UUID.randomUUID().toString()
        val databaseName = "discard-upgrade-$suffix.db"
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.version = 2
        }
        val journal = PendingDiscardJournal(
            context = context,
            databaseName = databaseName,
            databaseVersion = 3,
            legacyPreferences = context.getSharedPreferences(
                "legacy-upgrade-$suffix",
                Context.MODE_PRIVATE,
            ),
        )

        assertEquals(
            DiscardJournalLookup.UNAVAILABLE,
            journal.lookup("content://upgrade/marker"),
        )
    }

    @Test
    fun `unavailable sqlite discard authority blocks valid row adoption until retry exhausts`() {
        val suffix = UUID.randomUUID().toString()
        val authority = "discard-recovery-$suffix"
        val imageBase = Uri.parse("content://$authority/images")
        val videoBase = Uri.parse("content://$authority/videos")
        val rowUri = Uri.withAppendedPath(imageBase, "1")
        val jpeg = File.createTempFile("valid-pending-", ".jpg", context.cacheDir).apply {
            writeBytes(
                byteArrayOf(
                    0xff.toByte(),
                    0xd8.toByte(),
                    0x01,
                    0x02,
                    0xff.toByte(),
                    0xd9.toByte(),
                ),
            )
        }
        val provider = PendingJpegProvider(imageBase, jpeg)
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val targets = listOf(
            OrphanRecoveryTarget(imageBase, OrphanRecoveryCollection.IMAGES),
            OrphanRecoveryTarget(videoBase, OrphanRecoveryCollection.VIDEO),
        )
        val databaseName = "discard-recovery-$suffix.db"
        val legacyPreferences = context.getSharedPreferences(
            "legacy-recovery-$suffix",
            Context.MODE_PRIVATE,
        )
        val healthyJournal = PendingDiscardJournal(
            context = context,
            databaseName = databaseName,
            legacyPreferences = legacyPreferences,
            identityReader = testIdentityReader(),
        )
        assertTrue(healthyJournal.mark(rowUri.toString()))
        assertEquals(DiscardJournalLookup.PRESENT, healthyJournal.lookup(rowUri.toString()))

        // Prove the fixture itself takes the structural JPEG adoption path when its authority is
        // readable and absent; the faulted journal below is the only changed input.
        val controlBatch = MediaStoreWriter.cleanupOrphanedPendingBatch(
            context = context,
            cursor = OrphanRecoveryCursor(preflightComplete = true),
            discardJournal = PendingDiscardJournal(
                context = context,
                databaseName = "discard-control-$suffix.db",
                legacyPreferences = context.getSharedPreferences(
                    "legacy-control-$suffix",
                    Context.MODE_PRIVATE,
                ),
            ),
            targets = targets,
        )
        assertEquals(
            "${controlBatch.report}; open=${provider.openCalls}; update=${provider.updateCalls}",
            1,
            controlBatch.report.adopted,
        )
        assertEquals(1, provider.openCalls)
        assertEquals(1, provider.updateCalls)
        provider.resetCounters()

        val unavailableJournal = PendingDiscardJournal(
            context = context,
            databaseName = databaseName,
            databaseVersion = 3,
            legacyPreferences = legacyPreferences,
        )
        assertFalse(MediaStoreWriter.publish(context, rowUri, healthyJournal))
        assertFalse(MediaStoreWriter.publish(context, rowUri, unavailableJournal))
        assertEquals(0, provider.updateCalls)

        val completion = executeLaunchMediaRecovery(maxFailureAttempts = 2) { cursor ->
            MediaStoreWriter.cleanupOrphanedPendingBatch(
                context = context,
                cursor = cursor,
                discardJournal = unavailableJournal,
                targets = targets,
            )
        }

        assertEquals(0, provider.updateCalls)
        assertEquals(0, provider.openCalls)
        assertEquals(0, completion.report.adopted)
        assertEquals(2, completion.report.retained)
        assertEquals(setOf(RecoveryFailureClass.QUERY), completion.report.failureClasses)
        assertEquals(RecoveryRetryDecision.EXHAUSTED, completion.decision)
        assertEquals(
            DiscardJournalLookup.UNAVAILABLE,
            unavailableJournal.lookup(rowUri.toString()),
        )
    }

    private fun newJournal(): PendingDiscardJournal {
        val suffix = UUID.randomUUID().toString()
        return PendingDiscardJournal(
            context = context,
            databaseName = "discard-$suffix.db",
            legacyPreferences = context.getSharedPreferences("legacy-$suffix", Context.MODE_PRIVATE),
            identityReader = testIdentityReader(),
        )
    }

    private fun replayJournal(
        suffix: String,
        reader: PendingDiscardIdentityReader,
    ): PendingDiscardJournal = PendingDiscardJournal(
        context = context,
        databaseName = "discard-replay-$suffix.db",
        legacyPreferences = context.getSharedPreferences("legacy-replay-$suffix", Context.MODE_PRIVATE),
        identityReader = reader,
    )

    private fun testIdentityReader(
        read: PendingDiscardIdentityRead = PendingDiscardIdentityRead.Present(testIdentity()),
    ): PendingDiscardIdentityReader = PendingDiscardIdentityReader { read }

    private fun testIdentity(
        providerVersion: String = "provider-v1",
        generationAdded: Long = 11L,
        displayName: String = "IMG_TELECAM_F1_0000000000001_0000000001.jpg",
    ): PendingDiscardIdentity = PendingDiscardIdentity(
        volumeName = "external_primary",
        providerVersion = providerVersion,
        rowId = 1L,
        generationAdded = generationAdded,
        displayName = displayName,
        relativePath = "DCIM/TeleCamPro/",
        mimeType = "image/jpeg",
        ownerPackageName = context.packageName,
        familyIdentity = "STILL|1|1",
        dateTaken = 1L,
    )

    private class MutableIdentityReader(
        @Volatile var current: PendingDiscardIdentityRead,
    ) : PendingDiscardIdentityReader {
        override fun read(uri: String): PendingDiscardIdentityRead = current
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

    private class ReplayProvider(
        var allowDelete: Boolean = false,
    ) : ContentProvider() {
        var deleteCalls = 0
        var generationAdded = 11L
        var lastDeleteSelection: String? = null
        private var exists = true

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf(MediaStore.MediaColumns._ID)).apply {
            if (exists) {
                addRow(Array(columnCount) { index ->
                    if (getColumnName(index) == MediaStore.MediaColumns._ID) 1L else null
                })
            }
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleteCalls += 1
            lastDeleteSelection = selection
            if (!allowDelete || !exists) return 0
            if (selectionArgs == null || selectionArgs.getOrNull(1)?.toLongOrNull() != generationAdded) {
                return 0
            }
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

    private class BlockingUpdateProvider : ContentProvider() {
        val updateEntered = CountDownLatch(1)
        val allowUpdate = CountDownLatch(1)
        val updateCalls = AtomicInteger()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf("_id"))

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            updateCalls.incrementAndGet()
            updateEntered.countDown()
            check(allowUpdate.await(2, TimeUnit.SECONDS)) { "timed out waiting to release update" }
            return 1
        }
    }

    private class PendingJpegProvider(
        private val imageBase: Uri,
        private val jpeg: File,
    ) : ContentProvider() {
        var updateCalls = 0
        var openCalls = 0

        fun resetCounters() {
            updateCalls = 0
            openCalls = 0
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection ?: arrayOf(MediaStore.MediaColumns._ID)
            return MatrixCursor(columns).apply {
                if (uri == imageBase) {
                    addRow(Array<Any?>(columns.size) { index ->
                        when (columns[index]) {
                            MediaStore.MediaColumns._ID -> 1L
                            MediaStore.MediaColumns.IS_PENDING -> 1
                            MediaStore.MediaColumns.MIME_TYPE -> "image/jpeg"
                            MediaStore.MediaColumns.SIZE -> jpeg.length()
                            MediaStore.MediaColumns.DISPLAY_NAME -> "IMG_LEGACY_VALID.jpg"
                            else -> null
                        }
                    })
                }
            }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            openCalls += 1
            return ParcelFileDescriptor.open(jpeg, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            updateCalls += 1
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun getType(uri: Uri): String? = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    }
}
