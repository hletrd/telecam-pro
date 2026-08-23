package me.hletrd.telecampro.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.edit
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Thin wrapper around MediaStore for saving photos/videos into DCIM/<subDir> using the
 * pending-file convention (IS_PENDING) so files don't appear to other apps until fully written.
 */
object MediaStoreWriter {

    /**
     * Where new captures are written. Renamed from "X9Tele" once the app stopped being tied to one
     * handset — the old name told users which phone the developer owned, not which app made the
     * file. Safe as a hard cut because the app had never been published.
     */
    const val CAPTURE_SUBDIR = "TeleCamPro"

    /**
     * Directories reads cover. A single entry today; the list shape is kept because the restore
     * query filters on an EXACT RELATIVE_PATH, so any future rename must add the old name here or
     * it silently empties the in-app gallery for everyone who shot before it. (The "X9Tele" → this
     * rename needed no such entry: the app had never shipped.)
     */
    val CAPTURE_SUBDIRS = listOf(CAPTURE_SUBDIR)

    private const val MAX_RESTORE_ROWS_PER_COLLECTION = 64
    private const val MAX_FAMILY_ROWS = 8
    private const val PENDING_JOURNAL = "pending_media_journal"
    private const val PENDING_REGISTERED = "registered"
    private const val PENDING_COMPLETE = "complete"
    private const val PENDING_DISCARD = "discard"
    private const val DELETED_FAMILY_JOURNAL = "deleted_capture_family_journal"
    private const val DELETED_FAMILY_PREFIX = "F1|"
    private const val COMPLETION_MARK_ATTEMPTS = 3
    private const val COMPLETION_MARK_BACKOFF_MS = 25L
    private val processJournalOwner = UUID.randomUUID().toString()

    /**
     * Reconstructs the newest published capture THIS APP saved under its own folder.
     *
     * Images and Video are separate MediaStore collections, so each query is bounded and the pure
     * reducer compares their results. Versioned filenames prove sibling identity; legacy files stay
     * one-file delete scopes instead of being grouped by timestamp proximity.
     */
    internal fun latestOwnCapture(
        context: Context,
        subDirs: List<String> = CAPTURE_SUBDIRS,
    ): RestoredCapture<Uri>? {
        val imageRows = queryOwnedPublished(
            context = context,
            base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            collection = StoredMediaCollection.IMAGE,
            subDirs = subDirs,
        )
        val videoRows = queryOwnedPublished(
            context = context,
            base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            collection = StoredMediaCollection.VIDEO,
            subDirs = subDirs,
        )
        // Media providers can fault one collection independently. Preserve every successful result
        // so a transient Images failure cannot hide the latest video (or vice versa).
        val initial = restoreLatestCaptureFromQueryResults(imageRows, videoRows) ?: return null
        val familyKey = initial.familyKey ?: return initial

        // The broad queries find the winner. One exact, bounded follow-up prevents their row limits
        // from ever omitting an older sibling of that winning family from whole-capture deletion.
        val familyCollection = when (familyKey.media) {
            CaptureFamilyMedia.STILL -> StoredMediaCollection.IMAGE
            CaptureFamilyMedia.VIDEO -> StoredMediaCollection.VIDEO
        }
        val familyBase = when (familyCollection) {
            StoredMediaCollection.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            StoredMediaCollection.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return queryOwnedPublished(
            context = context,
            base = familyBase,
            collection = familyCollection,
            subDirs = subDirs,
            displayNames = familyKey.knownOutputDisplayNames(),
            limit = MAX_FAMILY_ROWS,
        ).fold(
            // An EMPTY exact result gets the same FILE_ONLY fallback as a failed query (CR4-11):
            // between the broad query that found the winner and this bounded follow-up, another
            // app can delete/re-pend those rows — dropping the whole restore over that TOCTOU
            // discarded a review file that still exists on disk. The two branches must agree.
            onSuccess = { exactRows ->
                restoreLatestCapture(exactRows) ?: RestoredCapture(
                    preferred = initial.preferred,
                    outputs = listOf(initial.preferred),
                    familyKey = null,
                    deleteScope = RestoredDeleteScope.FILE_ONLY,
                )
            },
            // A failed family expansion cannot safely promise capture-level deletion. Retain only
            // the already-resolved review file and make the fallback contract explicit.
            onFailure = {
                RestoredCapture(
                    preferred = initial.preferred,
                    outputs = listOf(initial.preferred),
                    familyKey = null,
                    deleteScope = RestoredDeleteScope.FILE_ONLY,
                )
            },
        )
    }

    private fun queryOwnedPublished(
        context: Context,
        base: Uri,
        collection: StoredMediaCollection,
        subDirs: List<String>,
        displayNames: List<String>? = null,
        limit: Int = MAX_RESTORE_ROWS_PER_COLLECTION,
    ): Result<List<StoredMediaRow<Uri>>> = runCatching {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        )
        val queryArgs = Bundle().apply {
            val ownerPolicy = restoreOwnerQueryPolicy(
                collection = collection,
                packageName = context.packageName,
                ownerColumn = MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                displayNameColumn = MediaStore.MediaColumns.DISPLAY_NAME,
                mimeTypeColumn = MediaStore.MediaColumns.MIME_TYPE,
            )
            val nameSelection = displayNames
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = " AND ${MediaStore.MediaColumns.DISPLAY_NAME} IN (", postfix = ")") { "?" }
                .orEmpty()
            // `OWNER_PACKAGE_NAME = ?` alone silently loses every capture this app made in a
            // PREVIOUS install: Android clears that column when the owning package is uninstalled,
            // and in SQL `NULL = 'anything'` is NULL — never true — so those rows can never match
            // any package name. Device evidence (2026-07-27): four rows in our own capture directory
            // with our own IMG_TELECAM_* filenames sat at owner NULL, invisible to this query,
            // which is why the gallery button fell back to its placeholder after a reinstall even
            // though the photos were right there. A NULL owner is therefore accepted only through
            // the explicit historical/current filename + collection + MIME rules. The reducer
            // repeats their exact grammar after this coarse provider filter. A row owned by a
            // DIFFERENT package is still excluded.
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN " +
                    subDirs.joinToString(", ", "(", ")") { "?" } + " AND " +
                    "${MediaStore.MediaColumns.IS_PENDING} = ? AND " +
                    ownerPolicy.selection + nameSelection,
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                buildList {
                    subDirs.forEach { add("DCIM/$it/") }
                    add("0")
                    addAll(ownerPolicy.selectionArgs)
                    displayNames?.let(::addAll)
                }.toTypedArray(),
            )
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_TAKEN} DESC, " +
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC, " +
                    "${MediaStore.MediaColumns._ID} DESC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        context.contentResolver.query(base, projection, queryArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val pendingColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
            val ownerColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val displayName = cursor.getString(nameColumn)
                    val family = CaptureFamilyKey.parse(displayName)?.familyKey
                    // A family delete is a durable review veto, including for a published row that
                    // survived its provider delete. Never let replacement-Engine restoration adopt
                    // it while the journal still owns that intent.
                    if (family != null && isFamilyDeleted(context, family)) continue
                    add(
                        StoredMediaRow(
                            output = ContentUris.withAppendedId(base, id),
                            collection = collection,
                            rowId = id,
                            displayName = displayName,
                            mimeType = cursor.getString(mimeColumn),
                            dateTakenEpochMillis = if (cursor.isNull(takenColumn)) null else cursor.getLong(takenColumn),
                            dateAddedEpochSeconds = cursor.getLong(addedColumn),
                            dateModifiedEpochSeconds = cursor.getLong(modifiedColumn),
                            isPending = cursor.getInt(pendingColumn) != 0,
                            // The selection admits our package OR a cleared owner matching the
                            // historical/current TeleCam contract. Cleared-owner rows remain
                            // display-only because deleting them requires a system consent flow.
                            isOwned = !cursor.isNull(ownerColumn),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    fun createPendingImage(
        context: Context,
        displayName: String,
        mimeType: String,
        subDir: String = CAPTURE_SUBDIR,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/$subDir")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            CaptureFamilyKey.parse(displayName)?.familyKey?.capturedAtEpochMillis?.let {
                put(MediaStore.Images.Media.DATE_TAKEN, it)
            }
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return registerPending(context, uri)
    }

    fun createPendingVideo(
        context: Context,
        displayName: String,
        mimeType: String,
        subDir: String = CAPTURE_SUBDIR,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/$subDir")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            CaptureFamilyKey.parse(displayName)?.familyKey?.capturedAtEpochMillis?.let {
                put(MediaStore.Video.Media.DATE_TAKEN, it)
            }
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return registerPending(context, uri)
    }

    /**
     * Durably records that all bytes/container metadata for [uri] were finalized. Call this after
     * the encoder/muxer has stopped and the output has been closed, but before [publish]. A launch
     * recovery can then distinguish a valuable complete take from an interrupted write without
     * guessing from file size alone.
     */
    internal fun markWriteComplete(context: Context, uri: Uri): CompletionMarkResult =
        markCompletionWithRetry(
            maxAttempts = COMPLETION_MARK_ATTEMPTS,
            commit = {
                context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
                    .edit()
                    .putString(uri.toString(), PENDING_COMPLETE)
                    .commit()
            },
            backoff = { attempt ->
                runCatching { Thread.sleep(COMPLETION_MARK_BACKOFF_MS * attempt) }
            },
        )

    /**
     * Durably vetoes recovery of a completed private row that belongs to a deleted capture.
     *
     * The immediate delete remains best-effort: a provider outage can reject it even though the
     * user's family-delete intent already won. Persisting DISCARD first makes that outage safe — a
     * later launch deletes the row regardless of its valid bytes or earlier COMPLETE marker instead
     * of adopting and publishing media the user deleted. A successful delete clears the journal in
     * the ordinary [delete] path.
     */
    internal fun discardPendingOutput(context: Context, uri: Uri): PendingOutputDiscardResult {
        val marker = markCompletionWithRetry(
            maxAttempts = COMPLETION_MARK_ATTEMPTS,
            commit = {
                context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
                    .edit()
                    .putString(uri.toString(), PENDING_DISCARD)
                    .commit()
            },
            backoff = { attempt ->
                runCatching { Thread.sleep(COMPLETION_MARK_BACKOFF_MS * attempt) }
            },
        )
        val deleted = delete(context, uri)
        // Keep the three outcomes distinct. In particular, callers must not erase UNRESOLVED and
        // claim terminal deletion: without either the row delete or a durable DISCARD marker, a
        // structurally valid pending row is still eligible for launch adoption.
        return when {
            deleted -> PendingOutputDiscardResult.DELETED
            marker.durable -> PendingOutputDiscardResult.RECOVERY_MARKED
            else -> PendingOutputDiscardResult.UNRESOLVED
        }
    }

    /**
     * Commits capture-family delete intent before the UI acknowledges deletion.
     *
     * The key is independent of any one MediaStore URI, so HEIF/JPEG/DNG rows created after the
     * delete (and published rows whose exact-URI discard failed) remain vetoed across Engine and
     * process replacement. The process token lets launch recovery retain current-process markers:
     * an old Engine may still produce a sibling after a replacement Engine's recovery pass.
     */
    internal fun markFamilyDeleted(context: Context, family: CaptureFamilyKey): Boolean =
        markCompletionWithRetry(
            maxAttempts = COMPLETION_MARK_ATTEMPTS,
            commit = {
                context.getSharedPreferences(DELETED_FAMILY_JOURNAL, Context.MODE_PRIVATE)
                    .edit()
                    .putString(deletedFamilyJournalKey(family), processJournalOwner)
                    .commit()
            },
            backoff = { attempt ->
                runCatching { Thread.sleep(COMPLETION_MARK_BACKOFF_MS * attempt) }
            },
        ).durable

    /** Fast shared-preference read used by still publication and launch restoration. */
    internal fun isFamilyDeleted(context: Context, family: CaptureFamilyKey): Boolean =
        context.getSharedPreferences(DELETED_FAMILY_JOURNAL, Context.MODE_PRIVATE)
            .contains(deletedFamilyJournalKey(family))

    fun openParcelFd(context: Context, uri: Uri, mode: String = "rw"): ParcelFileDescriptor? =
        runCatching { context.contentResolver.openFileDescriptor(uri, mode) }.getOrNull()

    fun openOutputStream(context: Context, uri: Uri): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()

    /**
     * Clears IS_PENDING so the file becomes visible to other apps (e.g. the gallery).
     *
     * Retries a transient resolver failure a few times with a short backoff (CRIT4-5): a complete
     * artifact must not be stranded pending — and later deleted by the next launch's
     * [cleanupOrphanedPending] sweep — over a one-off provider hiccup. Callers run on background
     * executors (ioExecutor / recorderExecutor), so the bounded sleep never blocks the UI. A
     * persistent failure still returns false; launch recovery returns an observable report and
     * retries complete or structurally proven rows instead of silently deleting them.
     */
    fun publish(context: Context, uri: Uri): Boolean {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        repeat(PUBLISH_ATTEMPTS) { attempt ->
            val published = runCatching { context.contentResolver.update(uri, values, null, null) > 0 }
                .getOrDefault(false)
            if (published) {
                clearPending(context, uri)
                return true
            }
            if (attempt < PUBLISH_ATTEMPTS - 1) {
                runCatching { Thread.sleep(PUBLISH_RETRY_BACKOFF_MS * (attempt + 1)) }
            }
        }
        return false
    }

    private const val PUBLISH_ATTEMPTS = 3
    private const val PUBLISH_RETRY_BACKOFF_MS = 50L

    /**
     * True when the requested media is gone after the operation. A resolver delete count of zero
     * is ambiguous (already absent vs. provider failure), so probe the exact URI before reporting a
     * failure. This keeps asynchronous family-delete reconciliation from restoring a stale URI as a
     * broken review thumbnail merely because another app removed it first.
     */
    fun delete(context: Context, uri: Uri): Boolean {
        val deleteCount = runCatching { context.contentResolver.delete(uri, null, null) }.getOrNull()
        val rowExistsAfter = if ((deleteCount ?: 0) > 0) {
            null
        } else {
            mediaRowExists(context, uri)
        }
        val disposition = mediaDeleteDisposition(deleteCount, rowExistsAfter)
        if (disposition != MediaDeleteDisposition.FAILED) clearPending(context, uri)
        return disposition != MediaDeleteDisposition.FAILED
    }

    /** Null means the provider could not answer; false is an authoritative already-absent row. */
    private fun mediaRowExists(context: Context, uri: Uri): Boolean? = runCatching {
        val queryArgs = Bundle().apply {
            // delete() is also used for not-yet-published cleanup; include an owned pending row so
            // an empty default query cannot be mistaken for authoritative absence.
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID),
            queryArgs,
            null,
        )?.use { cursor -> cursor.moveToFirst() }
    }.getOrNull()

    /**
     * Deletes still-PENDING rows belonging to [familyUri]'s capture family.
     *
     * A whole-family delete walks the TRACKER's known outputs — but an output whose MediaStore
     * publish failed (transient provider error after the bytes were written) stays IS_PENDING with a
     * COMPLETE journal entry and the tracker never learned it exists. Left behind, the next launch's
     * [cleanupOrphanedPending] would ADOPT and publish it, resurrecting part of a capture the user
     * already deleted (2026-07-30 review C3). So the family delete sweeps them here first.
     *
     * Scope is deliberately narrow: only the exact versioned-F1 display names of THIS family
     * ([CaptureFamilyKey.knownOutputDisplayNames]) and only rows still IS_PENDING=1 — published
     * siblings are the tracker's job, and a legacy (non-F1) [familyUri] parses to null so nothing is
     * ever proximity-swept. Best-effort; never throws.
     */
    fun deletePendingFamilySiblings(context: Context, familyUri: Uri): Int {
        val displayName = runCatching {
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(
                familyUri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                queryArgs,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        val family = CaptureFamilyKey.parse(displayName)?.familyKey ?: return 0
        val names = family.knownOutputDisplayNames()
        val base = when (family.media) {
            CaptureFamilyMedia.STILL -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            CaptureFamilyMedia.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        var swept = 0
        runCatching {
            val queryArgs = Bundle().apply {
                // Same OWNER + directory scoping as orphanSweepSelection: display names are exact,
                // but a foreign app can own an identically-named pending row elsewhere — its delete
                // would fail anyway (not ours to delete), yet scoping keeps this sweep's SELECTION
                // aligned with its recovery-side sibling instead of relying on delete() to refuse
                // (verification note, 2026-07-30).
                val paths = CAPTURE_SUBDIRS.joinToString(" OR ") {
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
                }
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "($paths) AND " + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + " = ? AND " +
                        MediaStore.MediaColumns.DISPLAY_NAME + " IN (" +
                        names.joinToString(",") { "?" } + ")",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    (CAPTURE_SUBDIRS.map { "DCIM/$it/%" } + context.packageName + names).toTypedArray(),
                )
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(
                base,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                queryArgs,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                while (cursor.moveToNext()) {
                    if (cursor.getInt(pendingCol) != 1) continue
                    val rowUri = ContentUris.withAppendedId(base, cursor.getLong(idCol))
                    // Never sweep the row the caller is about to delete itself — identical outcome,
                    // but keeping the contract "this function touches only rows the tracker does
                    // NOT know" makes the caller's survivor accounting exact.
                    if (rowUri == familyUri) continue
                    if (delete(context, rowUri)) swept++
                }
            }
        }
        return swept
    }

    /**
     * Recovers our own prior-process pending entries under DCIM/[subDir]. A complete take is adopted
     * by publishing it; only a proven incomplete artifact is deleted. Indeterminate rows remain
     * pending for a later launch rather than risking silent data loss. Best-effort; never throws.
     */
    internal fun cleanupOrphanedPending(
        context: Context,
        subDirs: List<String> = CAPTURE_SUBDIRS,
    ): RecoveryReport {
        // Only sweep entries created BEFORE this process: the launch-time sweep runs on the setup
        // executor while an immediate first capture creates its own pending entry on ioExecutor —
        // without the age gate the sweep could delete that in-flight write (two-executor race on
        // shared MediaStore state; an orphan from a prior crash is by definition older than us).
        val processStartSecs = processStartEpochSecs(
            nowMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime(),
            processStartElapsedRealtimeMillis = android.os.Process.getStartElapsedRealtime(),
        )
        // Selection/args construction is pure and PINNED BY TEST (OrphanSweepTest). Each collection
        // is independently caught and reported, so a broken Images query cannot suppress Video.
        val (selection, args) = orphanSweepSelection(subDirs, context.packageName, processStartSecs)
        // A deleted output can have crossed IS_PENDING=0 immediately before the Engine observed the
        // tombstone. Recover durable DISCARD entries by exact URI first, so published as well as
        // pending rows are deleted. Successful [delete] clears the journal; failures retain it for
        // the next bounded recovery attempt.
        var report = cleanupDeletedFamilyJournal(context).merge(cleanupDiscardJournal(context))
        for (base in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            val collectionResult = runCatching {
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    // Pending items are hidden from ordinary queries even for the owner; opt in.
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                val cursor = context.contentResolver.query(
                    base,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.IS_PENDING,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                    ),
                    queryArgs,
                    null,
                ) ?: error("MediaProvider returned no cursor")
                cursor.use {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        if (cursor.getInt(pendingCol) != 1) continue
                        report = report.record(RecoveryEvent.SCANNED)
                        val uri = ContentUris.withAppendedId(base, cursor.getLong(idCol))
                        val journalState = pendingJournalState(context, uri)
                        val familyDeleted = CaptureFamilyKey.parse(cursor.getString(nameCol))
                            ?.familyKey
                            ?.let { isFamilyDeleted(context, it) }
                            ?: false
                        val sizeBytes = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                        val probeOutcome = when {
                            sizeBytes <= 0L -> PendingProbeOutcome(PendingProbe.INVALID)
                            journalState == PendingJournalState.DISCARD ->
                                PendingProbeOutcome(PendingProbe.INVALID)
                            journalState == PendingJournalState.COMPLETE -> PendingProbeOutcome(PendingProbe.VALID)
                            else -> probePendingMedia(
                                context = context,
                                uri = uri,
                                mimeType = cursor.getString(mimeCol).orEmpty(),
                                isVideoCollection = base == MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                sizeBytes = sizeBytes,
                            )
                        }
                        if (probeOutcome.failed) {
                            report = report.record(RecoveryEvent.PROBE_FAILED)
                        }
                        when (orphanDisposition(journalState, probeOutcome.probe, familyDeleted)) {
                            OrphanDisposition.ADOPT -> {
                                report = report.record(
                                    if (publish(context, uri)) RecoveryEvent.ADOPTED
                                    else RecoveryEvent.PUBLISH_FAILED,
                                )
                            }
                            OrphanDisposition.DELETE -> {
                                report = report.record(
                                    if (delete(context, uri)) RecoveryEvent.DELETED
                                    else RecoveryEvent.DELETE_FAILED,
                                )
                            }
                            OrphanDisposition.KEEP_PENDING -> report = report.record(RecoveryEvent.RETAINED)
                        }
                    }
                }
            }
            if (collectionResult.isFailure) report = report.record(RecoveryEvent.QUERY_FAILED)
        }
        return report
    }

    /**
     * Reconciles family tombstones against both pending and published rows.
     *
     * A marker written by this process is deliberately never cleared here: a retired Engine may
     * still own an accepted save tail. A prior-process marker can be removed only after its exact,
     * bounded family query completed and every matching row was authoritatively deleted/absent.
     */
    private fun cleanupDeletedFamilyJournal(context: Context): RecoveryReport {
        val preferences = context.getSharedPreferences(DELETED_FAMILY_JOURNAL, Context.MODE_PRIVATE)
        val entries = runCatching { preferences.all.toMap() }.getOrElse {
            return RecoveryReport().record(RecoveryEvent.QUERY_FAILED)
        }
        var report = RecoveryReport()
        entries.forEach { (rawKey, rawOwner) ->
            val family = parseDeletedFamilyJournalKey(rawKey)
            if (family == null) {
                report = report.record(RecoveryEvent.QUERY_FAILED)
                return@forEach
            }
            val base = when (family.media) {
                CaptureFamilyMedia.STILL -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                CaptureFamilyMedia.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val rows = runCatching {
                val names = family.knownOutputDisplayNames()
                val paths = CAPTURE_SUBDIRS.joinToString(" OR ") {
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
                }
                val queryArgs = Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "($paths) AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                            "${MediaStore.MediaColumns.DISPLAY_NAME} IN (" +
                            names.joinToString(",") { "?" } + ")",
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        (CAPTURE_SUBDIRS.map { "DCIM/$it/%" } + context.packageName + names).toTypedArray(),
                    )
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                context.contentResolver.query(
                    base,
                    arrayOf(MediaStore.MediaColumns._ID),
                    queryArgs,
                    null,
                )?.use { cursor ->
                    buildList<Uri> {
                        while (cursor.moveToNext()) {
                            add(ContentUris.withAppendedId(base, cursor.getLong(0)))
                        }
                    }
                } ?: error("MediaProvider returned no cursor")
            }
            val ownedRows = rows.getOrElse {
                report = report.record(RecoveryEvent.QUERY_FAILED)
                return@forEach
            }
            var resolved = true
            ownedRows.forEach { uri ->
                report = report.record(RecoveryEvent.SCANNED)
                if (delete(context, uri)) {
                    report = report.record(RecoveryEvent.DELETED)
                } else {
                    resolved = false
                    report = report.record(RecoveryEvent.DELETE_FAILED)
                }
            }
            if (resolved && rawOwner != processJournalOwner) {
                preferences.edit().remove(rawKey).commit()
            }
        }
        return report
    }

    private fun cleanupDiscardJournal(context: Context): RecoveryReport {
        val entries = runCatching {
            context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE).all
                .filterValues { it == PENDING_DISCARD }
                .keys
        }.getOrElse {
            return RecoveryReport().record(RecoveryEvent.QUERY_FAILED)
        }
        var report = RecoveryReport()
        for (rawUri in entries) {
            report = report.record(RecoveryEvent.SCANNED)
            val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
            report = report.record(
                if (uri != null && delete(context, uri)) RecoveryEvent.DELETED
                else RecoveryEvent.DELETE_FAILED,
            )
        }
        return report
    }

    private fun registerPending(context: Context, uri: Uri): Uri? {
        val registered = context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
            .edit()
            .putString(uri.toString(), PENDING_REGISTERED)
            .commit()
        if (registered) return uri
        runCatching { context.contentResolver.delete(uri, null, null) }
        return null
    }

    private fun pendingJournalState(context: Context, uri: Uri): PendingJournalState =
        when (context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE).getString(uri.toString(), null)) {
            PENDING_COMPLETE -> PendingJournalState.COMPLETE
            PENDING_DISCARD -> PendingJournalState.DISCARD
            PENDING_REGISTERED -> PendingJournalState.REGISTERED
            else -> PendingJournalState.UNKNOWN
        }

    private fun clearPending(context: Context, uri: Uri) {
        context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
            .edit(commit = true) { remove(uri.toString()) }
    }

    private fun deletedFamilyJournalKey(family: CaptureFamilyKey): String =
        "$DELETED_FAMILY_PREFIX${family.media.name}|${family.capturedAtEpochMillis}|${family.sequence}"

    private fun parseDeletedFamilyJournalKey(raw: String): CaptureFamilyKey? {
        val fields = raw.split('|')
        if (fields.size != 4 || fields[0] != DELETED_FAMILY_PREFIX.removeSuffix("|")) return null
        val media = runCatching { CaptureFamilyMedia.valueOf(fields[1]) }.getOrNull() ?: return null
        val timestamp = fields[2].toLongOrNull() ?: return null
        val sequence = fields[3].toLongOrNull() ?: return null
        return runCatching { CaptureFamilyKey(media, timestamp, sequence) }.getOrNull()
    }

    private fun probePendingMedia(
        context: Context,
        uri: Uri,
        mimeType: String,
        isVideoCollection: Boolean,
        sizeBytes: Long,
    ): PendingProbeOutcome = pendingProbeOutcome {
        when (pendingMediaProbeKind(mimeType, isVideoCollection)) {
            PendingMediaProbeKind.VIDEO -> probeFinalizedVideo(context, uri)
            PendingMediaProbeKind.JPEG -> probeCompleteJpeg(context, uri, sizeBytes)
            PendingMediaProbeKind.DNG -> probeCompleteDng(context, uri, sizeBytes)
            PendingMediaProbeKind.HEIF -> probeCompleteHeif(context, uri)
            PendingMediaProbeKind.KEEP_PENDING -> PendingProbe.INDETERMINATE
        }
    }

    private fun probeFinalizedVideo(context: Context, uri: Uri): PendingProbe {
        val extractor = MediaExtractor()
        return try {
            val pfd = openReadableParcelFd(context, uri)
            pfd.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                if ((0 until extractor.trackCount).any { index ->
                        extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    }
                ) {
                    PendingProbe.VALID
                } else {
                    PendingProbe.INVALID
                }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Reopens a closed recording and requires an extractor-readable video track. */
    internal fun hasReadableVideoTrack(context: Context, uri: Uri): Boolean =
        runCatching { probeFinalizedVideo(context, uri) == PendingProbe.VALID }.getOrDefault(false)

    private fun probeCompleteHeif(context: Context, uri: Uri): PendingProbe {
        val pfd = openReadableParcelFd(context, uri)
        return pfd.use {
            FileInputStream(it.fileDescriptor).use { input ->
                val channel = input.channel
                probeHeifIsoBmff(channel.size()) { offset, byteCount ->
                    val buffer = ByteBuffer.allocate(byteCount)
                    channel.position(offset)
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) <= 0) return@probeHeifIsoBmff null
                    }
                    buffer.array()
                }
            }
        }
    }

    private fun probeCompleteJpeg(context: Context, uri: Uri, sizeBytes: Long): PendingProbe {
        if (sizeBytes < 4L) return PendingProbe.INVALID
        val pfd = openReadableParcelFd(context, uri)
        return pfd.use {
            FileInputStream(it.fileDescriptor).use { input ->
                val channel = input.channel
                if (channel.size() < 4L) return@use PendingProbe.INVALID
                channel.position(channel.size() - 2L)
                val tail = ByteArray(2)
                if (input.read(tail) == 2 && tail[0] == 0xff.toByte() && tail[1] == 0xd9.toByte()) {
                    PendingProbe.VALID
                } else {
                    PendingProbe.INVALID
                }
            }
        }
    }

    private fun probeCompleteDng(context: Context, uri: Uri, sizeBytes: Long): PendingProbe {
        val pfd = openReadableParcelFd(context, uri)
        return pfd.use {
            val exif = androidx.exifinterface.media.ExifInterface(it.fileDescriptor)
            val width = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH, 0)
            val height = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH, 0)
            val version = exif.getAttributeBytes(androidx.exifinterface.media.ExifInterface.TAG_DNG_VERSION)
            val offsets = parseUnsignedExifValues(
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_STRIP_OFFSETS),
            )
            val counts = parseUnsignedExifValues(
                exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_STRIP_BYTE_COUNTS),
            )
            val stripsFit = offsets.isNotEmpty() && offsets.size == counts.size &&
                offsets.zip(counts).all { (offset, count) -> count > 0L && offset <= sizeBytes - count }
            if (width > 0 && height > 0 && version != null && version.isNotEmpty() && stripsFit) {
                PendingProbe.VALID
            } else {
                PendingProbe.INVALID
            }
        }
    }

    /** A queried row that cannot be reopened is a provider/probe error, not quiet indeterminacy. */
    private fun openReadableParcelFd(context: Context, uri: Uri): ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("MediaProvider returned no file descriptor")

}

internal data class PendingProbeOutcome(
    val probe: PendingProbe,
    val failed: Boolean = false,
)

/** Converts access/parser exceptions into an explicit retained probe failure for launch recovery. */
internal fun pendingProbeOutcome(probe: () -> PendingProbe): PendingProbeOutcome {
    val result = runCatching(probe)
    return PendingProbeOutcome(
        probe = result.getOrDefault(PendingProbe.INDETERMINATE),
        failed = result.isFailure,
    )
}

internal data class CompletionMarkResult(
    val durable: Boolean,
    val attempts: Int,
)

/** Durable ownership result for media the user has already deleted as part of a capture family. */
internal enum class PendingOutputDiscardResult {
    /** The exact MediaStore row is gone and its journal entry was cleared. */
    DELETED,

    /** Immediate delete failed, but DISCARD is durable and launch recovery owns the retry. */
    RECOVERY_MARKED,

    /** Neither deletion nor durable recovery ownership succeeded; an in-process retry is required. */
    UNRESOLVED,
}

/**
 * Terminal provider disposition for bytes that have already been closed and structurally
 * completed. Both retained outcomes deliberately leave the MediaStore row private: publishing is
 * forbidden until COMPLETE is durable, while a provider publication outage must not destroy a
 * valuable take that launch recovery can adopt.
 */
internal enum class CompletedOutputPublication {
    PUBLISHED,
    RETAINED_MARKER_UNAVAILABLE,
    RETAINED_PUBLICATION_UNAVAILABLE,
}

/**
 * The single durable-before-publication gate shared by still and video callers.
 *
 * A failed marker commit is not a save failure and is not permission to delete structurally
 * complete bytes. It is a recoverable pending outcome, and [publish] is intentionally not invoked.
 */
internal fun publishCompletedOutput(
    markerDurable: Boolean,
    publish: () -> Boolean,
): CompletedOutputPublication {
    if (!markerDurable) return CompletedOutputPublication.RETAINED_MARKER_UNAVAILABLE
    return if (publish()) {
        CompletedOutputPublication.PUBLISHED
    } else {
        CompletedOutputPublication.RETAINED_PUBLICATION_UNAVAILABLE
    }
}

/** Bounded durable-marker policy with injected seams for commit-failure tests. */
internal fun markCompletionWithRetry(
    maxAttempts: Int,
    commit: () -> Boolean,
    backoff: (attempt: Int) -> Unit = {},
): CompletionMarkResult {
    require(maxAttempts > 0)
    repeat(maxAttempts) { zeroBasedAttempt ->
        val attempt = zeroBasedAttempt + 1
        if (runCatching(commit).getOrDefault(false)) {
            return CompletionMarkResult(durable = true, attempts = attempt)
        }
        if (attempt < maxAttempts) backoff(attempt)
    }
    return CompletionMarkResult(durable = false, attempts = maxAttempts)
}

internal enum class RecoveryFailureClass { QUERY, PROBE, PUBLISH, DELETE }

internal data class RecoveryReport(
    val scanned: Int = 0,
    val adopted: Int = 0,
    val deleted: Int = 0,
    val retained: Int = 0,
    val errors: Int = 0,
    val failureClasses: Set<RecoveryFailureClass> = emptySet(),
) {
    val retryRequired: Boolean
        get() = failureClasses.isNotEmpty()

    internal fun record(event: RecoveryEvent): RecoveryReport = when (event) {
        RecoveryEvent.SCANNED -> copy(scanned = scanned + 1)
        RecoveryEvent.ADOPTED -> copy(adopted = adopted + 1)
        RecoveryEvent.DELETED -> copy(deleted = deleted + 1)
        RecoveryEvent.RETAINED -> copy(retained = retained + 1)
        RecoveryEvent.QUERY_FAILED -> failed(RecoveryFailureClass.QUERY, retain = false)
        RecoveryEvent.PROBE_FAILED -> failed(RecoveryFailureClass.PROBE, retain = false)
        RecoveryEvent.PUBLISH_FAILED -> failed(RecoveryFailureClass.PUBLISH, retain = true)
        RecoveryEvent.DELETE_FAILED -> failed(RecoveryFailureClass.DELETE, retain = true)
    }

    private fun failed(failure: RecoveryFailureClass, retain: Boolean): RecoveryReport = copy(
        retained = retained + if (retain) 1 else 0,
        errors = errors + 1,
        failureClasses = failureClasses + failure,
    )

    /**
     * Adds durable transition counts while making the newest attempt the sole owner of unresolved
     * failures. A clean retry therefore completes even though the terminal summary retains the
     * earlier attempt's error count for truthful diagnostics.
     */
    internal fun foldRecoveryAttempt(attempt: RecoveryReport): RecoveryReport = RecoveryReport(
        scanned = scanned + attempt.scanned,
        adopted = adopted + attempt.adopted,
        deleted = deleted + attempt.deleted,
        retained = retained + attempt.retained,
        errors = errors + attempt.errors,
        failureClasses = attempt.failureClasses,
    )
}

private fun RecoveryReport.merge(other: RecoveryReport): RecoveryReport = RecoveryReport(
    scanned = scanned + other.scanned,
    adopted = adopted + other.adopted,
    deleted = deleted + other.deleted,
    retained = retained + other.retained,
    errors = errors + other.errors,
    failureClasses = failureClasses + other.failureClasses,
)

internal enum class RecoveryEvent {
    SCANNED,
    ADOPTED,
    DELETED,
    RETAINED,
    QUERY_FAILED,
    PROBE_FAILED,
    PUBLISH_FAILED,
    DELETE_FAILED,
}

internal enum class RecoveryRetryDecision { COMPLETE, RETRY, EXHAUSTED }

/** One-based bounded launch-recovery retry decision, kept pure for provider-failure matrices. */
internal fun recoveryRetryDecision(
    report: RecoveryReport,
    completedAttempts: Int,
    maxAttempts: Int,
): RecoveryRetryDecision = when {
    !report.retryRequired -> RecoveryRetryDecision.COMPLETE
    completedAttempts < maxAttempts.coerceAtLeast(1) -> RecoveryRetryDecision.RETRY
    else -> RecoveryRetryDecision.EXHAUSTED
}

internal enum class MediaDeleteDisposition { DELETED, ALREADY_ABSENT, FAILED }

/** Pure resolver-result reduction used by [MediaStoreWriter.delete]. */
internal fun mediaDeleteDisposition(
    deleteCount: Int?,
    rowExistsAfter: Boolean?,
): MediaDeleteDisposition = when {
    deleteCount != null && deleteCount > 0 -> MediaDeleteDisposition.DELETED
    rowExistsAfter == false -> MediaDeleteDisposition.ALREADY_ABSENT
    else -> MediaDeleteDisposition.FAILED
}

internal enum class PendingMediaProbeKind { VIDEO, JPEG, DNG, HEIF, KEEP_PENDING }

/**
 * Only containers with a conservative terminal-structure probe may bridge a missing COMPLETE
 * marker. HEIF requires a supported primary-item location whose explicit extents are wholly inside
 * bounded mdat payloads; header image dimensions or top-level box presence are never accepted.
 */
internal fun pendingMediaProbeKind(
    mimeType: String,
    isVideoCollection: Boolean,
): PendingMediaProbeKind = when {
    isVideoCollection -> PendingMediaProbeKind.VIDEO
    mimeType.equals("image/jpeg", ignoreCase = true) -> PendingMediaProbeKind.JPEG
    mimeType.contains("dng", ignoreCase = true) -> PendingMediaProbeKind.DNG
    mimeType.equals("image/heif", ignoreCase = true) ||
        mimeType.equals("image/heic", ignoreCase = true) -> PendingMediaProbeKind.HEIF
    else -> PendingMediaProbeKind.KEEP_PENDING
}

internal enum class PendingJournalState { UNKNOWN, REGISTERED, COMPLETE, DISCARD }

internal enum class PendingProbe { VALID, INVALID, INDETERMINATE }

internal enum class OrphanDisposition { ADOPT, DELETE, KEEP_PENDING }

/** Pure conservative launch-recovery decision; an unknown answer never destroys user media. */
internal fun orphanDisposition(
    journalState: PendingJournalState,
    probe: PendingProbe,
    familyDeleted: Boolean = false,
): OrphanDisposition = when {
    familyDeleted -> OrphanDisposition.DELETE
    journalState == PendingJournalState.DISCARD -> OrphanDisposition.DELETE
    journalState == PendingJournalState.COMPLETE -> OrphanDisposition.ADOPT
    probe == PendingProbe.VALID -> OrphanDisposition.ADOPT
    probe == PendingProbe.INVALID -> OrphanDisposition.DELETE
    else -> OrphanDisposition.KEEP_PENDING
}

/**
 * Structural HEIF completion probe. Reads bounded ISO-BMFF metadata only, never pixel data. A valid
 * result requires a HEIF brand, one supported primary item, and every explicit primary-item extent
 * to resolve wholly inside an mdat payload. Malformed or out-of-range structures are INVALID;
 * unreadable bytes, unbounded boxes, and unsupported meta/pitm/iloc variants are INDETERMINATE so
 * recovery retains the private row instead of risking deletion.
 */
internal fun probeHeifIsoBmff(
    fileSize: Long,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): PendingProbe {
    if (fileSize < 24L) return PendingProbe.INVALID
    var offset = 0L
    var boxCount = 0
    var foundFtyp = false
    var metaBox: HeifIsoBox? = null
    val mdatPayloads = mutableListOf<HeifByteRange>()

    while (offset < fileSize) {
        if (++boxCount > MAX_HEIF_BOXES) return PendingProbe.INDETERMINATE
        val box = when (val parsed = readHeifIsoBox(offset, fileSize, readAt)) {
            is HeifParse.Success -> parsed.value
            is HeifParse.Failure -> return parsed.probe
        }
        when (box.type) {
            "ftyp" -> {
                when (val brands = hasHeifBrand(box, readAt)) {
                    is HeifParse.Success -> foundFtyp = foundFtyp || brands.value
                    is HeifParse.Failure -> return brands.probe
                }
            }
            "meta" -> {
                if (metaBox != null) return PendingProbe.INVALID
                metaBox = box
            }
            "mdat" -> {
                if (box.payloadSize <= 0L) return PendingProbe.INVALID
                mdatPayloads += HeifByteRange(box.payloadOffset, box.payloadSize)
            }
        }
        offset += box.size
    }

    if (offset != fileSize || !foundFtyp || metaBox == null || mdatPayloads.isEmpty()) {
        return PendingProbe.INVALID
    }
    val primaryExtents = when (val parsed = parseHeifMeta(metaBox, readAt)) {
        is HeifParse.Success -> parsed.value
        is HeifParse.Failure -> return parsed.probe
    }
    return if (primaryExtents.all { extent ->
            extent.length > 0L &&
                extent.offset <= fileSize - extent.length &&
                mdatPayloads.any { payload -> extent.isWhollyInside(payload) }
        }
    ) PendingProbe.VALID else PendingProbe.INVALID
}

private const val MAX_HEIF_BOXES = 4_096
private const val MAX_HEIF_ITEMS = 4_096
private const val MAX_HEIF_EXTENTS = 4_096
private const val MAX_HEIF_FTYP_BYTES = 4_096

private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")

private sealed interface HeifParse<out T> {
    data class Success<T>(val value: T) : HeifParse<T>
    data class Failure(val probe: PendingProbe) : HeifParse<Nothing>
}

private data class HeifIsoBox(
    val type: String,
    val offset: Long,
    val size: Long,
    val headerSize: Long,
) {
    val payloadOffset: Long get() = offset + headerSize
    val payloadSize: Long get() = size - headerSize
    val endOffset: Long get() = offset + size
}

private data class HeifByteRange(val offset: Long, val length: Long) {
    fun isWhollyInside(container: HeifByteRange): Boolean {
        if (offset < container.offset) return false
        val relativeOffset = offset - container.offset
        return relativeOffset <= container.length && length <= container.length - relativeOffset
    }
}

private fun readHeifIsoBox(
    offset: Long,
    parentEnd: Long,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<HeifIsoBox> {
    if (offset < 0L || parentEnd < offset || parentEnd - offset < 8L) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }
    val header = readAt(offset, 8)
        ?: return HeifParse.Failure(PendingProbe.INDETERMINATE)
    if (header.size != 8) return HeifParse.Failure(PendingProbe.INDETERMINATE)
    val size32 = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL
    val type = String(header, 4, 4, Charsets.US_ASCII)
    val headerSize: Long
    val boxSize: Long
    when (size32) {
        // Although ISO-BMFF defines zero as "through parent/EOF", a crash-truncated final box has
        // the same shape. The recovery probe deliberately declines to decide.
        0L -> return HeifParse.Failure(PendingProbe.INDETERMINATE)
        1L -> {
            if (parentEnd - offset < 16L) return HeifParse.Failure(PendingProbe.INVALID)
            val extended = readAt(offset + 8L, 8)
                ?: return HeifParse.Failure(PendingProbe.INDETERMINATE)
            if (extended.size != 8) return HeifParse.Failure(PendingProbe.INDETERMINATE)
            boxSize = ByteBuffer.wrap(extended).order(ByteOrder.BIG_ENDIAN).long
            if (boxSize < 0L) return HeifParse.Failure(PendingProbe.INVALID)
            headerSize = 16L
        }
        else -> {
            boxSize = size32
            headerSize = 8L
        }
    }
    if (boxSize < headerSize || boxSize > parentEnd - offset) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }
    return HeifParse.Success(HeifIsoBox(type, offset, boxSize, headerSize))
}

private fun hasHeifBrand(
    box: HeifIsoBox,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<Boolean> {
    if (box.payloadSize < 8L || (box.payloadSize - 8L) % 4L != 0L) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }
    if (box.payloadSize > MAX_HEIF_FTYP_BYTES) {
        return HeifParse.Failure(PendingProbe.INDETERMINATE)
    }
    val bytes = readAt(box.payloadOffset, box.payloadSize.toInt())
        ?: return HeifParse.Failure(PendingProbe.INDETERMINATE)
    if (bytes.size != box.payloadSize.toInt()) {
        return HeifParse.Failure(PendingProbe.INDETERMINATE)
    }
    val brands = buildList {
        add(String(bytes, 0, 4, Charsets.US_ASCII))
        var offset = 8
        while (offset + 4 <= bytes.size) {
            add(String(bytes, offset, 4, Charsets.US_ASCII))
            offset += 4
        }
    }
    return HeifParse.Success(brands.any(HEIF_BRANDS::contains))
}

private fun parseHeifMeta(
    meta: HeifIsoBox,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<List<HeifByteRange>> {
    if (meta.payloadSize < 4L) return HeifParse.Failure(PendingProbe.INVALID)
    val fullBox = when (val parsed = readHeifUnsigned(meta.payloadOffset, 4, meta.endOffset, readAt)) {
        is HeifParse.Success -> parsed.value
        is HeifParse.Failure -> return parsed
    }
    val version = (fullBox ushr 24).toInt()
    val flags = fullBox and 0x00ff_ffffL
    if (version != 0) return HeifParse.Failure(PendingProbe.INDETERMINATE)
    if (flags != 0L) return HeifParse.Failure(PendingProbe.INVALID)

    var childOffset = meta.payloadOffset + 4L
    var childCount = 0
    var pitm: HeifIsoBox? = null
    var iloc: HeifIsoBox? = null
    while (childOffset < meta.endOffset) {
        if (++childCount > MAX_HEIF_BOXES) return HeifParse.Failure(PendingProbe.INDETERMINATE)
        val child = when (val parsed = readHeifIsoBox(childOffset, meta.endOffset, readAt)) {
            is HeifParse.Success -> parsed.value
            is HeifParse.Failure -> return parsed
        }
        when (child.type) {
            "pitm" -> {
                if (pitm != null) return HeifParse.Failure(PendingProbe.INVALID)
                pitm = child
            }
            "iloc" -> {
                if (iloc != null) return HeifParse.Failure(PendingProbe.INVALID)
                iloc = child
            }
        }
        childOffset += child.size
    }
    if (childOffset != meta.endOffset || pitm == null || iloc == null) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }
    val primaryItemId = when (val parsed = parsePrimaryItemId(pitm, readAt)) {
        is HeifParse.Success -> parsed.value
        is HeifParse.Failure -> return parsed
    }
    return parsePrimaryItemExtents(iloc, primaryItemId, readAt)
}

private fun parsePrimaryItemId(
    pitm: HeifIsoBox,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<Long> {
    if (pitm.payloadSize < 4L) return HeifParse.Failure(PendingProbe.INVALID)
    val fullBox = when (val parsed = readHeifUnsigned(pitm.payloadOffset, 4, pitm.endOffset, readAt)) {
        is HeifParse.Success -> parsed.value
        is HeifParse.Failure -> return parsed
    }
    val version = (fullBox ushr 24).toInt()
    val flags = fullBox and 0x00ff_ffffL
    if (version !in 0..1) return HeifParse.Failure(PendingProbe.INDETERMINATE)
    if (flags != 0L) return HeifParse.Failure(PendingProbe.INVALID)
    val itemIdSize = if (version == 0) 2 else 4
    if (pitm.payloadSize != 4L + itemIdSize) return HeifParse.Failure(PendingProbe.INVALID)
    return when (val parsed = readHeifUnsigned(pitm.payloadOffset + 4L, itemIdSize, pitm.endOffset, readAt)) {
        is HeifParse.Success -> if (parsed.value != 0L) parsed else HeifParse.Failure(PendingProbe.INVALID)
        is HeifParse.Failure -> parsed
    }
}

private fun parsePrimaryItemExtents(
    iloc: HeifIsoBox,
    primaryItemId: Long,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<List<HeifByteRange>> {
    val reader = HeifBoundedReader(iloc.payloadOffset, iloc.endOffset, readAt)
    val fullBox = reader.readUnsigned(4) ?: return HeifParse.Failure(reader.failure!!)
    val version = (fullBox ushr 24).toInt()
    val flags = fullBox and 0x00ff_ffffL
    if (version !in 0..2) return HeifParse.Failure(PendingProbe.INDETERMINATE)
    if (flags != 0L) return HeifParse.Failure(PendingProbe.INVALID)

    val sizePair = reader.readUnsigned(1)?.toInt()
        ?: return HeifParse.Failure(reader.failure!!)
    val baseAndIndexPair = reader.readUnsigned(1)?.toInt()
        ?: return HeifParse.Failure(reader.failure!!)
    val offsetSize = sizePair ushr 4
    val lengthSize = sizePair and 0x0f
    val baseOffsetSize = baseAndIndexPair ushr 4
    val indexSize = if (version == 0) 0 else baseAndIndexPair and 0x0f
    if (version == 0 && baseAndIndexPair and 0x0f != 0) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }
    val legalFieldSizes = setOf(0, 4, 8)
    if (offsetSize !in legalFieldSizes || lengthSize !in legalFieldSizes ||
        baseOffsetSize !in legalFieldSizes || indexSize !in legalFieldSizes
    ) {
        return HeifParse.Failure(PendingProbe.INVALID)
    }

    val itemCount = reader.readUnsigned(if (version < 2) 2 else 4)
        ?: return HeifParse.Failure(reader.failure!!)
    if (itemCount > MAX_HEIF_ITEMS) return HeifParse.Failure(PendingProbe.INDETERMINATE)

    var primaryFound = false
    var totalExtents = 0L
    val primaryExtents = mutableListOf<HeifByteRange>()
    repeat(itemCount.toInt()) {
        val itemId = reader.readUnsigned(if (version < 2) 2 else 4)
            ?: return HeifParse.Failure(reader.failure!!)
        val constructionMethod = if (version == 0) {
            0
        } else {
            val construction = reader.readUnsigned(2)?.toInt()
                ?: return HeifParse.Failure(reader.failure!!)
            if (construction and 0xfff0 != 0) return HeifParse.Failure(PendingProbe.INVALID)
            construction and 0x0f
        }
        val dataReferenceIndex = reader.readUnsigned(2)
            ?: return HeifParse.Failure(reader.failure!!)
        val isPrimary = itemId == primaryItemId
        if (isPrimary && primaryFound) return HeifParse.Failure(PendingProbe.INVALID)
        if (isPrimary && constructionMethod != 0) {
            return HeifParse.Failure(PendingProbe.INDETERMINATE)
        }
        if (isPrimary && dataReferenceIndex != 0L) {
            return HeifParse.Failure(PendingProbe.INDETERMINATE)
        }
        val baseOffset = if (isPrimary) {
            reader.readUnsigned(baseOffsetSize) ?: return HeifParse.Failure(reader.failure!!)
        } else {
            if (!reader.skip(baseOffsetSize)) return HeifParse.Failure(reader.failure!!)
            0L
        }
        val extentCount = reader.readUnsigned(2)
            ?: return HeifParse.Failure(reader.failure!!)
        totalExtents += extentCount
        if (totalExtents > MAX_HEIF_EXTENTS) return HeifParse.Failure(PendingProbe.INDETERMINATE)
        if (extentCount == 0L) return HeifParse.Failure(PendingProbe.INVALID)
        if (isPrimary && lengthSize == 0) return HeifParse.Failure(PendingProbe.INDETERMINATE)

        repeat(extentCount.toInt()) {
            if (!reader.skip(indexSize)) return HeifParse.Failure(reader.failure!!)
            if (isPrimary) {
                val extentOffset = reader.readUnsigned(offsetSize)
                    ?: return HeifParse.Failure(reader.failure!!)
                val extentLength = reader.readUnsigned(lengthSize)
                    ?: return HeifParse.Failure(reader.failure!!)
                // A zero length means "the whole source" in ISO-BMFF. It is valid syntax, but not
                // an explicit crash-safe bound, so keep the row pending rather than adopting it.
                if (extentLength == 0L) return HeifParse.Failure(PendingProbe.INDETERMINATE)
                if (baseOffset > Long.MAX_VALUE - extentOffset) {
                    return HeifParse.Failure(PendingProbe.INVALID)
                }
                primaryExtents += HeifByteRange(baseOffset + extentOffset, extentLength)
            } else {
                if (!reader.skip(offsetSize + lengthSize)) return HeifParse.Failure(reader.failure!!)
            }
        }
        if (isPrimary) primaryFound = true
    }
    if (reader.cursor != iloc.endOffset) return HeifParse.Failure(PendingProbe.INVALID)
    return if (primaryFound && primaryExtents.isNotEmpty()) {
        HeifParse.Success(primaryExtents)
    } else {
        HeifParse.Failure(PendingProbe.INVALID)
    }
}

private class HeifBoundedReader(
    start: Long,
    private val end: Long,
    private val readAt: (offset: Long, byteCount: Int) -> ByteArray?,
) {
    var cursor: Long = start
        private set
    var failure: PendingProbe? = null
        private set

    fun readUnsigned(byteCount: Int): Long? {
        if (failure != null) return null
        if (byteCount !in 0..8) {
            failure = PendingProbe.INDETERMINATE
            return null
        }
        if (byteCount == 0) return 0L
        if (cursor < 0L || end < cursor || byteCount.toLong() > end - cursor) {
            failure = PendingProbe.INVALID
            return null
        }
        val bytes = readAt(cursor, byteCount)
        if (bytes == null || bytes.size != byteCount) {
            failure = PendingProbe.INDETERMINATE
            return null
        }
        var value = 0L
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xff
            if (value > (Long.MAX_VALUE - unsigned) / 256L) {
                failure = PendingProbe.INVALID
                return null
            }
            value = value * 256L + unsigned
        }
        cursor += byteCount
        return value
    }

    fun skip(byteCount: Int): Boolean {
        if (failure != null) return false
        if (byteCount < 0 || cursor < 0L || end < cursor || byteCount.toLong() > end - cursor) {
            failure = PendingProbe.INVALID
            return false
        }
        cursor += byteCount
        return true
    }
}

private fun readHeifUnsigned(
    offset: Long,
    byteCount: Int,
    end: Long,
    readAt: (offset: Long, byteCount: Int) -> ByteArray?,
): HeifParse<Long> {
    val reader = HeifBoundedReader(offset, end, readAt)
    val value = reader.readUnsigned(byteCount)
    return if (value != null) HeifParse.Success(value) else HeifParse.Failure(reader.failure!!)
}

internal fun parseUnsignedExifValues(raw: String?): List<Long> = raw
    ?.split(',')
    ?.mapNotNull { token -> token.trim().substringBefore('/').toLongOrNull()?.takeIf { it >= 0L } }
    .orEmpty()

/**
 * Epoch-seconds moment this process started: wall-clock "now" rolled back by the time elapsed
 * since boot, plus the process-start elapsed-realtime stamp. Mixes the two clocks deliberately —
 * DATE_ADDED is epoch seconds, but "before this process" is an elapsed-realtime fact. Integer
 * division truncates up to ~1 s conservative (fewer deletions), which is the safe direction for
 * a delete sweep.
 */
internal fun processStartEpochSecs(
    nowMillis: Long,
    elapsedRealtimeMillis: Long,
    processStartElapsedRealtimeMillis: Long,
): Long = (nowMillis - elapsedRealtimeMillis) / 1000 + processStartElapsedRealtimeMillis / 1000

/**
 * The orphan-sweep delete predicate, as a pure pair so OrphanSweepTest can pin it:
 * - RELATIVE_PATH values are normalized with a trailing slash ("DCIM/TeleCamPro/"), so the pattern
 *   anchors on it — "DCIM/TeleCamPro%" would also sweep a hypothetical "DCIM/TeleCamProOther/".
 * - OWNER_PACKAGE_NAME makes ownership an EXPLICIT invariant (mirroring queryOwnedPublished):
 *   scoped storage without READ_MEDIA_* already hides other apps' rows today, but a future
 *   media-read permission would otherwise silently widen this delete sweep to any app's pending
 *   items under a same-named DCIM path.
 * - Placeholder count must equal the arg count; a drifted edit fails the test, not silently
 *   inside the sweep's runCatching.
 */
internal fun orphanSweepSelection(
    subDirs: List<String>,
    packageName: String,
    cutoffEpochSecs: Long,
): Pair<String, Array<String>> {
    // One LIKE per known directory: the sweep has to reach a PREVIOUS naming too, or a pending row
    // stranded there before the rename is never cleaned up and never expires.
    val paths = subDirs.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
    val selection =
        "($paths) AND ${MediaStore.MediaColumns.DATE_ADDED} < ? AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
    return selection to (
        subDirs.map { "DCIM/$it/%" } + listOf(cutoffEpochSecs.toString(), packageName)
        ).toTypedArray()
}
