package me.hletrd.telecampro

import java.util.concurrent.atomic.AtomicReference

/** Debug-only command payload; release has no exported producer for this process-local mailbox. */
internal data class DebugCameraControlCommand(
    val zslSpike: Boolean? = null,
    val zoomRatio: Float? = null,
    /** Wanted REC state: true starts a Video take, false stops it; null leaves REC alone. */
    val record: Boolean? = null,
) {
    val isEmpty: Boolean get() = zslSpike == null && zoomRatio == null && record == null

    fun mergedWith(newer: DebugCameraControlCommand): DebugCameraControlCommand =
        DebugCameraControlCommand(
            zslSpike = newer.zslSpike ?: zslSpike,
            zoomRatio = newer.zoomRatio ?: zoomRatio,
            record = newer.record ?: record,
        )
}

/**
 * Process-local bridge from the DUMP-protected debug component to the live launcher Activity.
 * Multiple shell commands merge until MainActivity consumes them, so a cold-start command cannot
 * be lost between the protected component's startActivity call and ViewModel construction.
 */
internal object DebugCameraControlMailbox {
    private val pending = AtomicReference<DebugCameraControlCommand?>(null)

    fun publish(command: DebugCameraControlCommand): Boolean {
        if (command.isEmpty) return false
        while (true) {
            val current = pending.get()
            val merged = current?.mergedWith(command) ?: command
            if (pending.compareAndSet(current, merged)) return true
        }
    }

    fun consume(): DebugCameraControlCommand? = pending.getAndSet(null)
}

