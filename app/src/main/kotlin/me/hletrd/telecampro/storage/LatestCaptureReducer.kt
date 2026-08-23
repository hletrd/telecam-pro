package me.hletrd.telecampro.storage

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

internal enum class StoredMediaCollection {
    IMAGE,
    VIDEO,
}

internal enum class StoredMediaOutputKind {
    DISPLAYABLE,
    RAW,
}

/** What the app can truthfully promise when deleting a restored review item. */
internal enum class RestoredDeleteScope {
    /** Every extant row with the exact, versioned capture-family key is known. */
    CAPTURE_FAMILY,

    /**
     * Only THIS file may be promised. Two different causes reach here, and the second is easy to
     * forget: either the row has no provable family identity (a legacy filename, deliberately
     * grouped alone), OR the family key IS proven but at least one row in it is no longer owned by
     * this package, so the delete would silently fail per row. Display is unaffected in both cases.
     */
    FILE_ONLY,
}

/** Android-free projection of one owned MediaStore row. */
internal data class StoredMediaRow<T>(
    val output: T,
    val collection: StoredMediaCollection,
    val rowId: Long,
    val displayName: String?,
    val mimeType: String?,
    val dateTakenEpochMillis: Long?,
    val dateAddedEpochSeconds: Long,
    val dateModifiedEpochSeconds: Long,
    val isPending: Boolean,
    /** False models a row that disappeared between query/reduction and is ignored safely. */
    val isPresent: Boolean = true,
    /**
     * Whether MediaStore still credits THIS package as the row's owner.
     *
     * False is the normal state for a file this app really did write in a PREVIOUS install:
     * Android clears `OWNER_PACKAGE_NAME` when the owning package is uninstalled, so every
     * reinstall or debug/release swap orphans that build's rows permanently. Such a row is still
     * considered ours only when its filename, collection, extension, and MIME match an explicit
     * historical/current save contract. Such a row is restorable for DISPLAY, but it can no longer
     * be deleted without a system consent flow, so it may never carry a capture-family delete
     * promise (see [restoreLatestCapture]).
     */
    val isOwned: Boolean = true,
)

internal data class RestoredCaptureOutput<T>(
    val output: T,
    val kind: StoredMediaOutputKind,
    val displayName: String?,
)

/** The newest capture, with review preference applied only among that capture's siblings. */
internal data class RestoredCapture<T>(
    val preferred: RestoredCaptureOutput<T>,
    val outputs: List<RestoredCaptureOutput<T>>,
    val familyKey: CaptureFamilyKey?,
    val deleteScope: RestoredDeleteScope,
)

private val rawMimeTypes = setOf(
    "image/x-adobe-dng",
    "image/dng",
    "application/x-adobe-dng",
)

private val heifMimeTypes = setOf("image/heic", "image/heif")
private const val jpegMimeType = "image/jpeg"
private const val videoMimeType = "video/mp4"

/**
 * Provider-side coarse allow-list for owner-cleared rows.
 *
 * The GLOBs deliberately mirror only filename shapes TeleCam has emitted. SQLite GLOB cannot
 * validate numeric ranges, so [isRestorableStoredMediaRow] repeats the check exactly after the
 * provider returns each row. Keeping the MIME alongside each pattern prevents a null-owner file
 * from entering the native review path merely by borrowing a TeleCam-looking suffix.
 */
internal data class NullOwnerRestoreQueryRule(
    val displayNameGlob: String,
    val mimeType: String,
)

internal data class RestoreOwnerQueryPolicy(
    val selection: String,
    val selectionArgs: List<String>,
)

internal fun nullOwnerRestoreQueryRules(
    collection: StoredMediaCollection,
): List<NullOwnerRestoreQueryRule> {
    val mediaPrefix = when (collection) {
        StoredMediaCollection.IMAGE -> "IMG"
        StoredMediaCollection.VIDEO -> "VID"
    }
    val digit = "[0-9]"
    val stems = listOf(
        "${mediaPrefix}_TELECAM_F1_${digit.repeat(13)}_${digit.repeat(10)}",
        "${mediaPrefix}_TELECAM_${digit.repeat(8)}_${digit.repeat(6)}_" +
            "${digit.repeat(3)}_${digit.repeat(3)}",
    )
    val extensionMimes = when (collection) {
        StoredMediaCollection.IMAGE -> listOf(
            "heic" to heifMimeTypes,
            "heif" to heifMimeTypes,
            "jpg" to setOf(jpegMimeType),
            "jpeg" to setOf(jpegMimeType),
            "dng" to rawMimeTypes,
        )
        StoredMediaCollection.VIDEO -> listOf("mp4" to setOf(videoMimeType))
    }
    return buildList {
        stems.forEach { stem ->
            extensionMimes.forEach { (extension, mimeTypes) ->
                mimeTypes.forEach { mimeType ->
                    add(NullOwnerRestoreQueryRule("$stem.$extension", mimeType))
                }
            }
        }
    }
}

/** Pure SQL fragment used by the MediaStore provider query and pinned by host tests. */
internal fun restoreOwnerQueryPolicy(
    collection: StoredMediaCollection,
    packageName: String,
    ownerColumn: String,
    displayNameColumn: String,
    mimeTypeColumn: String,
): RestoreOwnerQueryPolicy {
    val rules = nullOwnerRestoreQueryRules(collection)
    val nullOwnerSelection = rules.joinToString(" OR ", "(", ")") {
        "($displayNameColumn GLOB ? AND $mimeTypeColumn = ?)"
    }
    return RestoreOwnerQueryPolicy(
        selection = "($ownerColumn = ? OR " +
            "($ownerColumn IS NULL AND $nullOwnerSelection))",
        selectionArgs = buildList {
            add(packageName)
            rules.forEach { rule ->
                add(rule.displayNameGlob)
                add(rule.mimeType)
            }
        },
    )
}

private data class RecognizedTeleCamFile(
    val media: CaptureFamilyMedia,
    val extension: String,
)

private val legacyFileName = Regex(
    "^(IMG|VID)_TELECAM_([0-9]{8}_[0-9]{6}_[0-9]{3})_([0-9]{3})\\.([a-z0-9]+)$",
)
private val legacyTimestamp = DateTimeFormatter
    .ofPattern("uuuuMMdd_HHmmss_SSS", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

/**
 * Trust boundary for rows whose MediaStore owner was cleared by uninstall.
 *
 * Current-package rows are admitted without a filename restriction because MediaStore ownership is
 * authoritative. Owner-cleared rows need a current or historical TeleCam filename AND a collection,
 * extension, and MIME combination that the corresponding save lane could actually have emitted.
 */
internal fun <T> isRestorableStoredMediaRow(row: StoredMediaRow<T>): Boolean {
    if (row.isOwned) return true
    val file = recognizedTeleCamFile(row.displayName) ?: return false
    if (!file.media.matches(row.collection)) return false
    val mimeType = row.mimeType ?: return false
    return when (row.collection) {
        StoredMediaCollection.IMAGE -> when (file.extension) {
            "heic", "heif" -> mimeType in heifMimeTypes
            "jpg", "jpeg" -> mimeType == jpegMimeType
            "dng" -> mimeType in rawMimeTypes
            else -> false
        }
        StoredMediaCollection.VIDEO -> file.extension == "mp4" && mimeType == videoMimeType
    }
}

private fun recognizedTeleCamFile(displayName: String?): RecognizedTeleCamFile? {
    CaptureFamilyKey.parse(displayName)?.let {
        return RecognizedTeleCamFile(it.familyKey.media, it.extension)
    }
    val match = displayName?.let(legacyFileName::matchEntire) ?: return null
    runCatching { LocalDateTime.parse(match.groupValues[2], legacyTimestamp) }.getOrNull()
        ?: return null
    val media = when (match.groupValues[1]) {
        "IMG" -> CaptureFamilyMedia.STILL
        "VID" -> CaptureFamilyMedia.VIDEO
        else -> return null
    }
    return RecognizedTeleCamFile(media, match.groupValues[4])
}

internal fun storedMediaOutputKind(
    collection: StoredMediaCollection,
    mimeType: String?,
    displayName: String?,
): StoredMediaOutputKind {
    val normalizedMime = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
    val extension = displayName
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
    return if (normalizedMime in rawMimeTypes || extension == "dng") {
        StoredMediaOutputKind.RAW
    } else {
        // Rows come only from Images/Video. Video and every non-RAW image are review-displayable.
        when (collection) {
            StoredMediaCollection.IMAGE,
            StoredMediaCollection.VIDEO,
            -> StoredMediaOutputKind.DISPLAYABLE
        }
    }
}

private sealed interface CaptureGroupIdentity {
    data class Proven(val key: CaptureFamilyKey) : CaptureGroupIdentity
    data class Legacy(val collection: StoredMediaCollection, val rowId: Long, val ordinal: Int) :
        CaptureGroupIdentity
}

private data class Candidate<T>(
    val row: StoredMediaRow<T>,
    val kind: StoredMediaOutputKind,
    val parsed: ParsedCaptureFile?,
)

private data class CaptureRank(
    val capturedAtEpochMillis: Long,
    val sequence: Long,
    val dateAddedEpochSeconds: Long,
    val dateModifiedEpochSeconds: Long,
    val rowId: Long,
    val collectionRank: Int,
    val stableName: String,
) : Comparable<CaptureRank> {
    override fun compareTo(other: CaptureRank): Int =
        compareValuesBy(
            this,
            other,
            CaptureRank::capturedAtEpochMillis,
            CaptureRank::sequence,
            CaptureRank::dateAddedEpochSeconds,
            CaptureRank::dateModifiedEpochSeconds,
            CaptureRank::rowId,
            CaptureRank::collectionRank,
            CaptureRank::stableName,
        )
}

/**
 * Selects the newest capture first, then selects a displayable sibling within only that family.
 *
 * Legacy rows deliberately form one-file groups. The reducer never groups by timestamp proximity,
 * so adjacent burst frames cannot become one destructive delete scope.
 */
internal fun <T> restoreLatestCapture(rows: Iterable<StoredMediaRow<T>>): RestoredCapture<T>? {
    val groups = LinkedHashMap<CaptureGroupIdentity, MutableList<Candidate<T>>>()
    rows.forEachIndexed { ordinal, row ->
        if (!row.isPresent || row.isPending || !isRestorableStoredMediaRow(row)) {
            return@forEachIndexed
        }
        val parsed = CaptureFamilyKey.parse(row.displayName)
            ?.takeIf { it.familyKey.media.matches(row.collection) }
        val identity = parsed?.let { CaptureGroupIdentity.Proven(it.familyKey) }
            ?: CaptureGroupIdentity.Legacy(row.collection, row.rowId, ordinal)
        groups.getOrPut(identity) { mutableListOf() }.add(
            Candidate(
                row = row,
                kind = storedMediaOutputKind(row.collection, row.mimeType, row.displayName),
                parsed = parsed,
            ),
        )
    }
    if (groups.isEmpty()) return null

    val winner = groups.maxWithOrNull(compareBy({ (identity, candidates) ->
        captureRank(identity, candidates)
    })) ?: return null
    val familyKey = (winner.key as? CaptureGroupIdentity.Proven)?.key
    val orderedOutputs = winner.value
        .sortedWith(
            compareByDescending<Candidate<T>> { it.kind == StoredMediaOutputKind.DISPLAYABLE }
                .thenByDescending(::displayPreference)
                .thenByDescending { it.row.dateAddedEpochSeconds }
                .thenByDescending { it.row.rowId },
        )
        .map { candidate ->
            RestoredCaptureOutput(
                output = candidate.row.output,
                kind = candidate.kind,
                displayName = candidate.row.displayName,
            )
        }
    // A proven family key normally earns capture-level deletion — but only while we still OWN every
    // row in it. After a reinstall MediaStore has cleared our ownership, so the same delete that
    // used to remove the whole family now fails per row; promising CAPTURE_FAMILY there would show
    // whole-capture delete copy for something we cannot actually remove. Display is unaffected.
    val ownsWholeFamily = winner.value.all { it.row.isOwned }
    return RestoredCapture(
        preferred = orderedOutputs.first(),
        outputs = orderedOutputs,
        familyKey = familyKey,
        deleteScope = if (familyKey != null && ownsWholeFamily) {
            RestoredDeleteScope.CAPTURE_FAMILY
        } else {
            RestoredDeleteScope.FILE_ONLY
        },
    )
}

/**
 * Merges the independent Images and Video query results without making either collection a
 * prerequisite. Rows from every successful query participate in one canonical capture reduction;
 * a failed collection contributes no rows, and null means the available union had no usable owner.
 */
internal fun <T> restoreLatestCaptureFromQueryResults(
    imageRows: Result<List<StoredMediaRow<T>>>,
    videoRows: Result<List<StoredMediaRow<T>>>,
): RestoredCapture<T>? = restoreLatestCapture(
    buildList {
        imageRows.getOrNull()?.let(::addAll)
        videoRows.getOrNull()?.let(::addAll)
    },
)

private fun CaptureFamilyMedia.matches(collection: StoredMediaCollection): Boolean = when (this) {
    CaptureFamilyMedia.STILL -> collection == StoredMediaCollection.IMAGE
    CaptureFamilyMedia.VIDEO -> collection == StoredMediaCollection.VIDEO
}

private fun <T> captureRank(
    identity: CaptureGroupIdentity,
    candidates: List<Candidate<T>>,
): CaptureRank {
    val newestRow = candidates.maxWithOrNull(
        compareBy<Candidate<T>>(
            { fallbackCaptureMillis(it.row) },
            { it.row.dateAddedEpochSeconds },
            { it.row.dateModifiedEpochSeconds },
            { it.row.rowId },
            { it.row.collection.ordinal },
            { it.row.displayName.orEmpty() },
        ),
    ) ?: error("capture group must not be empty")
    val familyKey = (identity as? CaptureGroupIdentity.Proven)?.key
    return CaptureRank(
        capturedAtEpochMillis = familyKey?.capturedAtEpochMillis
            ?: fallbackCaptureMillis(newestRow.row),
        sequence = familyKey?.sequence ?: 0L,
        dateAddedEpochSeconds = newestRow.row.dateAddedEpochSeconds,
        dateModifiedEpochSeconds = newestRow.row.dateModifiedEpochSeconds,
        rowId = newestRow.row.rowId,
        collectionRank = newestRow.row.collection.ordinal,
        stableName = newestRow.row.displayName.orEmpty(),
    )
}

private fun <T> fallbackCaptureMillis(row: StoredMediaRow<T>): Long =
    row.dateTakenEpochMillis?.takeIf { it > 0L }
        ?: row.dateAddedEpochSeconds.takeIf { it > 0L }?.times(1_000L)
        ?: row.dateModifiedEpochSeconds.coerceAtLeast(0L) * 1_000L

private fun <T> displayPreference(candidate: Candidate<T>): Int = when {
    candidate.kind == StoredMediaOutputKind.RAW -> 0
    candidate.parsed?.extension == "heic" -> 4
    candidate.parsed?.extension == "heif" -> 3
    candidate.parsed?.extension == "jpg" || candidate.parsed?.extension == "jpeg" -> 2
    candidate.row.collection == StoredMediaCollection.VIDEO -> 1
    else -> 1
}
