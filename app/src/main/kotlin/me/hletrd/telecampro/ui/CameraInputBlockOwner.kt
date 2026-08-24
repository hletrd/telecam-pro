package me.hletrd.telecampro.ui

/** Independent full-screen owners; releasing one must never release another. */
internal enum class CameraInputBlockOwner {
    COMPOSE_MODAL,
    REVIEW,
    MICROPHONE_PERMISSION,
    MEDIA_PERMISSION,
    CAMERA_PERMISSION,
    CAMERA_POLICY,
    OWNERLESS_DELETE,
}

/** Pure immutable reducer used by the ViewModel's synchronized owner set and host tests. */
internal fun cameraInputBlockOwnersAfter(
    current: Set<CameraInputBlockOwner>,
    owner: CameraInputBlockOwner,
    blocked: Boolean,
): Set<CameraInputBlockOwner> = when {
    blocked && owner !in current -> current + owner
    !blocked && owner in current -> current - owner
    else -> current
}
