package me.hletrd.telecampro.camera

import androidx.compose.runtime.Immutable

/** Stable message identity emitted by camera, capture, storage, recorder, and UI intent layers. */
enum class CameraStatusMessage {
    STARTING_CAMERA,
    PREVIEW_UNAVAILABLE_REOPEN,
    PREVIEW_INTERRUPTED_RECOVERING,
    CAMERA_ERROR_RECOVERING,
    CAMERA_UNAVAILABLE_REOPEN,
    PREVIEW_UNAVAILABLE_RETRYING,
    CAMERA_UNAVAILABLE_RETRYING,
    STOP_RECORDING_FIRST,
    STOP_RECORDING_MODE_UNCHANGED,
    STOP_RECORDING_RECALL_UNCHANGED,
    STOP_RECORDING_LENS_UNCHANGED,
    STOP_RECORDING_CAMERA_UNCHANGED,
    SWITCH_TO_REAR_FIRST,
    CAMERA_UNAVAILABLE_MODE_UNCHANGED,
    CAMERA_UNAVAILABLE_RECALL_UNCHANGED,
    CAMERA_UNAVAILABLE_FACING_UNCHANGED,
    CAMERA_UNAVAILABLE_CAMERA_UNCHANGED,
    PREVIEW_UNAVAILABLE_CAMERA_UNCHANGED,
    FRONT_CAMERA_UNAVAILABLE,
    TELE_LENS_UNAVAILABLE_UNCHANGED,
    LENS_UNAVAILABLE_UNCHANGED,
    SELECTED_RESOLUTION_UNAVAILABLE,
    SELECTED_FPS_UNAVAILABLE,
    SELECTED_CODEC_UNAVAILABLE,
    CAMERA_RECONFIGURING,
    STILL_CAPTURE_UNAVAILABLE,
    PROCESSED_STILL_UNAVAILABLE_DNG_ONLY,
    RAW_UNAVAILABLE,
    FINISHING_PREVIOUS_PHOTO,
    PHOTO_CAPTURE_FAILED,
    PHOTO_SAVE_FAILED,
    HEIF_SAVE_FAILED,
    JPEG_SAVE_FAILED,
    DNG_SAVE_FAILED,
    DNG_SAVE_DELAYED,
    DNG_CAPTURE_FAILED,
    OUTPUT_SAVED_PENDING,
    OUTPUT_SAVED_PENDING_RECOVERY,
    FINISHING_PREVIOUS_CLIP,
    RECORDING_ALREADY_ACTIVE,
    RECORDING_FAILED,
    MICROPHONE_BUSY,
    UNSAFE_RECORDER_RESTART,
    VIDEO_SAVED,
    VIDEO_SAVE_DELAYED,
    VIDEO_KEPT_UNVERIFIED,
    VIDEO_SAVE_FAILED,
    RECORDING_WITHOUT_AUDIO,
    MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO,
    MICROPHONE_DENIED_AUDIO_OFF,
    MICROPHONE_ALLOWED_AUDIO_ON,
    STANDBY_MICROPHONE_UNAVAILABLE,
    USE_AUTO_WB,
    CUSTOM_WB_MEASUREMENT_FAILED,
    CUSTOM_WB_SET,
    AUDIO_INPUT_USING_DEFAULT,
    MEMORY_SLOT_SAVED,
    MEMORY_SLOT_EMPTY,
    MEMORY_SLOT_LOADED,
    DELETED,
    DELETE_CANCELED,
    FILE_ALREADY_REMOVED,
    DELETE_AUTHORIZATION_UNAVAILABLE,
    SOME_FILES_NOT_DELETED_RETRY_GALLERY,
    COULD_NOT_DELETE_FILE,
}

enum class CameraStatusSeverity { INFO, SUCCESS, WARNING, ERROR }
enum class CameraStatusLivePriority { POLITE, ASSERTIVE }

/**
 * PROGRESS statuses describe a condition that is either true or false right now, so an EVENT ends
 * them — never a timer: [CameraStatusMessage.status] gives a PROGRESS status a null `durationMs`.
 * [CameraStatusMessage.STARTING_CAMERA] is the one the app emits at cold start: the owner reported
 * "starting the camera takes a long time" on a device whose session configures in ~950 ms, and the
 * cause was the duration classifier dropping the message into the 2.5 s neutral bucket. The pill
 * therefore sat for its full 2.5 s after the camera was already live, and the wait the user was
 * reading was the timer, not the camera. A timer is wrong in BOTH directions here: too long makes a
 * fast start look slow, and too short would clear the message while the camera is still coming up,
 * which claims ready before it is. Nothing bounds this one — while the camera has genuinely not
 * come up, "Starting camera…" is true, and every way that attempt can end (Ready, an error status,
 * the exhausted-retry terminal status) replaces it.
 */
enum class CameraStatusLifecycle { PROGRESS, EVENT }

/** A formatting argument with no presentation wording embedded in the domain event. */
sealed interface CameraStatusArgument {
    @Immutable data class Text(val value: String) : CameraStatusArgument
    @Immutable data class Number(val value: Long) : CameraStatusArgument
    @Immutable data class AudioInput(val value: AudioInputPreference) : CameraStatusArgument
    @Immutable data class Lens(val value: LensChoice) : CameraStatusArgument
}

/**
 * Fully typed transient presentation event. Metadata is fixed at creation rather than inferred by
 * searching translated copy, so changing a translation cannot alter urgency or dismissal.
 */
@Immutable
data class CameraStatus(
    val message: CameraStatusMessage,
    val arguments: List<CameraStatusArgument> = emptyList(),
    val severity: CameraStatusSeverity,
    val livePriority: CameraStatusLivePriority,
    val lifecycle: CameraStatusLifecycle,
    val durationMs: Long?,
)

/** Complete outputs kept private for the next process's recovery sweep (warning, not failure). */
internal val RETAINED_TAKE_MESSAGES: Set<CameraStatusMessage> = setOf(
    CameraStatusMessage.DNG_SAVE_DELAYED,
    CameraStatusMessage.OUTPUT_SAVED_PENDING,
    CameraStatusMessage.VIDEO_SAVE_DELAYED,
    CameraStatusMessage.VIDEO_KEPT_UNVERIFIED,
)

fun CameraStatusMessage.status(
    vararg arguments: CameraStatusArgument,
): CameraStatus {
    val severity = when (this) {
        CameraStatusMessage.STARTING_CAMERA,
        CameraStatusMessage.CAMERA_RECONFIGURING,
        CameraStatusMessage.PREVIEW_INTERRUPTED_RECOVERING,
        CameraStatusMessage.FINISHING_PREVIOUS_PHOTO,
        CameraStatusMessage.FINISHING_PREVIOUS_CLIP,
        CameraStatusMessage.RECORDING_WITHOUT_AUDIO,
        CameraStatusMessage.MICROPHONE_ALLOWED_AUDIO_ON,
        CameraStatusMessage.DELETE_CANCELED,
        CameraStatusMessage.FILE_ALREADY_REMOVED,
        -> CameraStatusSeverity.INFO

        CameraStatusMessage.VIDEO_SAVED,
        CameraStatusMessage.CUSTOM_WB_SET,
        CameraStatusMessage.MEMORY_SLOT_SAVED,
        CameraStatusMessage.MEMORY_SLOT_LOADED,
        CameraStatusMessage.DELETED,
        -> CameraStatusSeverity.SUCCESS

        CameraStatusMessage.STOP_RECORDING_FIRST,
        CameraStatusMessage.STOP_RECORDING_MODE_UNCHANGED,
        CameraStatusMessage.STOP_RECORDING_RECALL_UNCHANGED,
        CameraStatusMessage.STOP_RECORDING_LENS_UNCHANGED,
        CameraStatusMessage.STOP_RECORDING_CAMERA_UNCHANGED,
        CameraStatusMessage.SWITCH_TO_REAR_FIRST,
        CameraStatusMessage.PROCESSED_STILL_UNAVAILABLE_DNG_ONLY,
        // A WARNING, announced once per session shape at Ready (AGG5-10): the shot still saves its
        // HEIF/JPEG, so a red assertive 6 s ERROR on every press overstated it and, under the rank
        // arbiter, also swallowed every lower-rank refusal for those 6 s.
        CameraStatusMessage.RAW_UNAVAILABLE,
        CameraStatusMessage.OUTPUT_SAVED_PENDING,
        CameraStatusMessage.DNG_SAVE_DELAYED,
        CameraStatusMessage.VIDEO_SAVE_DELAYED,
        CameraStatusMessage.VIDEO_KEPT_UNVERIFIED,
        CameraStatusMessage.RECORDING_ALREADY_ACTIVE,
        CameraStatusMessage.MICROPHONE_BUSY,
        CameraStatusMessage.MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO,
        CameraStatusMessage.MICROPHONE_DENIED_AUDIO_OFF,
        CameraStatusMessage.USE_AUTO_WB,
        CameraStatusMessage.AUDIO_INPUT_USING_DEFAULT,
        CameraStatusMessage.MEMORY_SLOT_EMPTY,
        -> CameraStatusSeverity.WARNING

        CameraStatusMessage.PREVIEW_UNAVAILABLE_REOPEN,
        CameraStatusMessage.CAMERA_ERROR_RECOVERING,
        CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN,
        CameraStatusMessage.PREVIEW_UNAVAILABLE_RETRYING,
        CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING,
        CameraStatusMessage.CAMERA_UNAVAILABLE_MODE_UNCHANGED,
        CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED,
        CameraStatusMessage.CAMERA_UNAVAILABLE_FACING_UNCHANGED,
        CameraStatusMessage.CAMERA_UNAVAILABLE_CAMERA_UNCHANGED,
        CameraStatusMessage.PREVIEW_UNAVAILABLE_CAMERA_UNCHANGED,
        CameraStatusMessage.FRONT_CAMERA_UNAVAILABLE,
        CameraStatusMessage.TELE_LENS_UNAVAILABLE_UNCHANGED,
        CameraStatusMessage.LENS_UNAVAILABLE_UNCHANGED,
        CameraStatusMessage.SELECTED_RESOLUTION_UNAVAILABLE,
        CameraStatusMessage.SELECTED_FPS_UNAVAILABLE,
        CameraStatusMessage.SELECTED_CODEC_UNAVAILABLE,
        CameraStatusMessage.STILL_CAPTURE_UNAVAILABLE,
        CameraStatusMessage.PHOTO_CAPTURE_FAILED,
        CameraStatusMessage.PHOTO_SAVE_FAILED,
        CameraStatusMessage.HEIF_SAVE_FAILED,
        CameraStatusMessage.JPEG_SAVE_FAILED,
        CameraStatusMessage.DNG_SAVE_FAILED,
        CameraStatusMessage.DNG_CAPTURE_FAILED,
        CameraStatusMessage.OUTPUT_SAVED_PENDING_RECOVERY,
        CameraStatusMessage.RECORDING_FAILED,
        CameraStatusMessage.UNSAFE_RECORDER_RESTART,
        CameraStatusMessage.VIDEO_SAVE_FAILED,
        CameraStatusMessage.STANDBY_MICROPHONE_UNAVAILABLE,
        CameraStatusMessage.CUSTOM_WB_MEASUREMENT_FAILED,
        CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE,
        CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY,
        CameraStatusMessage.COULD_NOT_DELETE_FILE,
        -> CameraStatusSeverity.ERROR
    }
    val lifecycle = when (this) {
        CameraStatusMessage.STARTING_CAMERA,
        CameraStatusMessage.CAMERA_RECONFIGURING,
        CameraStatusMessage.PREVIEW_INTERRUPTED_RECOVERING,
        CameraStatusMessage.CAMERA_ERROR_RECOVERING,
        CameraStatusMessage.PREVIEW_UNAVAILABLE_RETRYING,
        CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING,
        -> CameraStatusLifecycle.PROGRESS

        else -> CameraStatusLifecycle.EVENT
    }
    val duration = when {
        lifecycle == CameraStatusLifecycle.PROGRESS -> null
        severity == CameraStatusSeverity.ERROR -> 6_000L
        // A retained take's copy is two sentences that tell the operator what to DO to get the file
        // (fully close and reopen the app). 2.5 s was too short to read it (DES2-5 / AGG3-5), and
        // missing it reads as a lost take — so it stays up as long as an error does.
        this in RETAINED_TAKE_MESSAGES -> 6_000L
        severity == CameraStatusSeverity.SUCCESS -> 1_500L
        else -> 2_500L
    }
    return CameraStatus(
        message = this,
        arguments = arguments.toList(),
        severity = severity,
        livePriority = if (severity == CameraStatusSeverity.ERROR) {
            CameraStatusLivePriority.ASSERTIVE
        } else {
            CameraStatusLivePriority.POLITE
        },
        lifecycle = lifecycle,
        durationMs = duration,
    )
}

/**
 * Terminal statuses of the camera-availability family (MRG4-1): each one is published exactly when a
 * PROGRESS condition ENDS without Ready — the exhausted retry (`*_REOPEN`) or a Not-Ready optics
 * rollback (`*_UNCHANGED`, [CameraStatusMessage.FRONT_CAMERA_UNAVAILABLE], the
 * `STOP_RECORDING_*_UNCHANGED` refusals that `rollbackOptics` carries). Deferring the condition
 * behind one of these was a resurrection: "Camera unavailable, retrying…" came back with no timer
 * when the 6 s REOPEN error expired, and no Ready would ever arrive to clear it because the retry
 * budget was already spent. So these END a condition instead of deferring it — but only the
 * condition they are the end OF ([endsCondition]). An ordinary event (an MR load, a saved clip)
 * still only interrupts the condition, which returns.
 */
internal val CAMERA_CONDITION_ENDING_MESSAGES: Set<CameraStatusMessage> = setOf(
    CameraStatusMessage.PREVIEW_UNAVAILABLE_REOPEN,
    CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN,
    CameraStatusMessage.STOP_RECORDING_MODE_UNCHANGED,
    CameraStatusMessage.STOP_RECORDING_RECALL_UNCHANGED,
    CameraStatusMessage.STOP_RECORDING_LENS_UNCHANGED,
    CameraStatusMessage.STOP_RECORDING_CAMERA_UNCHANGED,
    CameraStatusMessage.CAMERA_UNAVAILABLE_MODE_UNCHANGED,
    CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED,
    CameraStatusMessage.CAMERA_UNAVAILABLE_FACING_UNCHANGED,
    CameraStatusMessage.CAMERA_UNAVAILABLE_CAMERA_UNCHANGED,
    CameraStatusMessage.PREVIEW_UNAVAILABLE_CAMERA_UNCHANGED,
    CameraStatusMessage.FRONT_CAMERA_UNAVAILABLE,
    CameraStatusMessage.TELE_LENS_UNAVAILABLE_UNCHANGED,
    CameraStatusMessage.LENS_UNAVAILABLE_UNCHANGED,
)

/**
 * The exhausted-retry terminals: the camera or preview is GONE until the app is reopened, so they end
 * every condition, whichever family scheduled it.
 */
private val CAMERA_TERMINAL_MESSAGES: Set<CameraStatusMessage> = setOf(
    CameraStatusMessage.PREVIEW_UNAVAILABLE_REOPEN,
    CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN,
)

/**
 * The conditions an OPTICS transaction owns (AGG5-11 / RG5-7): cold start, a reopen, and the bounded
 * cold/bare preflight retry that transaction schedules. A Not-Ready rollback (`*_UNCHANGED`) is the
 * end of exactly these. It is NOT the end of a camera-health recovery (`CAMERA_ERROR_RECOVERING`) or
 * a preview-EGL retry (`PREVIEW_UNAVAILABLE_RETRYING` / `PREVIEW_INTERRUPTED_RECOVERING`) running
 * beside it: keyed by message alone, an unavailable-lens tap erased "Preview unavailable, retrying…"
 * and the plate went blank while that recovery was still in flight.
 */
internal val OPTICS_CONDITION_MESSAGES: Set<CameraStatusMessage> = setOf(
    CameraStatusMessage.STARTING_CAMERA,
    CameraStatusMessage.CAMERA_RECONFIGURING,
    CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING,
)

/** The reopen/recovery conditions the Output row calls "Camera reconfiguring…" (AGG5-55). */
internal val CAMERA_REOPEN_CONDITION_MESSAGES: Set<CameraStatusMessage> = setOf(
    CameraStatusMessage.CAMERA_RECONFIGURING,
    CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING,
    CameraStatusMessage.CAMERA_ERROR_RECOVERING,
    CameraStatusMessage.PREVIEW_UNAVAILABLE_RETRYING,
    CameraStatusMessage.PREVIEW_INTERRUPTED_RECOVERING,
)

/** Whether this event is the END of [condition] (shown or deferred), rather than an interruption. */
internal fun CameraStatus.endsCondition(condition: CameraStatus): Boolean = when (message) {
    in CAMERA_TERMINAL_MESSAGES -> true
    in CAMERA_CONDITION_ENDING_MESSAGES -> condition.message in OPTICS_CONDITION_MESSAGES
    else -> false
}

/**
 * RESPONSES (AGG5-11 / UX5-2): the synchronous answer to the operator's OWN input — a refusal, the
 * result of an MR store/recall, a delete outcome, the save of the clip they just stopped. AGG4-65
 * stopped ambient chatter from wiping a retained-take instruction or an error, but it had no notion of
 * who asked, so a refusal under a 6 s retained-take line was dropped and the tap looked inert (the
 * affordance failure DES4-4 rejected for the shutter), and "Video saved" vanished under a still-up
 * "Recording without audio". A response always takes the plate; a higher unexpired event it covers is
 * DEFERRED (not wiped) and returns for its remaining time when the response expires, so the AGG4-65
 * intent still holds.
 */
internal val RESPONSE_MESSAGES: Set<CameraStatusMessage> = setOf(
    CameraStatusMessage.STOP_RECORDING_FIRST,
    CameraStatusMessage.STOP_RECORDING_MODE_UNCHANGED,
    CameraStatusMessage.STOP_RECORDING_RECALL_UNCHANGED,
    CameraStatusMessage.STOP_RECORDING_LENS_UNCHANGED,
    CameraStatusMessage.STOP_RECORDING_CAMERA_UNCHANGED,
    CameraStatusMessage.SWITCH_TO_REAR_FIRST,
    CameraStatusMessage.RECORDING_ALREADY_ACTIVE,
    CameraStatusMessage.FINISHING_PREVIOUS_PHOTO,
    CameraStatusMessage.FINISHING_PREVIOUS_CLIP,
    CameraStatusMessage.MEMORY_SLOT_SAVED,
    CameraStatusMessage.MEMORY_SLOT_EMPTY,
    CameraStatusMessage.MEMORY_SLOT_LOADED,
    CameraStatusMessage.DELETED,
    CameraStatusMessage.DELETE_CANCELED,
    CameraStatusMessage.FILE_ALREADY_REMOVED,
    CameraStatusMessage.VIDEO_SAVED,
    CameraStatusMessage.CUSTOM_WB_SET,
    CameraStatusMessage.USE_AUTO_WB,
)

/**
 * RESOLUTIONS (RG5-6): an incoming status that settles the very thing a shown or deferred event
 * reported. The stale event is DROPPED, not deferred — "Could not delete file" must not return after
 * the retry's "Deleted", nor "Finishing previous clip" after that clip's "Video saved".
 */
private val RESOLVED_BY: Map<CameraStatusMessage, Set<CameraStatusMessage>> = mapOf(
    CameraStatusMessage.DELETED to setOf(
        CameraStatusMessage.COULD_NOT_DELETE_FILE,
        CameraStatusMessage.SOME_FILES_NOT_DELETED_RETRY_GALLERY,
        CameraStatusMessage.DELETE_AUTHORIZATION_UNAVAILABLE,
        CameraStatusMessage.DELETE_CANCELED,
    ),
    CameraStatusMessage.VIDEO_SAVED to setOf(
        CameraStatusMessage.FINISHING_PREVIOUS_CLIP,
        CameraStatusMessage.RECORDING_WITHOUT_AUDIO,
        CameraStatusMessage.MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO,
    ),
)

private fun CameraStatus.resolves(stale: CameraStatus): Boolean =
    RESOLVED_BY[message]?.contains(stale.message) == true

private val CameraStatus.isResponse: Boolean
    get() = lifecycle == CameraStatusLifecycle.EVENT && message in RESPONSE_MESSAGES

/**
 * Status-plate arbitration rank (AGG4-65), lowest first. The plate used to be last-writer-wins: a
 * 6 s retained-take instruction (the one line that tells the operator how to get a file back) or an
 * error was replaced within milliseconds by "MR1 loaded" or a refusal, and never came back.
 */
internal enum class StatusPlateRank { PROGRESS, SUCCESS, INFO, WARNING, RETAINED_TAKE, ERROR }

internal val CameraStatus.plateRank: StatusPlateRank
    get() = when {
        // An ERROR-severity condition (camera-error recovery, the bounded unavailable/preview
        // retries) is ASSERTIVE: ranked as PROGRESS it waited behind a 1.5 s "MR1 loaded" and its
        // TalkBack announcement was delayed or lost to a Ready (AGG5-11 / CT5-7). It ranks at ERROR
        // for arbitration only — it still has no timer and Ready still clears it.
        lifecycle == CameraStatusLifecycle.PROGRESS && severity == CameraStatusSeverity.ERROR ->
            StatusPlateRank.ERROR
        // A condition, not an event: it yields to every event and returns when that event expires.
        lifecycle == CameraStatusLifecycle.PROGRESS -> StatusPlateRank.PROGRESS
        severity == CameraStatusSeverity.ERROR -> StatusPlateRank.ERROR
        message in RETAINED_TAKE_MESSAGES -> StatusPlateRank.RETAINED_TAKE
        severity == CameraStatusSeverity.WARNING -> StatusPlateRank.WARNING
        severity == CameraStatusSeverity.INFO -> StatusPlateRank.INFO
        else -> StatusPlateRank.SUCCESS
    }

/** A higher event a response covered, with the display time it had left when it was covered. */
internal data class DeferredStatusEvent(val status: CameraStatus, val remainingMs: Long)

/**
 * The one status plate: what is [shown] (until [shownExpiresAtMs] on the caller's uptime clock; null
 * for a timer-less condition), a PROGRESS condition [deferredProgress] waiting behind an event, and a
 * higher event [deferredEvent] a response is covering. Pure; the ViewModel owns the timers and the
 * serialization.
 */
internal data class StatusPlate(
    val shown: CameraStatus?,
    val deferredProgress: CameraStatus? = null,
    val deferredEvent: DeferredStatusEvent? = null,
    val shownExpiresAtMs: Long? = null,
) {
    /**
     * [plate] after a publication, and whether [shown] changed (only then is a timer re-armed, for
     * [StatusPlate.shownExpiresAtMs]).
     */
    data class Publication(val plate: StatusPlate, val shownChanged: Boolean)

    /** The live condition, shown or waiting: what the Output row's "reconfiguring" keys on. */
    val condition: CameraStatus?
        get() = shown?.takeIf { it.lifecycle == CameraStatusLifecycle.PROGRESS } ?: deferredProgress

    /**
     * An unexpired EVENT is replaced only by an incoming status of EQUAL or HIGHER rank — measured
     * against a covered [deferredEvent] too — or by a RESPONSE, which covers it instead of wiping it
     * ([RESPONSE_MESSAGES]), or by its own RESOLUTION, which wipes it ([RESOLVED_BY]). A lower event is
     * dropped, and a lower PROGRESS waits behind it. An event that replaces a PROGRESS condition defers
     * it, so the condition reappears when the event expires — unless the event is that condition's
     * END ([endsCondition]), which drops it shown or deferred, even when the ending event is itself
     * outranked and dropped. Conditions replace each other. `null` is an explicit clear of everything.
     */
    fun publish(incoming: CameraStatus?, nowMs: Long): Publication {
        if (incoming == null) return Publication(StatusPlate(null), shownChanged = true)
        val current = shown
        val keptProgress = deferredProgress?.takeUnless { incoming.endsCondition(it) }
        val keptEvent = deferredEvent?.takeUnless { incoming.resolves(it.status) }
        fun show(progress: CameraStatus?, event: DeferredStatusEvent?) = Publication(
            StatusPlate(incoming, progress, event, incoming.durationMs?.let { nowMs + it }),
            shownChanged = true,
        )
        if (incoming.lifecycle == CameraStatusLifecycle.PROGRESS) {
            val guard = listOfNotNull(
                current?.takeIf { it.lifecycle == CameraStatusLifecycle.EVENT }?.plateRank,
                deferredEvent?.status?.plateRank,
            ).maxOrNull()
            return when {
                guard == null -> show(progress = null, event = null)
                incoming.plateRank >= guard -> show(progress = null, event = null)
                else -> Publication(copy(deferredProgress = incoming), shownChanged = false)
            }
        }
        if (current == null) return show(keptProgress, keptEvent)
        if (current.lifecycle == CameraStatusLifecycle.PROGRESS) {
            return if (incoming.isResponse || incoming.plateRank >= current.plateRank ||
                incoming.endsCondition(current)
            ) {
                show(current.takeUnless { incoming.endsCondition(it) }, keptEvent)
            } else {
                Publication(this, shownChanged = false)
            }
        }
        // An unexpired EVENT holds the plate.
        if (incoming.resolves(current)) return show(keptProgress, keptEvent)
        if (incoming.isResponse) {
            val covered = when {
                // The plate already shows a response: whatever IT covered is still waiting.
                current.isResponse -> keptEvent
                incoming.plateRank >= current.plateRank -> null
                else -> remainingOf(current, nowMs)?.let { DeferredStatusEvent(current, it) }
            }
            return show(keptProgress, covered)
        }
        val guard = maxOf(current.plateRank, deferredEvent?.status?.plateRank ?: current.plateRank)
        return when {
            incoming.plateRank >= guard -> show(keptProgress, event = null)
            else -> Publication(copy(deferredProgress = keptProgress, deferredEvent = keptEvent), false)
        }
    }

    private fun remainingOf(event: CameraStatus, nowMs: Long): Long? {
        val left = shownExpiresAtMs?.let { it - nowMs } ?: event.durationMs
        return left?.takeIf { it > 0L }
    }

    /**
     * The timer for [expired] fired: a covered event returns for its remaining time, else a deferred
     * condition takes the plate — but only if [expired] still has it.
     */
    fun expire(expired: CameraStatus, nowMs: Long): StatusPlate = when {
        shown != expired -> this
        deferredEvent != null -> StatusPlate(
            shown = deferredEvent.status,
            deferredProgress = deferredProgress,
            shownExpiresAtMs = nowMs + deferredEvent.remainingMs,
        )
        else -> StatusPlate(deferredProgress)
    }

    /** The progress condition ended (Ready, rollback, pause): drop it whether shown or deferred. */
    fun clearProgress(): StatusPlate =
        // A shown condition never covers a deferred event (only a response does), so clearing it
        // leaves nothing waiting.
        if (shown?.lifecycle == CameraStatusLifecycle.PROGRESS) {
            StatusPlate(null)
        } else {
            copy(deferredProgress = null)
        }
}
