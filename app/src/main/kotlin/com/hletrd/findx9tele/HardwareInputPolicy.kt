package com.hletrd.findx9tele

internal enum class CameraKeyEdge { DOWN, REPEAT, UP }

internal data class CameraKeyDecision(
    val consume: Boolean,
    val start: Boolean = false,
    val release: Boolean = false,
    val ownAfter: Boolean = false,
)

/**
 * Pairs camera-key edges across modal changes. Once this Activity consumes DOWN, it also owns every
 * repeat and the matching UP; an unowned key passes to Android whenever camera input is unavailable.
 */
internal fun cameraKeyDecision(
    hasCameraPermission: Boolean,
    cameraInputBlocked: Boolean,
    alreadyOwned: Boolean,
    edge: CameraKeyEdge,
): CameraKeyDecision {
    if (alreadyOwned) {
        return if (edge == CameraKeyEdge.UP) {
            CameraKeyDecision(consume = true, release = true, ownAfter = false)
        } else {
            CameraKeyDecision(consume = true, ownAfter = true)
        }
    }
    if (!hasCameraPermission || cameraInputBlocked || edge != CameraKeyEdge.DOWN) {
        return CameraKeyDecision(consume = false)
    }
    return CameraKeyDecision(consume = true, start = true, ownAfter = true)
}
