package me.hletrd.telecampro

import java.util.concurrent.atomic.AtomicReference

/** Debug-only command payload; release has no exported producer for this process-local mailbox. */
internal data class DebugCameraControlCommand(
    val zslSpike: Boolean? = null,
    val zoomRatio: Float? = null,
    /** Wanted REC state: true starts a Video take, false stops it; null leaves REC alone. */
    val record: Boolean? = null,
    /** Photo shutter presses still to deliver. A COUNT, so presses merged before consumption all fire. */
    val shutterPresses: Int = 0,
    /** Explicitly enter/leave the standalone TC + 0x80b5 maker-video probe; null leaves admission alone. */
    val standaloneMakerVideo: Boolean? = null,
) {
    val isEmpty: Boolean get() = zslSpike == null && zoomRatio == null && record == null && shutterPresses == 0 && standaloneMakerVideo == null

    fun mergedWith(newer: DebugCameraControlCommand): DebugCameraControlCommand =
        DebugCameraControlCommand(
            zslSpike = newer.zslSpike ?: zslSpike,
            zoomRatio = newer.zoomRatio ?: zoomRatio,
            record = newer.record ?: record,
            shutterPresses = shutterPresses + newer.shutterPresses,
            standaloneMakerVideo = newer.standaloneMakerVideo ?: standaloneMakerVideo,
        )
}

/**
 * Holds protected-harness followups across a probe-triggered reopen. A stale Ready must not release
 * zoom/REC against the outgoing route's caps: after a deferral from Ready, observe Not-Ready first,
 * then the next accepted Ready. Main-thread only, like the Activity mailbox consumer.
 */
internal class DebugCameraCommandDeferral {
    private var pending: DebugCameraControlCommand? = null
    private var barrierArmed = false
    private var waitingForNotReady = false

    /** True even for a probe-only reopen whose followup has not arrived yet. */
    val hasPending: Boolean get() = barrierArmed

    fun defer(command: DebugCameraControlCommand, currentlyReady: Boolean) {
        if (!barrierArmed) {
            barrierArmed = true
            waitingForNotReady = currentlyReady
        }
        if (command.isEmpty) return
        pending = pending?.mergedWith(command) ?: command
    }

    fun onCameraReady(ready: Boolean): DebugCameraControlCommand? {
        if (!barrierArmed) return null
        if (!ready) {
            waitingForNotReady = false
            return null
        }
        if (waitingForNotReady) return null
        val command = pending
        pending = null
        barrierArmed = false
        waitingForNotReady = false
        return command
    }

    fun clear() {
        pending = null
        barrierArmed = false
        waitingForNotReady = false
    }
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

