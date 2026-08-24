package me.hletrd.telecampro.storage

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImmediateDiscardIdentityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val family = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1L, 1L)
    private val uri = Uri.parse("content://media/external_primary/images/media/1")

    @Test
    fun `uri reassignment before marker cannot bless replacement`() {
        val expected = identity()
        val reader = MutableReader(PendingDiscardIdentityRead.Present(expected))
        val journal = journal(reader)
        val allocation = requireNotNull(journal.captureAllocation(uri, family))
        reader.current = PendingDiscardIdentityRead.Present(
            expected.copy(generationAdded = expected.generationAdded + 1),
        )

        assertNull(journal.mark(allocation))
        assertEquals(DiscardJournalLookup.ABSENT, journal.lookup(uri.toString()))
    }

    @Test
    fun `committed marker returns full allocation identity and remap fails closed`() {
        val expected = identity(owner = null, dateTaken = null)
        val reader = MutableReader(PendingDiscardIdentityRead.Present(expected))
        val journal = journal(reader)
        val allocation = requireNotNull(journal.captureAllocation(uri, family))

        val committed = requireNotNull(journal.mark(allocation))
        assertEquals(expected, committed.identity)
        assertEquals(DiscardReplayIdentity.MATCH, journal.replayIdentity(committed))

        reader.current = PendingDiscardIdentityRead.Present(expected.copy(ownerPackageName = "replacement"))
        assertEquals(DiscardReplayIdentity.MISMATCH, journal.replayIdentity(committed))
        assertEquals(DiscardJournalLookup.PRESENT, journal.lookup(uri.toString()))
    }

    @Test
    fun `provider version change after marker never authorizes immediate identity`() {
        val expected = identity()
        val reader = MutableReader(PendingDiscardIdentityRead.Present(expected))
        val journal = journal(reader)
        val committed = requireNotNull(
            journal.mark(requireNotNull(journal.captureAllocation(uri, family))),
        )

        reader.current = PendingDiscardIdentityRead.Present(expected.copy(providerVersion = "v2"))
        assertEquals(DiscardReplayIdentity.MISMATCH, journal.replayIdentity(committed))
        assertEquals(DiscardJournalLookup.PRESENT, journal.lookup(uri.toString()))
    }

    @Test
    fun `conditional delete requires expected nullable columns to remain null`() {
        val (selection, args) = MediaStoreWriter.discardDeleteCondition(
            identity(owner = null, dateTaken = null),
        )

        assertTrue(selection.contains("${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} IS NULL"))
        assertTrue(selection.contains("${MediaStore.MediaColumns.DATE_TAKEN} IS NULL"))
        assertFalse(args.contains("null"))
        assertEquals(selection.count { it == '?' }, args.size)
    }

    @Test
    fun `legacy URI-only immediate discard is non destructive`() {
        val legacyUri = Uri.parse("content://legacy-unowned/${UUID.randomUUID()}")
        assertEquals(
            PendingOutputDiscardResult.UNRESOLVED,
            MediaStoreWriter.discardPendingOutput(context, legacyUri),
        )
        assertEquals(
            PendingOutputDiscardResult.UNRESOLVED,
            MediaStoreWriter.discardRejectedOutput(context, legacyUri),
        )
    }

    private fun journal(reader: PendingDiscardIdentityReader): PendingDiscardJournal {
        val suffix = UUID.randomUUID().toString()
        return PendingDiscardJournal(
            context = context,
            databaseName = "immediate-discard-$suffix.db",
            legacyPreferences = context.getSharedPreferences("immediate-discard-$suffix", 0),
            identityReader = reader,
        )
    }

    private fun identity(
        owner: String? = context.packageName,
        dateTaken: Long? = 1L,
    ) = PendingDiscardIdentity(
        volumeName = "external_primary",
        providerVersion = "v1",
        rowId = 1L,
        generationAdded = 7L,
        displayName = family.displayName("jpg"),
        relativePath = "DCIM/TeleCamPro/",
        mimeType = "image/jpeg",
        ownerPackageName = owner,
        familyIdentity = family.discardIdentity(),
        dateTaken = dateTaken,
    )

    private class MutableReader(
        @Volatile var current: PendingDiscardIdentityRead,
    ) : PendingDiscardIdentityReader {
        override fun read(uri: String): PendingDiscardIdentityRead = current
    }
}
