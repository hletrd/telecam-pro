package me.hletrd.telecampro.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import me.hletrd.telecampro.camera.MAX_RETAINED_SINGLE_PROCESSED_SNAPSHOTS
import me.hletrd.telecampro.camera.RECORDING_STORAGE_BACKLOG_CAPACITY
import me.hletrd.telecampro.camera.RECORDING_STORAGE_WORKER_COUNT

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
    internal const val MAX_ORPHAN_RECOVERY_ROWS_PER_COLLECTION = 64
    internal const val MAX_DISCARD_RECOVERY_ROWS = 64
    private const val MAX_FAMILY_ROWS = 8
    private const val PENDING_JOURNAL = "pending_media_journal"
    private const val PENDING_REGISTERED = "registered"
    private const val PENDING_COMPLETE = "complete"
    private const val DELETED_FAMILY_JOURNAL = "deleted_capture_family_journal"
    private const val DELETED_FAMILY_PREFIX = "F1|"
    private const val COMPLETION_MARK_ATTEMPTS = 3
    private const val COMPLETION_MARK_BACKOFF_MS = 25L
    internal const val MAX_DELETED_FAMILY_MARKERS = 64
    internal const val MAX_REJECTED_OUTPUTS = 32
    /**
     * Exact headroom for every output that can still reach rejection after the soft admission edge:
     * two process-wide retained processed snapshots at three siblings each; one Camera2 RAW-only
     * shot; two active + eight queued process recording-storage tails; and one active recorder not
     * yet handed to that dispatcher. These upstream owners are all hard-bounded.
     */
    internal const val MAX_ALREADY_ADMITTED_REJECTED_OUTPUTS =
        MAX_RETAINED_SINGLE_PROCESSED_SNAPSHOTS * 3 +
            1 +
            RECORDING_STORAGE_WORKER_COUNT + RECORDING_STORAGE_BACKLOG_CAPACITY +
            1
    private val processJournalOwner = UUID.randomUUID().toString()
    private val familyJournalMetadataLock = Any()
    private val familyAuthorityRegistryLock = Any()
    private val familyAuthorities = mutableMapOf<String, FamilyJournalAuthority>()

    private class FamilyJournalAuthority(
        val monitor: Any = Any(),
        // This monitor protects only the finite admission transition. Retirement seals an empty
        // state here before slow marker removal, then opens it again before releasing [monitor]. A
        // registrant that observes the seal waits on [monitor] and becomes a post-retirement
        // admission; it never waits while owning the process-wide registry.
        val admissionMonitor: Any = Any(),
        var users: Int = 0,
        // Registered before Camera2 receives the still request and retained until every possible
        // HEIF/JPEG/DNG continuation is terminal. Unlike an Engine-local capture id, this survives
        // Engine replacement and therefore prevents a restored delete from retiring the exact
        // family veto in front of a future old-Engine sibling.
        var producerLeases: Int = 0,
        // Registered before a publication caller waits for [monitor]. Retirement consults this
        // count after its exact-family absence query, so it cannot erase the durable veto in front
        // of an old-Engine publication that is already queued behind it.
        var publicationClaims: Int = 0,
        var retirementSealed: Boolean = false,
    )

    private inline fun <T> withFamilyJournalAuthority(
        key: String,
        publication: Boolean = false,
        onRegistered: () -> Unit = {},
        block: (FamilyJournalAuthority) -> T,
    ): T {
        var publicationInstalled = false
        val authority = synchronized(familyAuthorityRegistryLock) {
            familyAuthorities.getOrPut(key, ::FamilyJournalAuthority).also {
                it.users += 1
                if (publication) synchronized(it.admissionMonitor) {
                    if (!it.retirementSealed) {
                        it.publicationClaims += 1
                        publicationInstalled = true
                    }
                }
            }
        }
        return try {
            if (publication && !publicationInstalled) {
                // Retirement already sealed its claim-free decision. Wait without the registry,
                // then install as a new post-retirement admission before observing the marker.
                synchronized(authority.monitor) {
                    synchronized(authority.admissionMonitor) {
                        check(!authority.retirementSealed) {
                            "sealed family admission escaped retirement"
                        }
                        authority.publicationClaims += 1
                        publicationInstalled = true
                    }
                    onRegistered()
                    block(authority)
                }
            } else {
                onRegistered()
                synchronized(authority.monitor) { block(authority) }
            }
        } finally {
            if (publicationInstalled) synchronized(authority.admissionMonitor) {
                check(authority.publicationClaims > 0) { "family publication claim underflow" }
                authority.publicationClaims -= 1
            }
            synchronized(familyAuthorityRegistryLock) {
                authority.users -= 1
                if (authority.users == 0 && familyAuthorities[key] === authority) {
                    familyAuthorities.remove(key)
                }
            }
        }
    }

    /**
     * Registers process-wide producer ownership before Camera2 can create any row in [family].
     * The returned lease is exact-family and idempotent; callers close it only after every output
     * lane is terminal, including rejected dispatch and exceptional completion.
     */
    internal fun registerStillFamilyProducer(family: CaptureFamilyKey): CaptureFamilyProducerLease {
        val key = deletedFamilyJournalKey(family)
        var producerInstalled = false
        val authority = synchronized(familyAuthorityRegistryLock) {
            familyAuthorities.getOrPut(key, ::FamilyJournalAuthority).also {
                it.users += 1
                synchronized(it.admissionMonitor) {
                    if (!it.retirementSealed) {
                        it.producerLeases += 1
                        producerInstalled = true
                    }
                }
            }
        }
        if (!producerInstalled) {
            // A producer arriving after the retirement decision is sealed is not retroactively
            // covered by that decision. It waits, without the registry, and starts a new family
            // admission only after marker retirement has completed.
            synchronized(authority.monitor) {
                synchronized(authority.admissionMonitor) {
                    check(!authority.retirementSealed) {
                        "sealed family producer escaped retirement"
                    }
                    authority.producerLeases += 1
                }
            }
        }
        return CaptureFamilyProducerLease {
            synchronized(authority.admissionMonitor) {
                check(authority.producerLeases > 0) { "family producer lease underflow" }
                authority.producerLeases -= 1
            }
            synchronized(familyAuthorityRegistryLock) {
                authority.users -= 1
                if (authority.users == 0 && familyAuthorities[key] === authority) {
                    familyAuthorities.remove(key)
                }
            }
        }
    }

    private data class RejectedOutput(val context: Context, val uri: Uri)

    private val rejectedOutputOwner = BoundedRejectedOutputOwner<RejectedOutput>(
        admissionLimit = MAX_REJECTED_OUTPUTS,
        // Once admission closes, work already accepted by the bounded still/recording owners may
        // still report siblings. Keep equal-sized exact headroom so those URIs are never forgotten.
        ownershipLimit = MAX_REJECTED_OUTPUTS + MAX_ALREADY_ADMITTED_REJECTED_OUTPUTS,
        discardEffect = { discardPendingOutput(it.context, it.uri) },
    )

    /**
     * Reconstructs the newest package-owned row or recognized owner-null TeleCam-format candidate.
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
            // PREVIOUS install: Android can clear that column when the owning package is uninstalled,
            // and in SQL `NULL = 'anything'` is NULL — never true — so those rows can never match
            // any package name. Device evidence (2026-07-27): four rows in our own capture directory
            // with our own IMG_TELECAM_* filenames sat at owner NULL, invisible to this query,
            // which is why the gallery button fell back to its placeholder after a reinstall even
            // though the photos were right there. A NULL owner is therefore recognized only through
            // the explicit historical/current filename + collection + MIME rules. Those public
            // properties cannot prove authorship: an imported lookalike can match too, so the reducer
            // labels every admitted NULL-owner row LEGACY_FORMAT_UNVERIFIED and limits it to file-only
            // deletion. The reducer repeats the exact grammar after this coarse provider filter. A
            // row owned by a DIFFERENT package is still excluded.
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
                            // The selection admits our package OR a null owner matching the
                            // historical/current TeleCam format. Null-owner rows are recognized but
                            // unverifiable and remain file-only because deletion can require consent.
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
                SharedPreferencesDurableEdit.putString(
                    context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE),
                    uri.toString(),
                    PENDING_COMPLETE,
                )
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
    internal fun discardPendingOutput(
        context: Context,
        uri: Uri,
        discardJournal: PendingDiscardJournal = PendingDiscardJournal(context),
    ): PendingOutputDiscardResult {
        val marker = markCompletionWithRetry(
            maxAttempts = COMPLETION_MARK_ATTEMPTS,
            commit = {
                discardJournal.mark(uri.toString())
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
    internal fun markFamilyDeletedResult(
        context: Context,
        family: CaptureFamilyKey,
    ): FamilyDeletionMarkResult = markFamilyDeletedResult(
        family = family,
        markerStore = SharedPreferencesFamilyMarkerStore(
            context.getSharedPreferences(DELETED_FAMILY_JOURNAL, Context.MODE_PRIVATE),
        ),
    )

    internal fun markFamilyDeletedResult(
        family: CaptureFamilyKey,
        markerStore: FamilyDeletionMarkerStore,
    ): FamilyDeletionMarkResult = withFamilyJournalAuthority(deletedFamilyJournalKey(family)) {
        val key = deletedFamilyJournalKey(family)
        repeat(COMPLETION_MARK_ATTEMPTS) { attempt ->
            val durable = synchronized(familyJournalMetadataLock) {
                if (markerStore.contains(key)) {
                    return@withFamilyJournalAuthority FamilyDeletionMarkResult.DURABLE
                }
                val markerCount = runCatching { markerStore.size() }.getOrElse {
                    return@withFamilyJournalAuthority FamilyDeletionMarkResult.UNAVAILABLE
                }
                if (markerCount >= MAX_DELETED_FAMILY_MARKERS) {
                    return@withFamilyJournalAuthority FamilyDeletionMarkResult.CAPACITY_EXHAUSTED
                }
                markerStore.put(key, processJournalOwner)
            }
            if (durable) return@withFamilyJournalAuthority FamilyDeletionMarkResult.DURABLE
            if (attempt + 1 < COMPLETION_MARK_ATTEMPTS) {
                // Preference I/O is serialized for capacity, but retry delay never owns global
                // metadata authority and cannot stall another family's durable mark.
                runCatching { Thread.sleep(COMPLETION_MARK_BACKOFF_MS * (attempt + 1)) }
            }
        }
        FamilyDeletionMarkResult.UNAVAILABLE
    }

    internal fun markFamilyDeleted(context: Context, family: CaptureFamilyKey): Boolean =
        markFamilyDeletedResult(context, family) == FamilyDeletionMarkResult.DURABLE

    /**
     * Retires one durable family marker only after producer terminality and exact provider absence.
     * The query is injectable so the durability/limit state machine is exhaustively host-testable.
     */
    internal fun retireFamilyDeletionMarker(
        context: Context,
        family: CaptureFamilyKey,
        producersTerminal: Boolean,
        exactFamilyAbsent: () -> Boolean?,
    ): FamilyDeletionRetirementResult = retireFamilyDeletionMarker(
        family = family,
        producersTerminal = producersTerminal,
        markerStore = SharedPreferencesFamilyMarkerStore(
            context.getSharedPreferences(DELETED_FAMILY_JOURNAL, Context.MODE_PRIVATE),
        ),
        exactFamilyAbsent = exactFamilyAbsent,
    )

    internal fun retireFamilyDeletionMarker(
        family: CaptureFamilyKey,
        producersTerminal: Boolean,
        markerStore: FamilyDeletionMarkerStore,
        exactFamilyAbsent: () -> Boolean?,
    ): FamilyDeletionRetirementResult {
        if (!producersTerminal) return FamilyDeletionRetirementResult.PRODUCERS_ACTIVE
        val key = deletedFamilyJournalKey(family)
        return withFamilyJournalAuthority(key) { authority ->
            if (synchronized(authority.admissionMonitor) { authority.producerLeases > 0 }) {
                return@withFamilyJournalAuthority FamilyDeletionRetirementResult.PRODUCERS_ACTIVE
            }
            // SharedPreferences reads are thread-safe. Exact-family authority supplies the required
            // same-key ordering; taking the global capacity/RMW lock here would couple an unrelated
            // family's publication or absence query to a slow synchronous commit.
            val markerPresent = runCatching { markerStore.contains(key) }.getOrNull()
                ?: return@withFamilyJournalAuthority FamilyDeletionRetirementResult.RETRYABLE
            if (!markerPresent) {
                return@withFamilyJournalAuthority FamilyDeletionRetirementResult.ALREADY_ABSENT
            }

            // The exact-family authority prevents a same-family re-mark from being erased, while
            // unrelated families remain free to commit their own deletion markers during provider
            // I/O. Never place this unbounded Binder query under the global metadata monitor.
            when (runCatching(exactFamilyAbsent).getOrNull()) {
                false -> return@withFamilyJournalAuthority FamilyDeletionRetirementResult.RETAINED
                null -> return@withFamilyJournalAuthority FamilyDeletionRetirementResult.RETRYABLE
                true -> Unit
            }

            val sealedResult = synchronized(authority.admissionMonitor) {
                if (authority.producerLeases > 0) {
                    return@synchronized FamilyDeletionRetirementResult.PRODUCERS_ACTIVE
                }
                // A publication registers before it waits for this family's monitor. If one
                // arrived during the absence query, retain the marker: after we release the exact
                // authority that publication must observe the durable delete and discard its row.
                if (authority.publicationClaims > 0) {
                    FamilyDeletionRetirementResult.RETRYABLE
                } else {
                    // This is the linearization point. Later registrants wait on the exact-family
                    // owner and become post-retirement admissions; earlier ones are counted above.
                    authority.retirementSealed = true
                    null
                }
            }
            if (sealedResult != null) return@withFamilyJournalAuthority sealedResult

            try {
                // Capacity-changing removal still uses the metadata RMW lock. Neither it nor the
                // slow preference commit owns the global registry or admission monitor.
                runCatching {
                    synchronized(familyJournalMetadataLock) {
                        if (!markerStore.contains(key)) {
                            FamilyDeletionRetirementResult.ALREADY_ABSENT
                        } else if (markerStore.remove(key)) {
                            FamilyDeletionRetirementResult.RETIRED
                        } else {
                            FamilyDeletionRetirementResult.RETRYABLE
                        }
                    }
                }.getOrDefault(FamilyDeletionRetirementResult.RETRYABLE)
            } finally {
                synchronized(authority.admissionMonitor) {
                    check(authority.retirementSealed) { "family retirement seal lost" }
                    authority.retirementSealed = false
                }
            }
        }
    }

    /** Exact Images/Video absence proof for a producer-terminal family. */
    internal fun retireFamilyDeletionIfAbsent(
        context: Context,
        family: CaptureFamilyKey,
        producersTerminal: Boolean,
        subDirs: List<String> = CAPTURE_SUBDIRS,
    ): FamilyDeletionRetirementResult = retireFamilyDeletionMarker(
        context = context,
        family = family,
        producersTerminal = producersTerminal,
        exactFamilyAbsent = {
            val base = when (family.media) {
                CaptureFamilyMedia.STILL -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                CaptureFamilyMedia.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val query = deletedFamilyQuery(family, subDirs, context.packageName)
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, query.selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, query.args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(
                base,
                arrayOf(MediaStore.MediaColumns._ID),
                queryArgs,
                null,
            )?.use { cursor -> !cursor.moveToFirst() }
        },
    )

    /**
     * Bounded same-process retry for retirement submissions lost to finite-lane saturation.
     *
     * The journal itself is capped at [MAX_DELETED_FAMILY_MARKERS], so the snapshot cannot grow
     * without bound. Each entry goes back through [retireFamilyDeletionIfAbsent]: producer leases,
     * publication claims, marker ownership, and exact pending+published provider absence are all
     * re-evaluated at retry execution time. Query/removal failure therefore retains the marker.
     */
    internal fun retireCurrentProcessFamilyDeletions(
        context: Context,
        subDirs: List<String> = CAPTURE_SUBDIRS,
    ): Map<CaptureFamilyKey, FamilyDeletionRetirementResult> =
        retireCurrentProcessFamilyDeletionsResult(context, subDirs).results

    /** Preserves a journal-enumeration failure so the process retry owner cannot mistake it for empty. */
    internal fun retireCurrentProcessFamilyDeletionsResult(
        context: Context,
        subDirs: List<String> = CAPTURE_SUBDIRS,
    ): CurrentProcessFamilyRetirementScan {
        val preferences = context.getSharedPreferences(DELETED_FAMILY_JOURNAL, Context.MODE_PRIVATE)
        val ownedEntries = runCatching {
            preferences.all
                .filterValues { owner -> owner == processJournalOwner }
                .toSortedMap()
        }.getOrElse {
            return CurrentProcessFamilyRetirementScan(emptyMap(), retryableFailure = true)
        }
        val batch = boundedDeletedFamilyBatch(ownedEntries, MAX_DELETED_FAMILY_MARKERS)
        val results = buildMap {
            batch.entries.forEach { (rawKey, _) ->
                val family = parseDeletedFamilyJournalKey(rawKey) ?: return@forEach
                put(
                    family,
                    retireFamilyDeletionIfAbsent(
                        context = context,
                        family = family,
                        producersTerminal = true,
                        subDirs = subDirs,
                    ),
                )
            }
        }
        return CurrentProcessFamilyRetirementScan(results, retryableFailure = false)
    }

    /** Fast shared-preference read used by launch restoration and recovery. */
    internal fun isFamilyDeleted(context: Context, family: CaptureFamilyKey): Boolean =
        context.getSharedPreferences(DELETED_FAMILY_JOURNAL, Context.MODE_PRIVATE)
            .contains(deletedFamilyJournalKey(family))

    /**
     * Orders one complete still publication decision against deletion of its exact family.
     *
     * Publication registers its claim before waiting for the family monitor, then keeps that
     * authority through provider publication and the saved callback. A replacement Engine's
     * same-family delete therefore either commits first and selects [deleted], or waits until
     * [live] is wholly complete. Unrelated families use different monitors. The global registry
     * and metadata locks never span [live], [deleted], provider calls, or retry sleeps.
     *
     * Journal-read uncertainty selects [unavailable], retaining complete bytes privately rather
     * than guessing that the family is live or destructively treating an unreadable marker as a
     * confirmed delete.
     */
    internal fun <T> withFamilyPublicationAuthority(
        context: Context,
        family: CaptureFamilyKey,
        deleted: () -> T,
        unavailable: () -> T,
        live: () -> T,
        publicationRegistered: () -> Unit = {},
    ): T = withFamilyPublicationAuthority(
        family = family,
        markerStore = SharedPreferencesFamilyMarkerStore(
            context.getSharedPreferences(DELETED_FAMILY_JOURNAL, Context.MODE_PRIVATE),
        ),
        deleted = deleted,
        unavailable = unavailable,
        live = live,
        publicationRegistered = publicationRegistered,
    )

    internal fun <T> withFamilyPublicationAuthority(
        family: CaptureFamilyKey,
        markerStore: FamilyDeletionMarkerStore,
        deleted: () -> T,
        unavailable: () -> T,
        live: () -> T,
        publicationRegistered: () -> Unit = {},
    ): T {
        val key = deletedFamilyJournalKey(family)
        return withFamilyJournalAuthority(
            key,
            publication = true,
            onRegistered = publicationRegistered,
        ) {
            // The exact-family monitor is the ordering authority. SharedPreferences.contains is
            // thread-safe, so a read-only publication decision must not wait for another family's
            // capacity-changing commit under [familyJournalMetadataLock].
            val markerPresent = runCatching { markerStore.contains(key) }.getOrNull()
            when (markerPresent) {
                true -> deleted()
                false -> live()
                null -> unavailable()
            }
        }
    }

    /** Durable reject/delete path for any output the app has already classified as failed. */
    internal fun discardRejectedOutput(context: Context, uri: Uri): PendingOutputDiscardResult =
        rejectedOutputOwner.discard(RejectedOutput(context.applicationContext, uri))

    internal fun retryRejectedOutputs(): Int = rejectedOutputOwner.retryUnresolved()

    internal fun rejectedOutputAdmissionAvailable(): Boolean = rejectedOutputOwner.canAdmit()

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
    fun publish(context: Context, uri: Uri): Boolean =
        publish(context, uri, PendingDiscardJournal(context))

    internal fun publish(
        context: Context,
        uri: Uri,
        discardJournal: PendingDiscardJournal,
    ): Boolean = discardJournal.withLookupAuthority(uri.toString()) { lookup ->
        if (lookup != DiscardJournalLookup.ABSENT) return@withLookupAuthority false
        val preferenceState = pendingPreferenceState(context, uri)
        if (
            preferenceState == PendingJournalState.DISCARD ||
            preferenceState == PendingJournalState.UNAVAILABLE
        ) {
            return@withLookupAuthority false
        }

        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        repeat(PUBLISH_ATTEMPTS) { attempt ->
            val published = runCatching {
                context.contentResolver.update(uri, values, null, null) > 0
            }.getOrDefault(false)
            if (published) {
                // SQLite was proven absent while holding this URI's process authority. Remove only
                // the older preference; a later exact DISCARD owner remains independently durable.
                runCatching {
                    context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
                        .edit(commit = true) { remove(uri.toString()) }
                }
                return@withLookupAuthority true
            }
            if (attempt < PUBLISH_ATTEMPTS - 1) {
                runCatching { Thread.sleep(PUBLISH_RETRY_BACKOFF_MS * (attempt + 1)) }
            }
        }
        false
    }

    private const val PUBLISH_ATTEMPTS = 3
    private const val PUBLISH_RETRY_BACKOFF_MS = 50L

    /**
     * Deletes one tracker-known output without conflating provider truth with journal cleanup.
     *
     * A deleted/already-absent provider row stays absent even when exact DISCARD-marker removal
     * fails. The retained marker is then recovery work, not evidence that the URI still exists and
     * should be restored into review. Conversely, a confirmed provider survivor remains reviewable,
     * while an unanswered provider probe is kept distinct so the UI can report uncertainty without
     * manufacturing a phantom handle.
     */
    internal fun deleteKnownOutput(
        context: Context,
        uri: Uri,
        discardJournal: PendingDiscardJournal = PendingDiscardJournal(context),
        expectedIdentity: PendingDiscardIdentity? = null,
    ): KnownOutputDeletionResult =
        knownOutputDeletionResult(
            delete = {
                val condition = expectedIdentity?.let(::discardDeleteCondition)
                context.contentResolver.delete(uri, condition?.first, condition?.second)
            },
            rowExistsAfter = { mediaRowExists(context, uri) },
            clearDiscardMarker = { clearPending(context, uri, discardJournal) },
        )

    /**
     * True only when both the provider row and its exact durable retry metadata are gone.
     *
     * Generic recovery/discard callers retain the historical Boolean contract. Review-family
     * deletion consumes [deleteKnownOutput] directly because it needs the two axes separately.
     */
    fun delete(context: Context, uri: Uri): Boolean =
        deleteKnownOutput(context, uri).fullyRetired

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

    /** Read-only exact-row truth used after a system-owned delete-consent surface returns. */
    internal fun knownOutputPresence(
        context: Context,
        uri: Uri,
    ): KnownOutputProviderDisposition = when (mediaRowExists(context, uri)) {
        true -> KnownOutputProviderDisposition.PRESENT
        false -> KnownOutputProviderDisposition.ALREADY_ABSENT
        null -> KnownOutputProviderDisposition.UNKNOWN
    }

    /**
     * Deletes every untracked row belonging to one exact versioned capture family.
     *
     * The durable family marker has already won before this sweep starts. Usually the unknown rows
     * are complete-but-pending outputs whose earlier publication failed. The exact-family
     * publication authority also permits the opposite ordering, though: an old Engine can finish
     * publication immediately before a replacement Engine commits Delete. Include published rows
     * here so that winner is still discovered and deleted instead of remaining Gallery-visible.
     * [excluded] is the tracker's frozen set; its caller keeps exact survivor accounting for those
     * rows. Exact F1 names, package ownership, and capture directories prevent proximity or foreign
     * media deletion. Every provider outcome is preserved in [DeletedFamilySweepResult]; unresolved
     * rows and query uncertainty remain owned by the durable family marker.
     */
    internal fun deleteUntrackedFamilySiblings(
        context: Context,
        family: CaptureFamilyKey,
        excluded: Set<Uri>,
    ): DeletedFamilySweepResult {
        val base = when (family.media) {
            CaptureFamilyMedia.STILL -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            CaptureFamilyMedia.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return sweepDeletedFamilySiblings(
            excluded = excluded,
            discover = {
                val queryArgs = Bundle().apply {
                    val query = deletedFamilyQuery(family, CAPTURE_SUBDIRS, context.packageName)
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, query.selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, query.args)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                context.contentResolver.query(
                    base,
                    arrayOf(MediaStore.MediaColumns._ID),
                    queryArgs,
                    null,
                )?.use { cursor ->
                    buildList {
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        while (cursor.moveToNext()) {
                            add(ContentUris.withAppendedId(base, cursor.getLong(idCol)))
                        }
                    }
                } ?: error("MediaProvider returned no cursor for exact-family delete sweep")
            },
            delete = { rowUri -> delete(context, rowUri) },
        )
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
        val discardJournal = PendingDiscardJournal(context)
        var cursor = OrphanRecoveryCursor()
        var cumulative = RecoveryReport()
        var progressedFailures = emptySet<RecoveryFailureClass>()
        var hasMore: Boolean
        do {
            val batch = cleanupOrphanedPendingBatch(
                context,
                cursor,
                subDirs = subDirs,
                discardJournal = discardJournal,
            )
            cumulative = cumulative.foldRecoveryAttempt(batch.report)
            if (batch.report.retryRequired) {
                if (!batch.continueAfterFailureExhaustion) return cumulative
                progressedFailures += batch.report.failureClasses
            }
            cursor = batch.nextCursor
            hasMore = batch.hasMore
        } while (hasMore)
        return if (progressedFailures.isEmpty()) {
            cumulative
        } else {
            cumulative.copy(failureClasses = progressedFailures)
        }
    }

    internal fun cleanupOrphanedPendingBatch(
        context: Context,
        cursor: OrphanRecoveryCursor = OrphanRecoveryCursor(),
        batchLimit: Int = MAX_ORPHAN_RECOVERY_ROWS_PER_COLLECTION,
        subDirs: List<String> = CAPTURE_SUBDIRS,
        discardJournal: PendingDiscardJournal = PendingDiscardJournal(context),
        targets: List<OrphanRecoveryTarget> = defaultOrphanRecoveryTargets(),
    ): OrphanRecoveryBatch {
        require(batchLimit > 0)
        require(targets.isNotEmpty())
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
        // Process-local rejected rows and durable family tombstones are one bounded preflight, not
        // work repeated for every Images/Video page. A failure retries this exact stage before any
        // media cursor advances.
        if (!cursor.preflightComplete) {
            retryRejectedOutputs()
            val report = cleanupDeletedFamilyJournal(context, subDirs)
            return OrphanRecoveryBatch(
                report = report,
                nextCursor = cursor.copy(preflightComplete = true),
                hasMore = true,
            )
        }

        // The exact-URI DISCARD journal is its own terminal stage. Generic collection pages count
        // and advance past its pending rows without deleting them; published rows are reachable only
        // through these durable URI markers. A permanent failure receives the normal bounded retry
        // budget, then advances so one bad provider row cannot starve later markers forever.
        if (cursor.mediaComplete && !cursor.discardComplete) {
            val batch = cleanupDiscardJournalBatch(
                context = context,
                afterKey = cursor.discardAfterKey,
                batchLimit = MAX_DISCARD_RECOVERY_ROWS,
                discardJournal = discardJournal,
            )
            return OrphanRecoveryBatch(
                report = batch.report,
                nextCursor = cursor.copy(
                    discardAfterKey = batch.nextAfterKey,
                    discardComplete = !batch.hasMore,
                ),
                hasMore = batch.hasMore,
                continueAfterFailureExhaustion = true,
            )
        }

        var report = RecoveryReport()
        var nextCursor = cursor
        for (target in targets) {
            val base = target.base
            val collection = target.collection
            val afterId = cursor.afterId(collection)
            if (afterId == OrphanRecoveryCursor.COLLECTION_COMPLETE) continue
            var nextAfterId = afterId
            var collectionHasMore = false
            val collectionResult = runCatching {
                val page = orphanSweepPage(selection, args, afterId, batchLimit)
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, page.selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, page.args)
                    // Pending items are hidden from ordinary queries even for the owner; opt in.
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putStringArray(
                        ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(MediaStore.MediaColumns._ID),
                    )
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, page.queryLimit)
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
                    var processed = 0
                    while (cursor.moveToNext()) {
                        if (cursor.getInt(pendingCol) != 1) continue
                        if (processed >= batchLimit) {
                            collectionHasMore = true
                            break
                        }
                        val rowId = cursor.getLong(idCol)
                        nextAfterId = rowId
                        processed += 1
                        report = report.record(RecoveryEvent.SCANNED)
                        val uri = ContentUris.withAppendedId(base, rowId)
                        val journalState = pendingJournalState(context, uri, discardJournal)
                        if (journalState == PendingJournalState.UNAVAILABLE) {
                            // SQLite failure is not absence. Do not probe, adopt, delete, or publish
                            // while exact delete authority is unknowable; retry this same cursor.
                            report = report
                                .record(RecoveryEvent.QUERY_FAILED)
                                .record(RecoveryEvent.RETAINED)
                            continue
                        }
                        val familyDeleted = CaptureFamilyKey.parse(cursor.getString(nameCol))
                            ?.familyKey
                            ?.let { isFamilyDeleted(context, it) }
                            ?: false
                        val sizeBytes = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                        val probeOutcome = when {
                            sizeBytes <= 0L -> PendingProbeOutcome(PendingProbe.INVALID)
                            // The lexicographically paged DISCARD stage below is the only terminal
                            // owner for an exact durable URI marker. This generic MediaStore page
                            // still counts/advances past the row, but must not spend the whole launch
                            // retry budget trying the same delete before that progress-capable stage.
                            journalState == PendingJournalState.DISCARD ->
                                PendingProbeOutcome(PendingProbe.INDETERMINATE)
                            journalState == PendingJournalState.COMPLETE -> PendingProbeOutcome(PendingProbe.VALID)
                            else -> probePendingMedia(
                                context = context,
                                uri = uri,
                                mimeType = cursor.getString(mimeCol).orEmpty(),
                                isVideoCollection = collection == OrphanRecoveryCollection.VIDEO,
                                sizeBytes = sizeBytes,
                            )
                        }
                        if (probeOutcome.failed) {
                            report = report.record(RecoveryEvent.PROBE_FAILED)
                        }
                        when (orphanDisposition(journalState, probeOutcome.probe, familyDeleted)) {
                            OrphanDisposition.ADOPT -> {
                                report = report.record(
                                    if (publish(context, uri, discardJournal)) RecoveryEvent.ADOPTED
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
            if (collectionResult.isFailure) {
                report = report.record(RecoveryEvent.QUERY_FAILED)
            } else {
                nextCursor = nextCursor.withAfterId(
                    collection,
                    if (collectionHasMore) nextAfterId else OrphanRecoveryCursor.COLLECTION_COMPLETE,
                )
            }
        }
        return OrphanRecoveryBatch(
            report = report,
            nextCursor = nextCursor,
            // Even an empty media scan must enter the independent DISCARD stage once.
            hasMore = !nextCursor.mediaComplete || !nextCursor.discardComplete,
        )
    }

    /**
     * Reconciles family tombstones against both pending and published rows.
     *
     * A marker written by this process is deliberately never cleared here: a retired Engine may
     * still own an accepted save tail. A prior-process marker can be removed only after its exact,
     * bounded family query completed and every matching row was authoritatively deleted/absent.
     */
    private fun cleanupDeletedFamilyJournal(
        context: Context,
        subDirs: List<String>,
    ): RecoveryReport {
        val preferences = context.getSharedPreferences(DELETED_FAMILY_JOURNAL, Context.MODE_PRIVATE)
        val allEntries = runCatching { preferences.all.toSortedMap() }.getOrElse {
            return RecoveryReport().record(RecoveryEvent.QUERY_FAILED)
        }
        val batch = boundedDeletedFamilyBatch(allEntries, MAX_DELETED_FAMILY_MARKERS)
        var report = if (batch.hasMore) {
            RecoveryReport().record(RecoveryEvent.QUERY_FAILED)
        } else {
            RecoveryReport()
        }
        batch.entries.forEach { (rawKey, rawOwner) ->
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
                val query = deletedFamilyQuery(family, subDirs, context.packageName)
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, query.selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, query.args)
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
                preferences.edit(commit = true) { remove(rawKey) }
            }
        }
        return report
    }

    internal fun cleanupDiscardJournalBatch(
        context: Context,
        afterKey: String?,
        batchLimit: Int,
        discardJournal: PendingDiscardJournal,
    ): DiscardJournalRecoveryBatch {
        val page = runCatching {
            discardJournal.page(afterKey, batchLimit)
        }.getOrElse {
            return DiscardJournalRecoveryBatch(
                report = RecoveryReport().record(RecoveryEvent.QUERY_FAILED),
                nextAfterKey = afterKey,
                hasMore = false,
            )
        }
        var report = RecoveryReport()
        for (record in page.records) {
            report = report.record(RecoveryEvent.SCANNED)
            val uri = runCatching { record.uri.toUri() }.getOrNull()
            val event = if (uri == null) {
                RecoveryEvent.DELETE_FAILED
            } else {
                discardJournal.withReplayIdentityAuthority(record) { identity ->
                    when (identity) {
                        DiscardReplayIdentity.MATCH -> {
                            if (
                                deleteKnownOutput(
                                    context = context,
                                    uri = uri,
                                    discardJournal = discardJournal,
                                    expectedIdentity = requireNotNull(record.identity),
                                ).fullyRetired
                            ) {
                                RecoveryEvent.DELETED
                            } else {
                                RecoveryEvent.DELETE_FAILED
                            }
                        }
                        DiscardReplayIdentity.ABSENT -> {
                            if (clearPending(context, uri, discardJournal)) {
                                RecoveryEvent.DELETED
                            } else {
                                RecoveryEvent.DELETE_FAILED
                            }
                        }
                        DiscardReplayIdentity.MISMATCH,
                        DiscardReplayIdentity.AMBIGUOUS,
                        DiscardReplayIdentity.UNAVAILABLE,
                        DiscardReplayIdentity.LEGACY,
                        -> RecoveryEvent.DELETE_FAILED
                    }
                }
            }
            report = report.record(event)
        }
        return DiscardJournalRecoveryBatch(report, page.nextAfterKey, page.hasMore)
    }

    private fun registerPending(context: Context, uri: Uri): Uri? {
        val registered = SharedPreferencesDurableEdit.putString(
            context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE),
            uri.toString(),
            PENDING_REGISTERED,
        )
        if (registered) return uri
        runCatching { context.contentResolver.delete(uri, null, null) }
        return null
    }

    private fun pendingJournalState(
        context: Context,
        uri: Uri,
        discardJournal: PendingDiscardJournal,
    ): PendingJournalState = when (discardJournal.lookup(uri.toString())) {
        DiscardJournalLookup.PRESENT -> PendingJournalState.DISCARD
        DiscardJournalLookup.UNAVAILABLE -> PendingJournalState.UNAVAILABLE
        DiscardJournalLookup.ABSENT -> pendingPreferenceState(context, uri)
    }

    private fun pendingPreferenceState(context: Context, uri: Uri): PendingJournalState =
        runCatching {
            when (
                context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
                    .getString(uri.toString(), null)
            ) {
                PENDING_COMPLETE -> PendingJournalState.COMPLETE
                PendingDiscardJournal.LEGACY_DISCARD_VALUE -> PendingJournalState.DISCARD
                PENDING_REGISTERED -> PendingJournalState.REGISTERED
                else -> PendingJournalState.UNKNOWN
            }
        }.getOrDefault(PendingJournalState.UNAVAILABLE)

    private fun clearPending(
        context: Context,
        uri: Uri,
        discardJournal: PendingDiscardJournal = PendingDiscardJournal(context),
    ): Boolean {
        val discardRemoved = discardJournal.remove(uri.toString())
        context.getSharedPreferences(PENDING_JOURNAL, Context.MODE_PRIVATE)
            .edit(commit = true) { remove(uri.toString()) }
        return discardRemoved
    }

    /** Atomically refuses a row that changed after the replay identity query. */
    private fun discardDeleteCondition(
        identity: PendingDiscardIdentity,
    ): Pair<String, Array<String>> {
        val clauses = mutableListOf(
            "${MediaStore.MediaColumns._ID} = ?",
            "${MediaStore.MediaColumns.GENERATION_ADDED} = ?",
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            "${MediaStore.MediaColumns.MIME_TYPE} = ?",
        )
        val args = mutableListOf(
            identity.rowId.toString(),
            identity.generationAdded.toString(),
            identity.displayName,
            identity.relativePath,
            identity.mimeType,
        )
        identity.ownerPackageName?.let { owner ->
            clauses += "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            args += owner
        }
        identity.dateTaken?.let { dateTaken ->
            clauses += "${MediaStore.MediaColumns.DATE_TAKEN} = ?"
            args += dateTaken.toString()
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
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

/** Exact process-owned still-family producer marker; [close] is safe to repeat. */
internal class CaptureFamilyProducerLease(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/** Narrow storage seam that keeps family ordering tests independent of Android preference timing. */
internal interface FamilyDeletionMarkerStore {
    fun contains(key: String): Boolean
    fun size(): Int
    fun put(key: String, owner: String): Boolean
    fun remove(key: String): Boolean
}

private class SharedPreferencesFamilyMarkerStore(
    private val preferences: SharedPreferences,
) : FamilyDeletionMarkerStore {
    override fun contains(key: String): Boolean = preferences.contains(key)

    override fun size(): Int = preferences.all.size

    override fun put(key: String, owner: String): Boolean =
        SharedPreferencesDurableEdit.putString(preferences, key, owner)

    override fun remove(key: String): Boolean = SharedPreferencesDurableEdit.remove(preferences, key)
}

internal enum class FamilyDeletionMarkResult { DURABLE, CAPACITY_EXHAUSTED, UNAVAILABLE }

internal enum class FamilyDeletionRetirementResult {
    RETIRED,
    ALREADY_ABSENT,
    PRODUCERS_ACTIVE,
    /** An exact provider row authoritatively remains; retry only after another mutation edge. */
    RETAINED,
    /** Marker/query/publication uncertainty may clear without another family mutation. */
    RETRYABLE,
}

internal data class CurrentProcessFamilyRetirementScan(
    val results: Map<CaptureFamilyKey, FamilyDeletionRetirementResult>,
    val retryableFailure: Boolean,
)

internal data class DeletedFamilyQuery(val selection: String, val args: Array<String>)

/** Exact terminal accounting for the untracked portion of a capture-family delete. */
internal data class DeletedFamilySweepResult(
    val discovered: Int = 0,
    val deleted: Int = 0,
    val unresolved: Int = 0,
    val queryFailed: Boolean = false,
) {
    init {
        require(discovered >= 0)
        require(deleted >= 0)
        require(unresolved >= 0)
        require(deleted + unresolved == discovered)
    }

    val complete: Boolean get() = !queryFailed && unresolved == 0

    companion object {
        val QUERY_FAILED = DeletedFamilySweepResult(queryFailed = true)
    }
}

/**
 * Provider seam for exact-family discovery and deletion.
 *
 * Query failure is distinct from an authoritative empty result. A row whose delete reports success
 * includes the already-absent case because [MediaStoreWriter.delete] probes an ambiguous zero count
 * before returning; every false result remains an explicit unresolved sibling.
 */
internal fun <T> sweepDeletedFamilySiblings(
    excluded: Set<T>,
    discover: () -> List<T>,
    delete: (T) -> Boolean,
): DeletedFamilySweepResult {
    val rows = runCatching(discover).getOrElse { return DeletedFamilySweepResult.QUERY_FAILED }
    var discovered = 0
    var deleted = 0
    var unresolved = 0
    rows.forEach { row ->
        if (row in excluded) return@forEach
        discovered += 1
        if (runCatching { delete(row) }.getOrDefault(false)) {
            deleted += 1
        } else {
            unresolved += 1
        }
    }
    return DeletedFamilySweepResult(
        discovered = discovered,
        deleted = deleted,
        unresolved = unresolved,
    )
}

internal data class DeletedFamilyBatch<K, V>(
    val entries: List<Map.Entry<K, V>>,
    val hasMore: Boolean,
)

internal fun <K, V> boundedDeletedFamilyBatch(
    entries: Map<K, V>,
    limit: Int,
): DeletedFamilyBatch<K, V> {
    require(limit > 0)
    return DeletedFamilyBatch(entries.entries.take(limit), entries.size > limit)
}

/** Exact bounded family query, extracted so placeholder order and path/owner anchors are testable. */
internal fun deletedFamilyQuery(
    family: CaptureFamilyKey,
    subDirs: List<String>,
    packageName: String,
): DeletedFamilyQuery {
    require(subDirs.isNotEmpty())
    val names = family.knownOutputDisplayNames()
    val paths = subDirs.joinToString(" OR ") {
        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
    }
    return DeletedFamilyQuery(
        selection = "($paths) AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} IN (" +
            names.joinToString(",") { "?" } + ")",
        args = (subDirs.map { "DCIM/$it/%" } + packageName + names).toTypedArray(),
    )
}

/** Finite process owner for discard attempts whose URI marker and provider delete both failed. */
internal class BoundedRejectedOutputOwner<T>(
    private val admissionLimit: Int,
    private val ownershipLimit: Int = admissionLimit * 2,
    private val discardEffect: (T) -> PendingOutputDiscardResult,
) {
    private val lock = Any()
    private val unresolved = LinkedHashSet<T>()

    init {
        require(admissionLimit > 0)
        require(ownershipLimit >= admissionLimit)
    }

    fun discard(output: T): PendingOutputDiscardResult {
        val result = runCatching { discardEffect(output) }
            .getOrDefault(PendingOutputDiscardResult.UNRESOLVED)
        synchronized(lock) {
            if (result == PendingOutputDiscardResult.UNRESOLVED) {
                // Admission closes at the soft limit, while bounded headroom owns siblings/tails
                // accepted before that edge. Never silently evict the exact identity at the edge.
                check(output in unresolved || unresolved.size < ownershipLimit) {
                    "already-admitted rejected-output ownership exhausted"
                }
                unresolved.add(output)
            } else {
                unresolved.remove(output)
            }
        }
        return result
    }

    fun retryUnresolved(): Int {
        val pending = synchronized(lock) { unresolved.toList() }
        pending.forEach(::discard)
        return unresolvedCount()
    }

    fun canAdmit(): Boolean = synchronized(lock) { unresolved.size < admissionLimit }

    internal fun unresolvedCount(): Int = synchronized(lock) { unresolved.size }
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

/** Monotonic per-collection page cursor for one process-owned launch recovery. */
internal enum class OrphanRecoveryCollection { IMAGES, VIDEO }

internal data class OrphanRecoveryTarget(
    val base: Uri,
    val collection: OrphanRecoveryCollection,
)

private fun defaultOrphanRecoveryTargets(): List<OrphanRecoveryTarget> = listOf(
    OrphanRecoveryTarget(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, OrphanRecoveryCollection.IMAGES),
    OrphanRecoveryTarget(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, OrphanRecoveryCollection.VIDEO),
)

internal data class OrphanRecoveryCursor(
    val preflightComplete: Boolean = false,
    val imagesAfterId: Long = 0L,
    val videoAfterId: Long = 0L,
    val discardAfterKey: String? = null,
    val discardComplete: Boolean = false,
) {
    fun afterId(collection: OrphanRecoveryCollection): Long = when (collection) {
        OrphanRecoveryCollection.IMAGES -> imagesAfterId
        OrphanRecoveryCollection.VIDEO -> videoAfterId
    }

    fun withAfterId(collection: OrphanRecoveryCollection, value: Long): OrphanRecoveryCursor = when (collection) {
        OrphanRecoveryCollection.IMAGES -> copy(imagesAfterId = value)
        OrphanRecoveryCollection.VIDEO -> copy(videoAfterId = value)
    }

    val mediaComplete: Boolean
        get() = imagesAfterId == COLLECTION_COMPLETE && videoAfterId == COLLECTION_COMPLETE

    companion object {
        const val COLLECTION_COMPLETE = Long.MAX_VALUE
    }
}

internal data class OrphanRecoveryBatch(
    val report: RecoveryReport,
    val nextCursor: OrphanRecoveryCursor,
    val hasMore: Boolean,
    /** A durable per-entry failure may advance only after the ordinary retry budget is exhausted. */
    val continueAfterFailureExhaustion: Boolean = false,
)

internal data class DiscardJournalPage(
    val records: List<PendingDiscardRecord>,
    val nextAfterKey: String?,
    val hasMore: Boolean,
    val rowsRead: Int,
) {
    val keys: List<String>
        get() = records.map(PendingDiscardRecord::uri)
}

internal data class DiscardJournalRecoveryBatch(
    val report: RecoveryReport,
    val nextAfterKey: String?,
    val hasMore: Boolean,
)

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

/** Provider truth for one tracker-known URI after a delete attempt. */
internal enum class KnownOutputProviderDisposition {
    DELETED,
    ALREADY_ABSENT,
    PRESENT,
    UNKNOWN,
}

/** Whether exact DISCARD/recovery metadata was retired after provider absence was established. */
internal enum class DiscardMarkerCleanupDisposition {
    CLEARED,
    RETAINED_FOR_RETRY,
    NOT_ATTEMPTED,
}

/** Two-axis result: provider presence is never inferred from marker-cleanup success. */
internal data class KnownOutputDeletionResult(
    val provider: KnownOutputProviderDisposition,
    val markerCleanup: DiscardMarkerCleanupDisposition,
) {
    val restoreAsSurvivor: Boolean
        get() = provider == KnownOutputProviderDisposition.PRESENT

    val providerAbsent: Boolean
        get() = provider == KnownOutputProviderDisposition.DELETED ||
            provider == KnownOutputProviderDisposition.ALREADY_ABSENT

    val providerUnknown: Boolean
        get() = provider == KnownOutputProviderDisposition.UNKNOWN

    val cleanupRetryRequired: Boolean
        get() = providerAbsent &&
            markerCleanup == DiscardMarkerCleanupDisposition.RETAINED_FOR_RETRY

    val fullyRetired: Boolean
        get() = providerAbsent && markerCleanup == DiscardMarkerCleanupDisposition.CLEARED
}

/**
 * Executes one known-output delete while preserving provider and marker-cleanup evidence.
 *
 * A positive delete count proves absence without a second query. A zero/throwing delete needs an
 * exact existence probe: false proves prior absence, true proves a survivor, and null/throw remains
 * unknown. Marker cleanup runs only after provider absence is authoritative.
 */
internal fun knownOutputDeletionResult(
    delete: () -> Int?,
    rowExistsAfter: () -> Boolean?,
    clearDiscardMarker: () -> Boolean,
): KnownOutputDeletionResult {
    val deleteCount = runCatching(delete).getOrNull()
    val rowExists = if (deleteCount != null && deleteCount > 0) {
        null
    } else {
        runCatching(rowExistsAfter).getOrNull()
    }
    val provider = when (mediaDeleteDisposition(deleteCount, rowExists)) {
        MediaDeleteDisposition.DELETED -> KnownOutputProviderDisposition.DELETED
        MediaDeleteDisposition.ALREADY_ABSENT -> KnownOutputProviderDisposition.ALREADY_ABSENT
        MediaDeleteDisposition.FAILED -> if (rowExists == true) {
            KnownOutputProviderDisposition.PRESENT
        } else {
            KnownOutputProviderDisposition.UNKNOWN
        }
    }
    val markerCleanup = if (
        provider == KnownOutputProviderDisposition.DELETED ||
        provider == KnownOutputProviderDisposition.ALREADY_ABSENT
    ) {
        if (runCatching(clearDiscardMarker).getOrDefault(false)) {
            DiscardMarkerCleanupDisposition.CLEARED
        } else {
            DiscardMarkerCleanupDisposition.RETAINED_FOR_RETRY
        }
    } else {
        DiscardMarkerCleanupDisposition.NOT_ATTEMPTED
    }
    return KnownOutputDeletionResult(provider, markerCleanup)
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

internal enum class PendingJournalState { UNKNOWN, REGISTERED, COMPLETE, DISCARD, UNAVAILABLE }

internal enum class PendingProbe { VALID, INVALID, INDETERMINATE }

internal enum class OrphanDisposition { ADOPT, DELETE, KEEP_PENDING }

/** Pure conservative launch-recovery decision; an unknown answer never destroys user media. */
internal fun orphanDisposition(
    journalState: PendingJournalState,
    probe: PendingProbe,
    familyDeleted: Boolean = false,
): OrphanDisposition = when {
    // Durable exact-URI DISCARD rows are retained by the generic Images/Video pages. The dedicated
    // lexicographic DISCARD stage is their sole delete owner and can advance after bounded failure,
    // so one wedged provider row cannot starve later media pages or later durable markers.
    journalState == PendingJournalState.DISCARD -> OrphanDisposition.KEEP_PENDING
    journalState == PendingJournalState.UNAVAILABLE -> OrphanDisposition.KEEP_PENDING
    familyDeleted -> OrphanDisposition.DELETE
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
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
            "${MediaStore.MediaColumns.IS_PENDING} = 1"
    return selection to (
        subDirs.map { "DCIM/$it/%" } + listOf(cutoffEpochSecs.toString(), packageName)
        ).toTypedArray()
}

internal data class OrphanSweepPage(
    val selection: String,
    val args: Array<String>,
    val queryLimit: Int,
)

/** One extra row proves continuation without processing beyond the page's hard bound. */
internal fun orphanSweepPage(
    selection: String,
    args: Array<String>,
    afterId: Long,
    batchLimit: Int,
): OrphanSweepPage {
    require(batchLimit > 0)
    return OrphanSweepPage(
        selection = "$selection AND ${MediaStore.MediaColumns._ID} > ?",
        args = args + afterId.toString(),
        queryLimit = batchLimit + 1,
    )
}
