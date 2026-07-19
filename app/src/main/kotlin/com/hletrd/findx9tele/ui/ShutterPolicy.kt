package com.hletrd.findx9tele.ui

/**
 * Resolves one photo-shutter activation without Android timing dependencies.
 *
 * Cancellation deliberately has first refusal: once a countdown is visible, another shutter
 * activation cancels that countdown regardless of current session readiness or configured delay.
 */
internal fun dispatchPhotoShutter(
    countdownSeconds: Int,
    stillCaptureReady: Boolean,
    configuredDelaySeconds: Int,
    cancelCountdown: () -> Unit,
    fireShutter: () -> Unit,
    startCountdown: (Int) -> Unit,
) {
    when {
        countdownSeconds > 0 -> cancelCountdown()
        !stillCaptureReady -> fireShutter()
        configuredDelaySeconds <= 0 -> fireShutter()
        else -> startCountdown(configuredDelaySeconds)
    }
}
