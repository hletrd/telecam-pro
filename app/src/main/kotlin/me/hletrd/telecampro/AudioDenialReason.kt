package me.hletrd.telecampro

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The persisted "audio is off BECAUSE a microphone request was refused" bit — the WHY behind
 * `recordAudio = false` that [audioRestoredByMicrophoneGrant] needs (operator-chosen silence is a
 * preference; denial-caused silence is a consequence a later grant must undo).
 *
 * ONE owner of the preference file and key, shared by the Activity's permission flow and the
 * ViewModel's memory-bank store (AGG4-49): when the key lived only in MainActivity, the ViewModel's
 * own `onStoreMemorySlot` could not read it and silently recorded "unknown" provenance, which made
 * recall fall back to the provenance-blind rule (AGG-37 reopened with every test green).
 */
internal class AudioDenialReasonStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PERMISSION_PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun read(): Boolean = preferences.getBoolean(AUDIO_OFF_BY_DENIAL_KEY, false)

    /** Synchronous commit, like every permission-history write: a swipe-kill must not lose it. */
    fun write(audioOffByDenial: Boolean) {
        preferences.edit(commit = true) { putBoolean(AUDIO_OFF_BY_DENIAL_KEY, audioOffByDenial) }
    }

    internal companion object {
        const val PERMISSION_PREFS_NAME = "permission_state"

        /** Whether audio is off BECAUSE a microphone request was refused, not by operator choice. */
        const val AUDIO_OFF_BY_DENIAL_KEY = "audio_off_by_denial"
    }
}

/**
 * The memory-bank half of the audio-denial provenance (AGG3-8) as one testable owner, so the
 * composition — which key is read, which value is written, and when a grant is re-evaluated — is
 * executed by host tests instead of living only inside the Activity (AGG4-49). The ViewModel owns the
 * one instance (AGG5-22): its own `onRecallMemorySlot` runs [afterRecall], so no `CameraActions`
 * binding can skip the recall leg, and the Activity's resume/grant reconciliation calls into it.
 *
 * [restoreAudio] receives `announce`: the resume/grant reconciliation announces the restore
 * ("Microphone allowed — audio on"), while a recall restores silently because the bank-loaded
 * status already owns the plate and the restored toggle is what the operator recalled — which is
 * why that write must not clear the recalled slot (AGG5-21).
 */
internal class MemoryBankAudioProvenance(
    private val reason: AudioDenialReasonStore,
    private val recordAudio: () -> Boolean,
    private val hasMicrophonePermission: () -> Boolean,
    private val restoreAudio: (announce: Boolean) -> Unit,
) {
    /** The provenance a bank stored NOW records ([bankAudioOffByDenial]); never "unknown". */
    fun bankAudioOffByDenialNow(): Boolean = bankAudioOffByDenial(recordAudio(), reason.read())

    /**
     * Applies a recall's answer to the denial reason, then reconciles it against the CURRENT grant
     * immediately (AGG4-9). Before this, recalling a denial-silent bank while the microphone was
     * already granted wrote `reason = true` and stopped: every clip stayed silent until some later,
     * unrelated `onResume` flipped audio on with a status nobody had triggered.
     */
    fun afterRecall(recallApplied: Boolean, recalledRecordAudio: Boolean, recalledOffByDenial: Boolean?) {
        audioDenialReasonAfterRecall(
            recallApplied = recallApplied,
            recalledRecordAudio = recalledRecordAudio,
            recalledOffByDenial = recalledOffByDenial,
        )?.let(reason::write)
        if (recallApplied) restoreIfGranted(announce = false)
    }

    /**
     * True when a grant handed denial-disabled audio back. Shared by the recall leg above and the
     * Activity's resume/grant reconciliation, so the two can never apply different rules.
     */
    fun restoreIfGranted(announce: Boolean): Boolean {
        if (!audioRestoredByMicrophoneGrant(
                audioDisabledByDenial = reason.read(),
                recordAudio = recordAudio(),
                hasMicrophonePermission = hasMicrophonePermission(),
            )
        ) {
            return false
        }
        reason.write(false)
        restoreAudio(announce)
        return true
    }
}
