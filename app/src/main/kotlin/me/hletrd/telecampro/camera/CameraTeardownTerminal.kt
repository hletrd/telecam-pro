package me.hletrd.telecampro.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Terminal proof returned by [CameraController.close]. */
internal enum class CameraControllerCloseResult {
    /** The camera-handler teardown completed every owned Camera2/readers release operation. */
    STRICTLY_RELEASED,

    /** Release was not proved before the deadline; native acquisition is refused until restart. */
    QUARANTINED,
}

/**
 * Exactly-once classification for one CameraController teardown.
 *
 * A timeout is not a weak success: it first closes process native admission through [quarantine],
 * then publishes [CameraControllerCloseResult.QUARANTINED]. A late handler completion cannot turn
 * that terminal back into a strict release or authorize a replacement graph.
 */
internal class CameraTeardownTerminal(
    private val onQuarantine: () -> Unit,
) {
    private val completed = CountDownLatch(1)

    @Volatile
    private var result: CameraControllerCloseResult? = null

    fun strictlyReleased(): CameraControllerCloseResult = classify(
        CameraControllerCloseResult.STRICTLY_RELEASED,
    )

    fun quarantine(): CameraControllerCloseResult = classify(
        CameraControllerCloseResult.QUARANTINED,
    )

    fun await(timeout: Long, unit: TimeUnit): CameraControllerCloseResult {
        val finished = try {
            completed.await(timeout.coerceAtLeast(0L), unit)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return if (finished) {
            checkNotNull(result)
        } else {
            quarantine()
        }
    }

    private fun classify(candidate: CameraControllerCloseResult): CameraControllerCloseResult =
        synchronized(this) {
            result?.let { return@synchronized it }
            if (candidate == CameraControllerCloseResult.QUARANTINED) onQuarantine()
            result = candidate
            completed.countDown()
            candidate
        }
}

/** Only a proved release (or no outgoing controller) can authorize replacement acquisition. */
internal fun cameraReplacementMayAcquire(result: CameraControllerCloseResult?): Boolean =
    result == null || result == CameraControllerCloseResult.STRICTLY_RELEASED
