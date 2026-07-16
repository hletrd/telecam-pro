package com.hletrd.findx9tele

internal enum class CameraPermissionDisposition {
    GRANTED,
    REQUESTABLE,
    SETTINGS_REQUIRED,
}

/**
 * Classifies camera access without treating a missing rationale as permanent denial. Android returns
 * false for both a never-requested permission and a fixed denial, so [requestedBefore] records an
 * actual false launcher result. A canceled request produces no result and does not change history.
 */
internal fun classifyCameraPermission(
    granted: Boolean,
    requestedBefore: Boolean,
    shouldShowRationale: Boolean,
): CameraPermissionDisposition = when {
    granted -> CameraPermissionDisposition.GRANTED
    shouldShowRationale -> CameraPermissionDisposition.REQUESTABLE
    requestedBefore -> CameraPermissionDisposition.SETTINGS_REQUIRED
    else -> CameraPermissionDisposition.REQUESTABLE
}

/** Updates durable request history; null means the permission dialog/request was canceled. */
internal fun updatedCameraPermissionRequestHistory(
    requestedBefore: Boolean,
    result: Boolean?,
): Boolean = when (result) {
    true -> false
    false -> true
    null -> requestedBefore
}
