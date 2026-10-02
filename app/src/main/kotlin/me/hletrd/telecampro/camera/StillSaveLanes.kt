package me.hletrd.telecampro.camera

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exactly-once terminal ownership of one still callback's save lanes (processed HEIF/JPEG and DNG).
 *
 * Each lane finishes once; the shot settles (producer-terminal mark, lease close, BURST/AEB/
 * timelapse continuation) exactly once, after every wanted lane finished.
 *
 * The Camera2 callback has two terminals, and onPhoto is the one that may HAND a lane off: a queued
 * processed save finishes its own lane from the io thread when the encode ends. The controller
 * still reports a throw out of onPhoto through onError (AGG6-22), which used to finish the
 * processed lane unconditionally — its CAS won, the shot settled while the HEIF/JPEG was still
 * being written, the producer lease closed under a live sibling (a concurrent delete could then
 * retire the durable family marker early), and the next BURST/AEB shot fired while this one still
 * held its full processed snapshot. Once onPhoto was entered, its own finally owns every lane it did
 * not hand off, so [claimErrorTerminal] refuses.
 */
internal class StillSaveLanes(
    wantsProcessed: Boolean,
    wantsDng: Boolean,
    private val onProcessedFinished: () -> Unit,
    private val settle: () -> Unit,
) {
    private val remaining = AtomicInteger((if (wantsProcessed) 1 else 0) + (if (wantsDng) 1 else 0))
    private val settled = AtomicBoolean(false)
    private val processedFinished = AtomicBoolean(!wantsProcessed)
    private val dngFinished = AtomicBoolean(!wantsDng)
    private val photoEntered = AtomicBoolean(false)

    /** Marks onPhoto delivery; from here its own finally owns every lane it does not hand off. */
    fun enterPhoto() {
        photoEntered.set(true)
    }

    /** True when onError owns the lanes, i.e. onPhoto never ran for this callback. */
    fun claimErrorTerminal(): Boolean = !photoEntered.get()

    fun finishProcessed() {
        if (processedFinished.compareAndSet(false, true)) {
            onProcessedFinished()
            if (remaining.decrementAndGet() == 0) finishSequence()
        }
    }

    fun finishDng() {
        if (dngFinished.compareAndSet(false, true) && remaining.decrementAndGet() == 0) {
            finishSequence()
        }
    }

    fun finishSequence() {
        if (remaining.get() == 0 && settled.compareAndSet(false, true)) settle()
    }
}
