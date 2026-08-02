package me.hletrd.telecampro.video

/**
 * Same-aspect fallback sizes for the MediaCodec input buffer, largest first.
 *
 * WHY THIS EXISTS (device-probed on an Android 13 emulator, 2026-08-02). Since cycle 4 the encoder
 * buffer takes [me.hletrd.telecampro.camera.RotationMath.encoderSurfaceSize], which SWAPS the
 * camera stream's landscape shape to portrait so the recorded field matches the viewfinder. Nothing
 * ever asked the ENCODER whether it can take the swapped shape. The PMA110's QTI encoder takes
 * both, so this was invisible — but the AOSP software HEVC encoder (`c2.android.hevc.encoder`, the
 * only HEVC encoder on a device with no hardware one) caps HEIGHT well below width:
 *
 *   1280x720 PASS   1920x1080 PASS   720x720 PASS   480x854 PASS   360x640 PASS
 *   720x1280 FAIL   1080x1920 FAIL
 *
 * Every recording on such a device failed at `MediaCodec.configure` with BAD_VALUE — no crash and
 * no orphan file (the recorder's failure path is sound), but also no clip, with nothing on screen
 * explaining why.
 *
 * A capability query CANNOT fix this: that same encoder advertises `supportedWidths=[2,512]` and
 * answers `isSizeSupported(1280, 720) = false` for a size it demonstrably encodes. The declared
 * caps are wrong in both directions, so the only honest oracle is an actual `configure` attempt —
 * which is exactly the fallback-ladder shape `CameraController.configureSession` already uses for
 * capture sessions.
 *
 * ASPECT IS PRESERVED, resolution is what gets spent. Falling back to the unswapped LANDSCAPE
 * buffer would "work" and silently re-introduce the cycle-4 defect it was built to fix (a
 * portrait-content draw into a landscape buffer overscans ~3.16x and records a centre band), so
 * that is deliberately not a rung here.
 *
 * Scales are chosen to divide the common video heights exactly wherever possible, and each result
 * is rounded to an even number: the probed encoder advertises 2x2 alignment, and even dimensions
 * are the floor for 4:2:0 chroma. On a device whose encoder accepts the requested size — every
 * verified handset so far, PMA110 included — only the first rung is ever attempted, so this is
 * inert there.
 */
internal fun encoderSizeLadder(width: Int, height: Int): List<Pair<Int, Int>> {
    if (width <= 0 || height <= 0) return emptyList()
    val scales = listOf(1.0, 0.75, 2.0 / 3.0, 0.5, 1.0 / 3.0, 0.25)
    return scales
        .map { s -> even((width * s).toInt()) to even((height * s).toInt()) }
        .filter { (w, h) -> w >= MIN_ENCODER_EDGE && h >= MIN_ENCODER_EDGE }
        .distinct()
}

/** Rounds down to an even value; 4:2:0 chroma cannot express an odd edge. */
private fun even(v: Int): Int = v - (v % 2)

/**
 * Below this the clip stops being worth writing. A device that cannot encode even this in the
 * displayed aspect should fail the take honestly rather than save a thumbnail-sized "video".
 */
internal const val MIN_ENCODER_EDGE = 160
