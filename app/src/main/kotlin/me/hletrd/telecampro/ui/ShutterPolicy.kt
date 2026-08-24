package me.hletrd.telecampro.ui

/**
 * Resolves one photo-shutter activation without Android timing dependencies.
 *
 * Cancellation deliberately has first refusal: once a countdown is visible, another shutter
 * activation cancels that countdown regardless of current session readiness or configured delay.
 */
internal fun dispatchPhotoShutter(
    timelapseRunning: Boolean = false,
    countdownSeconds: Int,
    stillCaptureReady: Boolean,
    configuredDelaySeconds: Int,
    stopTimelapse: () -> Unit = {},
    cancelCountdown: () -> Unit,
    fireShutter: () -> Unit,
    startCountdown: (Int) -> Unit,
) {
    when {
        // A running sequence is already-owned work; stopping it needs neither a Ready camera nor a
        // still target and must not fall through the capture-admission gate.
        timelapseRunning -> stopTimelapse()
        countdownSeconds > 0 -> cancelCountdown()
        !stillCaptureReady -> fireShutter()
        configuredDelaySeconds <= 0 -> fireShutter()
        else -> startCountdown(configuredDelaySeconds)
    }
}

/** Video snapshots ignore the Photo self-timer; the dedicated REC control is an immediate shutter. */
internal fun photoShutterDelaySeconds(configuredDelaySeconds: Int, recording: Boolean): Int =
    if (recording) 0 else configuredDelaySeconds
