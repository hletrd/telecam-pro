package com.hletrd.findx9tele.ui

internal enum class CaptureOutputKind {
    DISPLAYABLE,
    RAW,
}

internal enum class CaptureOutputDecision {
    /** The capture was already deleted; this late output must be deleted immediately. */
    DELETE,

    /** Keep the output as a deletion sibling, but do not replace the current review owner. */
    TRACK_ONLY,

    /** This output is now the truthful owner of the last-capture review entry. */
    REVIEW,
}

internal data class PriorCaptureOutput<T>(
    val output: T,
    val kind: CaptureOutputKind,
)

/**
 * Bounded ownership map for every file emitted by one capture. Deleted capture ids are tombstoned
 * so a slower sibling that arrives after review deletion is rejected immediately.
 *
 * Capture ids are monotonic within one engine lifetime. The newest capture always owns review even
 * when its only successful output is RAW; a displayable sibling upgrades RAW for that same capture,
 * while RAW can never displace an existing displayable sibling or any newer capture.
 */
internal class CaptureOutputTracker<T>(
    private val maxCaptureHistory: Int,
    private val maxTombstones: Int = maxCaptureHistory,
) {
    private val outputsByCapture = LinkedHashMap<Int, LinkedHashSet<T>>()
    private val captureByOutput = HashMap<T, Int>()
    private val tombstones = LinkedHashSet<Int>()
    private var reviewCaptureId: Int? = null
    private var reviewOutput: T? = null
    private var reviewKind: CaptureOutputKind? = null
    private var pinnedReviewCaptureId: Int? = null
    private var pinnedReviewOutput: T? = null

    /**
     * Seeds a fully reconstructed prior-process family without letting it outrank live callbacks.
     * Returns true only when [preferredOutput] became the current review owner.
     */
    @Synchronized
    fun seedPriorCapture(
        outputs: Collection<PriorCaptureOutput<T>>,
        preferredOutput: T,
    ): Boolean {
        if (PRIOR_PROCESS_CAPTURE_ID in tombstones) return false
        val distinct = LinkedHashMap<T, CaptureOutputKind>()
        outputs.forEach { seeded -> distinct.putIfAbsent(seeded.output, seeded.kind) }
        val preferredKind = distinct[preferredOutput] ?: return false

        val accepted = distinct.filterKeys { output ->
            captureByOutput[output].let { it == null || it == PRIOR_PROCESS_CAPTURE_ID }
        }
        if (preferredOutput !in accepted) return false

        // A launch seed is expected once, but replacement is deterministic and cannot merge two
        // prior families if a caller retries restoration.
        outputsByCapture.remove(PRIOR_PROCESS_CAPTURE_ID).orEmpty().forEach { output ->
            if (captureByOutput[output] == PRIOR_PROCESS_CAPTURE_ID) captureByOutput.remove(output)
        }
        outputsByCapture[PRIOR_PROCESS_CAPTURE_ID] = LinkedHashSet(accepted.keys)
        accepted.keys.forEach { captureByOutput[it] = PRIOR_PROCESS_CAPTURE_ID }
        trimCaptures()
        if (PRIOR_PROCESS_CAPTURE_ID !in outputsByCapture) return false

        if (reviewCaptureId == null || reviewCaptureId == PRIOR_PROCESS_CAPTURE_ID) {
            reviewCaptureId = PRIOR_PROCESS_CAPTURE_ID
            reviewOutput = preferredOutput
            reviewKind = preferredKind
        }
        return reviewCaptureId == PRIOR_PROCESS_CAPTURE_ID && reviewOutput == preferredOutput
    }

    /** Records [output] and returns its deletion/review ownership decision. */
    @Synchronized
    fun record(
        captureId: Int,
        output: T,
        kind: CaptureOutputKind,
    ): CaptureOutputDecision {
        if (captureId in tombstones) return CaptureOutputDecision.DELETE
        outputsByCapture.getOrPut(captureId) { linkedSetOf() }.add(output)
        captureByOutput[output] = captureId
        trimCaptures()
        // Trimming can evict the capture that was JUST added — a late sibling of an old id arriving
        // while the ordinary history is full (reachable right after deleting a pinned review while
        // newer ids hold every ordinary slot). An evicted capture must never become the review
        // owner: the UI would publish a URI whose family/reverse mapping no longer exists,
        // pinForReview would fail, and delete would silently degrade to the displayed file only.
        // The file itself is untouched — it simply ages out of managed history like any other
        // older-than-history capture.
        if (captureByOutput[output] != captureId) return CaptureOutputDecision.TRACK_ONLY

        val currentCaptureId = reviewCaptureId
        val ownsReview = when {
            currentCaptureId == null -> true
            captureId > currentCaptureId -> true
            captureId < currentCaptureId -> false
            kind == CaptureOutputKind.DISPLAYABLE && reviewKind == CaptureOutputKind.RAW -> true
            else -> false
        }
        if (!ownsReview) return CaptureOutputDecision.TRACK_ONLY

        reviewCaptureId = captureId
        reviewOutput = output
        reviewKind = kind
        return CaptureOutputDecision.REVIEW
    }

    /** Rechecks a REVIEW decision after it crosses from tracker ownership into UI state. */
    @Synchronized
    fun isCurrentReviewOutput(output: T): Boolean = reviewOutput == output

    /**
     * Replaces the open-review pin with the exact family that owns [output]. A failed replacement
     * releases the old pin and returns false so callers can truthfully fall back to file-only copy.
     */
    @Synchronized
    fun pinForReview(output: T): Boolean {
        val captureId = captureByOutput[output]
            ?.takeIf { output in outputsByCapture[it].orEmpty() }
        pinnedReviewCaptureId = captureId
        pinnedReviewOutput = output.takeIf { captureId != null }
        trimCaptures()
        return captureId != null
    }

    /** Releases only the matching overlay's pin; a stale close cannot release a replacement pin. */
    @Synchronized
    fun releaseReviewPin(output: T) {
        if (pinnedReviewOutput != output) return
        pinnedReviewCaptureId = null
        pinnedReviewOutput = null
        trimCaptures()
    }

    /** Freezes all currently-known siblings and tombstones their capture before async deletion. */
    @Synchronized
    fun takeForDelete(output: T): Set<T> {
        val captureId = pinnedReviewCaptureId.takeIf { pinnedReviewOutput == output }
            ?: captureByOutput[output]
            ?: return setOf(output)
        if (pinnedReviewCaptureId == captureId) {
            pinnedReviewCaptureId = null
            pinnedReviewOutput = null
        }
        tombstones.remove(captureId)
        tombstones.add(captureId)
        while (tombstones.size > maxTombstones.coerceAtLeast(1)) {
            tombstones.remove(tombstones.first())
        }
        val owned = outputsByCapture.remove(captureId).orEmpty().toSet() + output
        owned.forEach(captureByOutput::remove)
        if (reviewCaptureId == captureId) {
            reviewCaptureId = null
            reviewOutput = null
            reviewKind = null
        }
        return owned
    }

    @Synchronized
    private fun trimCaptures() {
        val ordinaryLimit = maxCaptureHistory.coerceAtLeast(1)
        while (outputsByCapture.keys.count { it != pinnedReviewCaptureId } > ordinaryLimit) {
            // Callback order can differ from capture order (DNG and processed encoders use different
            // workers), so evict by monotonic id rather than LinkedHashMap insertion order. The one
            // open family is bounded separately and never consumes ordinary timelapse history.
            val oldestCaptureId = outputsByCapture.keys
                .asSequence()
                .filter { it != pinnedReviewCaptureId }
                .minOrNull() ?: return
            val evicted = outputsByCapture.remove(oldestCaptureId).orEmpty()
            evicted.forEach(captureByOutput::remove)
        }
    }

    private companion object {
        /** All live engine capture ids are non-negative, so even capture 0 supersedes this seed. */
        const val PRIOR_PROCESS_CAPTURE_ID = Int.MIN_VALUE
    }
}
