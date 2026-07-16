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

    /** Freezes all currently-known siblings and tombstones their capture before async deletion. */
    @Synchronized
    fun takeForDelete(output: T): Set<T> {
        val captureId = captureByOutput[output] ?: return setOf(output)
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
        while (outputsByCapture.size > maxCaptureHistory.coerceAtLeast(1)) {
            // Callback order can differ from capture order (DNG and processed encoders use different
            // workers), so evict by monotonic id rather than LinkedHashMap insertion order.
            val oldestCaptureId = outputsByCapture.keys.minOrNull() ?: return
            val evicted = outputsByCapture.remove(oldestCaptureId).orEmpty()
            evicted.forEach(captureByOutput::remove)
        }
    }
}
