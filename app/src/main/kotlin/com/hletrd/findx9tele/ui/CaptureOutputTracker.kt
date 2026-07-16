package com.hletrd.findx9tele.ui

/**
 * Bounded ownership map for every file emitted by one capture. Deleted capture ids are tombstoned
 * so a slower sibling (typically DNG) that arrives after review deletion is rejected immediately.
 */
internal class CaptureOutputTracker<T>(
    private val maxCaptureHistory: Int,
    private val maxTombstones: Int = maxCaptureHistory,
) {
    private val outputsByCapture = LinkedHashMap<Int, LinkedHashSet<T>>()
    private val captureByOutput = HashMap<T, Int>()
    private val tombstones = LinkedHashSet<Int>()

    /** Returns true when [output] belongs to a capture already deleted and must be deleted now. */
    @Synchronized
    fun record(captureId: Int, output: T): Boolean {
        if (captureId in tombstones) return true
        outputsByCapture.getOrPut(captureId) { linkedSetOf() }.add(output)
        captureByOutput[output] = captureId
        trimCaptures()
        return false
    }

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
        return owned
    }

    @Synchronized
    private fun trimCaptures() {
        while (outputsByCapture.size > maxCaptureHistory.coerceAtLeast(1)) {
            val (_, evicted) = outputsByCapture.entries.first()
            outputsByCapture.remove(outputsByCapture.keys.first())
            evicted.forEach(captureByOutput::remove)
        }
    }
}
