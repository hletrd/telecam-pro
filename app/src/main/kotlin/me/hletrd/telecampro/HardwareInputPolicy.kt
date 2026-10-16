package me.hletrd.telecampro

import me.hletrd.telecampro.camera.HardwareKeyAction

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

/**
 * Applies one key's ownership and returns the aggregate active state only when it changes. Multiple
 * physical keys can represent the same camera action (both volume keys, CAMERA plus volume, or the
 * two half-press aliases); callers must not turn each individual edge into another shutter/REC edge.
 */
internal fun updateAggregateCameraKeyOwnership(
    ownedKeys: MutableSet<Int>,
    keyCode: Int,
    ownedAfter: Boolean,
): Boolean? {
    val wasActive = ownedKeys.isNotEmpty()
    if (ownedAfter) ownedKeys += keyCode else ownedKeys -= keyCode
    val isActive = ownedKeys.isNotEmpty()
    return isActive.takeIf { it != wasActive }
}

/**
 * Physical keys keep owning their DOWN/UP pair while an unavailable shutter stays inert.
 * Non-shutter assignments retain their own live-control policy.
 */
internal fun hardwareActionAdmitted(
    action: HardwareKeyAction,
    primaryShutterEnabled: Boolean,
): Boolean = action != HardwareKeyAction.SHUTTER || primaryShutterEnabled
