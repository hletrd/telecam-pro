package me.hletrd.telecampro

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The memory-bank audio provenance as EXECUTED composition (AGG4-49), not a hand-replayed sequence
 * of the pure rules: which preference key is read, which value is written, and when the current
 * grant is re-evaluated (AGG4-9).
 */
@RunWith(RobolectricTestRunner::class)
class MemoryBankAudioProvenanceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = AudioDenialReasonStore(app)
    private var recordAudio = false
    private var granted = false
    private val restores = mutableListOf<Boolean>()
    private val provenance = MemoryBankAudioProvenance(
        reason = store,
        recordAudio = { recordAudio },
        hasMicrophonePermission = { granted },
        restoreAudio = { announce ->
            restores += announce
            recordAudio = true
        },
    )

    @Before fun resetReason() = store.write(false)

    @Test fun `the store owns the Activity's historical preference file and key`() {
        // Renaming either orphans every installed user's denial history, so pin both literals.
        app.getSharedPreferences("permission_state", Context.MODE_PRIVATE)
            .edit(commit = true) { putBoolean("audio_off_by_denial", true) }
        assertTrue(AudioDenialReasonStore(app).read())
        store.write(false)
        assertFalse(
            app.getSharedPreferences("permission_state", Context.MODE_PRIVATE)
                .getBoolean("audio_off_by_denial", true),
        )
    }

    @Test fun `a bank stored silent by denial records denial and operator silence records false`() {
        store.write(true)
        assertTrue(provenance.bankAudioOffByDenialNow())
        store.write(false)
        assertFalse(provenance.bankAudioOffByDenialNow())
        recordAudio = true
        store.write(true)
        assertFalse("a bank that wants audio is never denial-silent", provenance.bankAudioOffByDenialNow())
    }

    @Test fun `recalling a denial-silent bank while already granted restores audio at once`() {
        granted = true
        provenance.afterRecall(recallApplied = true, recalledRecordAudio = false, recalledOffByDenial = true)
        // Immediately, silently (the bank-loaded status owns the plate) — not at a later onResume.
        assertEquals(listOf(false), restores)
        assertTrue(recordAudio)
        assertFalse("the restore consumes the denial reason", store.read())
    }

    @Test fun `recalling a denial-silent bank while still denied keeps the reason for a later grant`() {
        provenance.afterRecall(recallApplied = true, recalledRecordAudio = false, recalledOffByDenial = true)
        assertEquals(emptyList<Boolean>(), restores)
        assertTrue(store.read())
        granted = true
        assertTrue(provenance.restoreIfGranted(announce = true))
        assertEquals("the resume reconciliation announces", listOf(true), restores)
        assertFalse(store.read())
    }

    @Test fun `recalling an operator-silenced bank clears the reason and restores nothing`() {
        store.write(true)
        granted = true
        provenance.afterRecall(recallApplied = true, recalledRecordAudio = false, recalledOffByDenial = false)
        assertFalse(store.read())
        assertEquals(emptyList<Boolean>(), restores)
        assertFalse(recordAudio)
    }

    @Test fun `a refused recall writes nothing and does not reconcile`() {
        store.write(true)
        granted = true
        provenance.afterRecall(recallApplied = false, recalledRecordAudio = false, recalledOffByDenial = null)
        assertTrue(store.read())
        assertEquals(emptyList<Boolean>(), restores)
    }

    @Test fun `audio already on is left alone by the reconciliation`() {
        store.write(true)
        granted = true
        recordAudio = true
        assertFalse(provenance.restoreIfGranted(announce = true))
        assertTrue(store.read())
        assertEquals(emptyList<Boolean>(), restores)
    }
}
