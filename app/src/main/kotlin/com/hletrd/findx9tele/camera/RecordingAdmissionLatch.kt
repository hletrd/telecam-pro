package com.hletrd.findx9tele.camera

/**
 * The REC start/stop admission latch, extracted so its exactly-once ordering is unit-testable.
 *
 * Admission runs asynchronously on the recorder executor (~100-700 ms); the UI stays stoppable in
 * that window. A stop arriving mid-admission must be LATCHED and executed on the same serial
 * executor the moment the recorder publishes — never raced against an unpublished owner (which
 * would silently stop nothing while the queued admission then starts an unwanted recording).
 *
 * All three transitions share one monitor, so a stop can never slip between an admission's
 * completion check and its latch consumption.
 */
internal class RecordingAdmissionLatch {
    private var inFlight = false
    private var stopRequested = false

    /** Claims the single admission slot; false while another admission is already in flight. */
    @Synchronized
    fun tryBeginAdmission(): Boolean {
        if (inFlight) return false
        inFlight = true
        stopRequested = false
        return true
    }

    /**
     * Latches a stop while an admission is in flight (true) — the admission completion runs it.
     * False = no admission in flight; the caller stops the published recorder normally.
     */
    @Synchronized
    fun requestStop(): Boolean {
        if (!inFlight) return false
        stopRequested = true
        return true
    }

    /**
     * Ends the admission (including executor-rejection abandonment via `succeeded = false`).
     * Returns true when a latched stop must run NOW — only for a successful admission; a failed
     * one has nothing to stop, and the latch is consumed either way (exactly-once).
     */
    @Synchronized
    fun completeAdmission(succeeded: Boolean): Boolean {
        inFlight = false
        val latched = stopRequested
        stopRequested = false
        return latched && succeeded
    }
}
