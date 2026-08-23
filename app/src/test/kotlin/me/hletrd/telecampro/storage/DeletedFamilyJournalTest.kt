package me.hletrd.telecampro.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeletedFamilyJournalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

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
}
