package me.hletrd.telecampro.gl

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Converts any number of producer notifications into one latest-frame draw at a time.
 *
 * [onFrameAvailable] is deliberately cheap: the first notification posts [drain], while later
 * notifications only mark the producer dirty. A notification arriving while [drawLatestFrame]
 * runs schedules one follow-up after that draw, so no real-frame edge is stranded and producer
 * backpressure cannot multiply full preview/encoder/analysis passes.
 */
internal class FrameNotificationCoalescer(
    private val post: (Runnable) -> Boolean,
    private val drawLatestFrame: () -> Unit,
) {
    private val active = AtomicBoolean(true)
    private val dirty = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)

    private val drain = Runnable {
        if (!active.get()) {
            dirty.set(false)
            scheduled.set(false)
            return@Runnable
        }
        dirty.set(false)
        try {
            drawLatestFrame()
        } finally {
            scheduled.set(false)
            if (active.get() && dirty.get()) arm()
        }
    }

    fun onFrameAvailable() {
        if (!active.get()) return
        dirty.set(true)
        arm()
    }

    fun cancel() {
        active.set(false)
        dirty.set(false)
    }

    private fun arm() {
        if (!active.get() || !scheduled.compareAndSet(false, true)) return
        val accepted = runCatching { post(drain) }.getOrDefault(false)
        if (!accepted) scheduled.set(false)
    }
}
