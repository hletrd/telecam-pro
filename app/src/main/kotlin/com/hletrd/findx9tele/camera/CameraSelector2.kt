package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import kotlin.math.abs
import kotlin.math.hypot

/**
 * @param physicalId non-null when the tele is a physical sub-camera of a logical multi-camera;
 *                   streams must then be routed with OutputConfiguration.setPhysicalCameraId().
 * @param equivFocalMm 35mm-equivalent focal length used for selection.
 */
data class TeleSelection(
    val logicalId: String,
    val physicalId: String?,
    val equivFocalMm: Float,
)

/**
 * Selects the lens the teleconverter mounts on: the back camera whose 35mm-equivalent focal length
 * is CLOSEST to [TARGET_EQUIV_MM] (the 3x/70mm periscope) — NOT the longest lens.
 *
 * On the Find X9 Ultra the longest native lens is the 230mm 10x periscope; the Explorer
 * teleconverter attaches to the 70mm 3x. Picking by max focal would wrongly select the 10x.
 * A manual override ("logicalId" or "logicalId:physicalId") pins a specific lens.
 */
object CameraSelector2 {
    const val TARGET_EQUIV_MM = 70f
    private const val FULL_FRAME_DIAGONAL_MM = 43.2666f

    fun select(manager: CameraManager, overrideId: String?): TeleSelection? {
        if (!overrideId.isNullOrBlank()) {
            val parts = overrideId.split(":")
            return if (parts.size == 2) {
                TeleSelection(parts[0], parts[1], equivFocalOf(manager, parts[1]))
            } else {
                TeleSelection(overrideId, null, equivFocalOf(manager, overrideId))
            }
        }
        return pickBest(candidatesOf(manager))
    }

    /** Enumerates every back-facing lens as a candidate (standalone ids + logical physical sub-cameras). */
    fun candidatesOf(manager: CameraManager): List<TeleSelection> {
        val candidates = ArrayList<TeleSelection>()
        // cameraIdList throws CameraAccessException on a transient camera-service hiccup — reached on
        // every cold start and lens switch, so degrade to "no candidates" instead of crashing.
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue

            val physicalIds = chars.physicalCameraIds
            if (physicalIds.isEmpty()) {
                candidates.add(TeleSelection(id, null, equivFocalOf(manager, id)))
            } else {
                for (pid in physicalIds) candidates.add(TeleSelection(id, pid, equivFocalOf(manager, pid)))
            }
        }
        return candidates
    }

    /**
     * The back LOGICAL multi-camera id (physical sub-ids present) — the seamless-zoom home. Driving
     * CONTROL_ZOOM_RATIO on this id lets the HAL cross the physical lenses internally (0.6–20× on
     * this device, physIds 3/2/4/5) with digital fill between the optical steps — no reopen, the
     * stock-camera behavior. This is NOT the setPhysicalCameraId ROUTING that crashes the QTI HAL:
     * the logical camera is opened plainly with no per-stream physical routing.
     */
    fun logicalBackId(manager: CameraManager): String? {
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
        for (id in ids) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            if (chars.physicalCameraIds.isNotEmpty()) return id
        }
        return null
    }

    /**
     * Resolves the lens whose 35mm-equiv is closest to [targetEquivMm] to a concrete override id
     * string ("logicalId" for a standalone, "logicalId:physicalId" for a routed sub-camera). Returns
     * null when no back lens is readable. Used by the lens switcher to pick UW/main/3×/10×; prefers a
     * standalone id so the QTI-HAL routing crash is avoided (same rule as [pickBest]).
     */
    fun overrideIdForFocal(manager: CameraManager, targetEquivMm: Float): String? {
        val sel = pickClosest(candidatesOf(manager), targetEquivMm) ?: return null
        return if (sel.physicalId != null) "${sel.logicalId}:${sel.physicalId}" else sel.logicalId
    }

    /**
     * The candidate whose 35mm-equiv is closest to [targetEquivMm]; on ties prefers a STANDALONE
     * camera (physicalId == null). Pure, so lens-picker resolution is JVM-unit-testable.
     */
    fun pickClosest(candidates: List<TeleSelection>, targetEquivMm: Float): TeleSelection? =
        candidates.filter { it.equivFocalMm > 0f }
            .minWithOrNull(
                compareBy({ abs(it.equivFocalMm - targetEquivMm) }, { if (it.physicalId == null) 0 else 1 }),
            ) ?: candidates.firstOrNull()

    /**
     * Pure selection over an enumerated candidate list: the one whose 35mm-equiv is CLOSEST to
     * [TARGET_EQUIV_MM]; on ties prefer a STANDALONE camera (physicalId == null) over one reached via
     * logical-multicamera physical routing. On this device the tele is exposed both as physical "0:4"
     * and as standalone id "4"; the routed path crashes the QTI HAL (ChiMulticameraBase
     * configureStreams SIGSEGV), while opening the standalone id works and also permits RAW.
     * Candidates with a non-positive equiv focal (unreadable lens) are excluded. Extracted from
     * [select] so it is JVM-unit-testable (no CameraManager / CameraCharacteristics needed).
     */
    fun pickBest(candidates: List<TeleSelection>): TeleSelection? = pickClosest(candidates, TARGET_EQUIV_MM)

    private fun equivFocalOf(manager: CameraManager, id: String): Float {
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return 0f
        val focalMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = physical?.let { hypot(it.width, it.height) } ?: 0f
        return if (diag > 0f) focalMm * FULL_FRAME_DIAGONAL_MM / diag else focalMm
    }
}
