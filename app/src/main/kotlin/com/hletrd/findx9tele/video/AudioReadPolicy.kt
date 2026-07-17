package com.hletrd.findx9tele.video

/** Android-free decision for one `AudioRecord.read` result. */
internal sealed interface AudioReadOutcome {
    data class Pcm(val byteCount: Int) : AudioReadOutcome
    data object Retry : AudioReadOutcome
    data object Stopped : AudioReadOutcome
    data class Failure(val code: Int) : AudioReadOutcome
}

/**
 * Classifies the read against the recording state observed after the potentially blocking call.
 * A stop request owns the race even when `AudioRecord.stop()` wakes the read with a negative code.
 */
internal fun classifyAudioRead(byteCount: Int, running: Boolean): AudioReadOutcome = when {
    !running -> AudioReadOutcome.Stopped
    byteCount > 0 -> AudioReadOutcome.Pcm(byteCount)
    byteCount == 0 -> AudioReadOutcome.Retry
    else -> AudioReadOutcome.Failure(byteCount)
}

/**
 * Standby-meter recreation policy after a terminal read error: recreate only while the bounded
 * budget of consecutive failed generations (no successful PCM read in any of them) is not
 * exhausted. Generation counting starts at 1 for the first failed owner.
 */
internal fun standbyMeterShouldRecreate(failedGenerations: Int, maxRecreates: Int): Boolean =
    failedGenerations in 1..maxRecreates

/** Builds the terminal failure retained by [FirstFailureSignal] for a running read error. */
internal fun audioReadFailure(code: Int): IllegalStateException =
    IllegalStateException("AudioRecord.read failed: ${audioReadErrorLabel(code)} ($code)")

// Public AudioRecord error-code values are mirrored here so the policy remains host-testable without
// loading Android framework classes. Every unknown negative code is still terminal while running.
private fun audioReadErrorLabel(code: Int): String = when (code) {
    -1 -> "ERROR"
    -2 -> "ERROR_BAD_VALUE"
    -3 -> "ERROR_INVALID_OPERATION"
    -6 -> "ERROR_DEAD_OBJECT"
    else -> "UNKNOWN_ERROR"
}
