package com.hletrd.findx9tele.storage

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

    /** The legacy row has no provable family identity; only this file may be promised. */
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
        if (!row.isPresent || row.isPending) return@forEachIndexed
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
    return RestoredCapture(
        preferred = orderedOutputs.first(),
        outputs = orderedOutputs,
        familyKey = familyKey,
        deleteScope = if (familyKey != null) {
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
