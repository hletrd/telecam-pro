package me.hletrd.telecampro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `recordAudio = false` means two different things, and only one of them is a preference.
 *
 * The bug this pins: a denial-disabled audio track was indistinguishable from operator-chosen
 * silence, so a later permission grant restored nothing — clips stayed silent, the level meter
 * stayed hidden (same flag gates it), and the shutter never re-prompted because
 * [microphonePermissionRequired] conditions its START_RECORDING prompt on that very flag.
 */
class MicrophoneGrantRestoreTest {

    @Test
    fun `a grant hands audio back when the denial is what disabled it`() {
        assertTrue(
            audioRestoredByMicrophoneGrant(
                audioDisabledByDenial = true,
                recordAudio = false,
                hasMicrophonePermission = true,
            ),
        )
    }

    @Test
    fun `operator-chosen silence survives a grant`() {
        // Someone who never wants audio must not have it switched back on by an unrelated grant.
        assertFalse(
            audioRestoredByMicrophoneGrant(
                audioDisabledByDenial = false,
                recordAudio = false,
                hasMicrophonePermission = true,
            ),
        )
    }

    @Test
    fun `a still-refused permission restores nothing`() {
        assertFalse(
            audioRestoredByMicrophoneGrant(
                audioDisabledByDenial = true,
                recordAudio = false,
                hasMicrophonePermission = false,
            ),
        )
    }

    @Test
    fun `audio already on is left alone`() {
        assertFalse(
            audioRestoredByMicrophoneGrant(
                audioDisabledByDenial = true,
                recordAudio = true,
                hasMicrophonePermission = true,
            ),
        )
    }

    @Test
    fun `the shutter cannot re-prompt once audio is off - which is why the restore is needed`() {
        // Not a hypothetical: this is the self-locking half of the defect. With audio off, a REC
        // press asks for nothing, so the ONLY in-app way back was the Audio toggle in Menu > Video.
        assertFalse(
            microphonePermissionRequired(
                action = PendingAudioAction.START_RECORDING,
                videoMode = true,
                recording = false,
                recordAudio = false,
            ),
        )
        // Whereas the toggle always asks — that is the door the restore now opens automatically.
        assertTrue(
            microphonePermissionRequired(
                action = PendingAudioAction.ENABLE_AUDIO,
                videoMode = true,
                recording = false,
                recordAudio = false,
            ),
        )
    }

    // AGG3-8 (CRIT3-3, REG3-4): an applied recall sets the reason from the bank's own provenance.
    @Test
    fun `recall sets the denial reason from the applied bank's provenance`() {
        // A bank that wants audio clears the reason whatever it recorded.
        assertEquals(false, audioDenialReasonAfterRecall(true, recalledRecordAudio = true, recalledOffByDenial = null))
        assertEquals(false, audioDenialReasonAfterRecall(true, recalledRecordAudio = true, recalledOffByDenial = true))
        // Silent by denial: set, so a later grant restores audio (AGG2-26).
        assertEquals(true, audioDenialReasonAfterRecall(true, recalledRecordAudio = false, recalledOffByDenial = true))
        // Silent by the operator: clear, so a later grant does not override them (AGG-37).
        assertEquals(false, audioDenialReasonAfterRecall(true, recalledRecordAudio = false, recalledOffByDenial = false))
        // A pre-provenance silent bank keeps the AGG2-26 rule: leave the reason alone.
        assertNull(audioDenialReasonAfterRecall(true, recalledRecordAudio = false, recalledOffByDenial = null))
        // Refused recall (REC active, empty slot, rejected optics) changes nothing.
        assertNull(audioDenialReasonAfterRecall(false, recalledRecordAudio = true, recalledOffByDenial = false))
        assertNull(audioDenialReasonAfterRecall(false, recalledRecordAudio = false, recalledOffByDenial = true))
    }

    @Test
    fun `a bank records denial provenance only when it is silent by denial`() {
        assertTrue(bankAudioOffByDenial(recordAudio = false, audioDenialReason = true))
        assertFalse(bankAudioOffByDenial(recordAudio = false, audioDenialReason = false))
        // Audio on: a stale reason must not be frozen into the bank.
        assertFalse(bankAudioOffByDenial(recordAudio = true, audioDenialReason = true))
    }
}
