package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import androidx.compose.runtime.Immutable

/*
 * Face Priority AF (owner request 2026-10-14). Everything Android-type-free lives here so the gate,
 * the request decision table, the readout throttle and the sensor→view geometry are host-tested.
 *
 * The HAL does the work: with CONTROL_MODE = USE_SCENE_MODE + CONTROL_SCENE_MODE = FACE_PRIORITY
 * the camera device itself steers AF (and, where AE/AWB are ON, AE/AWB) toward the faces it
 * detects. The app NEVER writes per-frame AF/AE regions from face results: every repeating-request
 * swap stalls this HAL's preview ~180 ms (CLAUDE.md), so steering from the app would turn a face in
 * motion into a slideshow. Face results are read only to DRAW the frames.
 *
 * Spec facts this relies on (android.hardware.camera2 CameraMetadata, checked 2026-10-14):
 *  - CONTROL_MODE_USE_SCENE_MODE disables aeMode/awbMode/afMode "except for FACE_PRIORITY scene
 *    mode", and CONTROL_SCENE_MODE_FACE_PRIORITY says "Unlike the other scene modes, aeMode,
 *    awbMode, and afMode remain active when FACE_PRIORITY is set". The app's AE_MODE_OFF (manual
 *    and app-side exposure, the PMA110 photo default) therefore stays OFF and SENSOR_EXPOSURE_TIME /
 *    SENSOR_SENSITIVITY stay in force ("only effective if aeMode or mode is OFF" — aeMode is).
 *    Face priority is applied in AE-OFF modes too; the decision table pins that the AE axis does
 *    not change the keys, and docs/FIELD_CHECKS.md A16 carries the on-device confirmation.
 *  - Face rectangles use the same post-zoom active-array coordinate system as AE/AF regions
 *    (CONTROL_ZOOM_RATIO: "The same applies to AE/AWB/AF regions, and faces"), so the inverse of
 *    the tap → metering-region mapping places them on the preview.
 */

/** Camera2 facts for the gate, flattened so the host can test sparse matrices. */
internal data class FaceCapability(
    val faceDetectModes: IntArray,
    val maxFaceCount: Int,
    val sceneModes: IntArray,
)

/**
 * The face-detect mode the request carries: SIMPLE (rects + scores — all the frames need) when
 * advertised, else FULL, else null. OFF is never "best".
 */
internal fun preferredFaceDetectMode(faceDetectModes: IntArray): Int? = when {
    CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE in faceDetectModes ->
        CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
    CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL in faceDetectModes ->
        CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
    else -> null
}

/**
 * Available iff the route advertises a face-detect mode other than OFF, a positive face count, AND
 * the FACE_PRIORITY scene mode. PMA110 advertises [OFF, SIMPLE] / 10 / FACE_PRIORITY on every
 * camera (dumpsys 2026-10-14); anything else is a capability fact of that route, not a model check.
 */
internal fun facePriorityAfAvailable(cap: FaceCapability): Boolean =
    preferredFaceDetectMode(cap.faceDetectModes) != null && cap.maxFaceCount > 0 &&
        CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY in cap.sceneModes

/** Focus modes face priority steers: the two AF modes. MANUAL owns the lens; MACRO is a near band. */
internal fun facePriorityFocusMode(focusMode: FocusMode): Boolean =
    focusMode == FocusMode.AUTO || focusMode == FocusMode.CONTINUOUS

/**
 * What the toggle actually does on this route right now — the value the settings row shows and
 * the overlay gate reads. The persisted preference itself is NOT normalized away on a route without
 * support (a trip through such a route must not silently turn the operator's choice off).
 */
internal fun facePriorityAfEffective(enabled: Boolean, cap: FaceCapability?, focusMode: FocusMode): Boolean =
    enabled && cap != null && facePriorityAfAvailable(cap) && facePriorityFocusMode(focusMode)

/**
 * The keys one request carries. Null = write NOTHING (setting off, unsupported route, or a non-AF
 * focus mode): the template defaults stay on the wire, so the default-OFF state is byte-identical to
 * the pre-feature request. Every input that can make this null is a ManualControls/route change,
 * which always builds a FRESH template request, so a cached fast-path builder can never carry stale
 * face keys across a null edge.
 *
 * Non-null always writes BOTH halves explicitly. A held tap-AF or AF Lock ([afOverrideActive]) keeps
 * its historical precedence exactly as [afOverrideFor] resolves it: the scene mode drops back to
 * CONTROL_MODE_AUTO / SCENE_MODE_DISABLED (the HAL must not re-aim AF at a face over the operator's
 * tap or lock) while detection keeps running so the frames stay drawn. Writing the AUTO half
 * explicitly matters on the fast path, where the override is re-applied onto a cached builder.
 *
 * Exposure mode is deliberately NOT consulted: FACE_PRIORITY keeps aeMode active by spec (see the
 * file header), so AE-OFF packets (S/ISO/M and app-side Program) get the same keys as HAL-AE ones.
 * The whole packet is the input so the decision table can pin that independence.
 */
internal data class FacePriorityKeys(val faceDetectMode: Int, val sceneModeFacePriority: Boolean)

internal fun facePriorityKeysFor(
    controls: ManualControls,
    cap: FaceCapability,
    afOverrideActive: Boolean,
    // False where the scene half must stand down for THIS request: a measured HAL whose
    // FACE_PRIORITY scene overrides an app-owned white balance on stills
    // ([DeviceProfile.facePrioritySceneOverridesManualWb]). Detection keeps running either way. No
    // default, the same rule as the AF override: every request site must decide it.
    sceneModeAllowed: Boolean,
): FacePriorityKeys? {
    if (!facePriorityAfEffective(controls.facePriorityAf, cap, controls.focusMode)) return null
    val detect = preferredFaceDetectMode(cap.faceDetectModes) ?: return null
    return FacePriorityKeys(faceDetectMode = detect, sceneModeFacePriority = !afOverrideActive && sceneModeAllowed)
}

/** A rect normalized to [0,1] in some named space (sensor/active array, or the preview view). */
@Immutable
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)

    companion object {
        val FULL = NormalizedRect(0f, 0f, 1f, 1f)
    }
}

/** One detected face, active-array normalized, with the HAL's 1..100 confidence. */
data class SensorFace(val bounds: NormalizedRect, val score: Int)

/** One readout: active-array-normalized faces plus the array size (for the stream-crop term). */
data class FaceFrame(val faces: List<SensorFace>, val arrayWidth: Int, val arrayHeight: Int)

/** One face frame for the overlay, already in VIEW-normalized coordinates. */
@Immutable
data class FaceBox(val bounds: NormalizedRect, val primary: Boolean)

/**
 * Normalizes one Camera2 face rectangle (active-array pixels, post-zoom field) against the SAME
 * active array [CameraController.applyMetering] uses, with the same `left + p·width` convention,
 * so this is that mapping's exact inverse. Null for a degenerate array or an empty rect.
 */
internal fun normalizeFaceRect(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    activeLeft: Int,
    activeTop: Int,
    activeWidth: Int,
    activeHeight: Int,
): NormalizedRect? {
    if (activeWidth <= 0 || activeHeight <= 0 || right <= left || bottom <= top) return null
    val w = activeWidth.toFloat()
    val h = activeHeight.toFloat()
    return NormalizedRect(
        ((left - activeLeft) / w).coerceIn(0f, 1f),
        ((top - activeTop) / h).coerceIn(0f, 1f),
        ((right - activeLeft) / w).coerceIn(0f, 1f),
        ((bottom - activeTop) / h).coerceIn(0f, 1f),
    ).takeIf { it.area > 0f }
}

/**
 * Camera-thread readout pacing for STATISTICS_FACES. Two gates, both cheap:
 *  - [shouldSample]: at most one read per [minIntervalMs] (10 Hz). The repeating callback runs
 *    30-60 Hz and `result.get(STATISTICS_FACES)` allocates a Face[] per call, so the time check runs
 *    FIRST and the metadata is not touched on the other frames.
 *  - [offer]: publish only on a MATERIAL change — face count, or any edge moving more than
 *    [moveThreshold] of the frame. An empty set is published once (to clear the frames), never
 *    repeated. A steady face therefore costs zero UI work.
 * Confined to the camera thread; no logging (ColorOS 300-row quota).
 */
internal class FaceReadoutGate(
    private val minIntervalMs: Long = FACE_READOUT_MIN_INTERVAL_MS,
    private val moveThreshold: Float = FACE_MATERIAL_MOVE,
) {
    private var lastSampleMs = Long.MIN_VALUE
    private var published: List<SensorFace>? = null

    fun shouldSample(nowMs: Long): Boolean {
        if (lastSampleMs != Long.MIN_VALUE && nowMs - lastSampleMs < minIntervalMs) return false
        lastSampleMs = nowMs
        return true
    }

    /** True when [faces] must be published; the gate then remembers them as the published set. */
    fun offer(faces: List<SensorFace>): Boolean {
        val last = published
        if (last != null && !materiallyDifferent(last, faces, moveThreshold)) return false
        published = faces
        return true
    }

    /** True when a non-empty set was published and a clear is owed (detection turned off). */
    fun clearOwed(): Boolean = !published.isNullOrEmpty()

    fun reset() {
        published = null
        lastSampleMs = Long.MIN_VALUE
    }
}

internal fun materiallyDifferent(a: List<SensorFace>, b: List<SensorFace>, threshold: Float): Boolean {
    if (a.size != b.size) return true
    // HAL order is not guaranteed stable across frames; compare in a canonical order so a reorder
    // of the same faces is not a "change".
    val sa = a.sortedWith(FACE_ORDER)
    val sb = b.sortedWith(FACE_ORDER)
    for (i in sa.indices) {
        val x = sa[i].bounds
        val y = sb[i].bounds
        if (kotlin.math.abs(x.left - y.left) > threshold ||
            kotlin.math.abs(x.top - y.top) > threshold ||
            kotlin.math.abs(x.right - y.right) > threshold ||
            kotlin.math.abs(x.bottom - y.bottom) > threshold
        ) {
            return true
        }
    }
    return false
}

private val FACE_ORDER = compareBy<SensorFace>({ it.bounds.left }, { it.bounds.top })

/**
 * Which face the overlay marks as the AF target. SIMPLE mode carries no face ids and Camera2 does
 * not say which face FACE_PRIORITY chose, so this is the honest proxy: the LARGEST face (nearest /
 * most prominent), ties broken by score. Index into [faces], or -1 when empty.
 */
internal fun primaryFaceIndex(faces: List<SensorFace>): Int {
    var best = -1
    for (i in faces.indices) {
        if (best < 0) { best = i; continue }
        val a = faces[i]
        val b = faces[best]
        if (a.bounds.area > b.bounds.area || (a.bounds.area == b.bounds.area && a.score > b.score)) best = i
    }
    return best
}

/**
 * The preview stream's source area inside the active array, normalized. A stream whose aspect
 * differs from the array is center-cropped by the HAL (Camera2 letterbox/pillarbox rule in
 * CONTROL_ZOOM_RATIO's javadoc): PHOTO previews the 4:3 array 1:1 ([NormalizedRect.FULL]); a 16:9
 * VIDEO stream from a 4:3 array shows only the middle 75 % of the array's height.
 */
internal fun streamSourceCrop(streamW: Int, streamH: Int, arrayW: Int, arrayH: Int): NormalizedRect {
    if (streamW <= 0 || streamH <= 0 || arrayW <= 0 || arrayH <= 0) return NormalizedRect.FULL
    val streamAspect = streamW.toFloat() / streamH
    val arrayAspect = arrayW.toFloat() / arrayH
    return if (streamAspect > arrayAspect) {
        val h = arrayAspect / streamAspect
        NormalizedRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
    } else if (streamAspect < arrayAspect) {
        val w = streamAspect / arrayAspect
        NormalizedRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
    } else {
        NormalizedRect.FULL
    }
}

/**
 * Every term [mapTapFocusGeometry] composes on the way from the view to a metering region, plus the
 * stream crop it does not model. Built by the engine from the SAME sources setTapPoint reads, so a
 * face frame lands where a tap on that face would meter.
 */
internal data class FaceViewGeometry(
    val sensorOrientation: Int,
    val afocal180: Boolean,
    val punchActive: Boolean,
    val loupeSensorCenter: Pair<Float, Float>,
    val meteringMirrorX: Boolean,
    val windowRotationDeg: Int,
    val streamCrop: NormalizedRect,
)

/**
 * Active-array-normalized point → VIEW-normalized point: the exact inverse of the sensor half of
 * [mapTapFocusGeometry] (window un-rotation → metering mirror → [viewTapToSensorPoint] → loupe crop),
 * applied in reverse order, preceded by the array → stream crop. Unclamped: a face outside the
 * magnified loupe legitimately maps outside [0,1] and is clipped by [sensorFaceToView].
 */
internal fun sensorPointToViewPoint(sx: Float, sy: Float, g: FaceViewGeometry): Pair<Float, Float> {
    val crop = g.streamCrop
    var px = if (crop.width > 0f) (sx - crop.left) / crop.width else sx
    var py = if (crop.height > 0f) (sy - crop.top) / crop.height else sy
    if (g.punchActive) {
        // Inverse of loupeAdjustedTap: s = c + span·(p − 0.5)  ⇒  p = 0.5 + (s − c)/span.
        val span = 1f - PUNCH_IN_CROP
        px = 0.5f + (px - g.loupeSensorCenter.first) / span
        py = 0.5f + (py - g.loupeSensorCenter.second) / span
    }
    // Inverse of viewTapToSensorPoint's rotation by −total: rotate the centered point by +total.
    val total = ((g.sensorOrientation + if (g.afocal180) 180 else 0) % 360 + 360) % 360
    val rad = Math.toRadians(total.toDouble())
    val cos = Math.cos(rad).toFloat()
    val sin = Math.sin(rad).toFloat()
    val cx = px - 0.5f
    val cy = py - 0.5f
    val vx0 = cx * cos - cy * sin + 0.5f
    val vy = cx * sin + cy * cos + 0.5f
    val vx = if (g.meteringMirrorX) 1f - vx0 else vx0
    return RotationMath.rotateViewPoint(vx, vy, g.windowRotationDeg)
}

/**
 * Maps a face rect to the view and clips it to the visible frame. Every stage is a 90°-multiple
 * rotation, a mirror, or an axis-aligned affine, so two opposite corners determine the rect. Null
 * when nothing of the face is visible (e.g. outside a magnified loupe).
 */
internal fun sensorFaceToView(face: NormalizedRect, g: FaceViewGeometry): NormalizedRect? {
    val (ax, ay) = sensorPointToViewPoint(face.left, face.top, g)
    val (bx, by) = sensorPointToViewPoint(face.right, face.bottom, g)
    val clipped = NormalizedRect(
        minOf(ax, bx).coerceIn(0f, 1f),
        minOf(ay, by).coerceIn(0f, 1f),
        maxOf(ax, bx).coerceIn(0f, 1f),
        maxOf(ay, by).coerceIn(0f, 1f),
    )
    return clipped.takeIf { it.width > 0f && it.height > 0f }
}

/** The overlay's whole input: faces mapped to the view, primary marked. Empty = draw nothing. */
internal fun faceBoxesForView(faces: List<SensorFace>, g: FaceViewGeometry): List<FaceBox> {
    val primary = primaryFaceIndex(faces)
    return faces.mapIndexedNotNull { i, face ->
        sensorFaceToView(face.bounds, g)?.let { FaceBox(it, primary = i == primary) }
    }
}

/**
 * Overlay gate. Frames show only while the feature is truly in force: setting on, route supports
 * it, an AF focus mode (MANUAL hides them — MF owns the lens), a live session, and no full-screen
 * modal ([CameraUiState.cameraInputBlocked] covers settings, Fn and review). Recording keeps them,
 * as Sony bodies do.
 */
internal fun faceFramesVisible(
    enabled: Boolean,
    cap: FaceCapability?,
    focusMode: FocusMode,
    cameraReady: Boolean,
    modalOpen: Boolean,
    boxes: List<FaceBox>,
): Boolean = facePriorityAfEffective(enabled, cap, focusMode) && cameraReady && !modalOpen &&
    boxes.isNotEmpty()

/**
 * Green (AF target confirmed) only for the primary face, only while the HAL reports FOCUSED, and only
 * when face priority actually owns AF — a held tap or AF Lock overrides it, so a green face frame
 * there would claim a focus decision the face did not make.
 */
internal fun faceFrameFocused(
    box: FaceBox,
    afIndication: AfIndication,
    tapFocusHeld: Boolean,
    afLock: Boolean,
): Boolean = box.primary && afIndication == AfIndication.FOCUSED && !tapFocusHeld && !afLock

/** 10 Hz ceiling on the face readout (see [FaceReadoutGate]). */
internal const val FACE_READOUT_MIN_INTERVAL_MS = 100L

/** 1.5 % of the frame: below this an edge move is detector jitter, not motion worth a recompose. */
internal const val FACE_MATERIAL_MOVE = 0.015f

/** Camera2 never reports more than maxFaceCount; this caps a misbehaving HAL's array anyway. */
internal const val FACE_MAX_PUBLISHED = 10
