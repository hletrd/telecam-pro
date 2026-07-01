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

        var best: TeleSelection? = null
        for (id in manager.cameraIdList) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue

            val physicalIds = chars.physicalCameraIds
            if (physicalIds.isEmpty()) {
                best = pickCloser(best, TeleSelection(id, null, equivFocalOf(manager, id)))
            } else {
                for (pid in physicalIds) {
                    best = pickCloser(best, TeleSelection(id, pid, equivFocalOf(manager, pid)))
                }
            }
        }
        return best
    }

    private fun pickCloser(current: TeleSelection?, candidate: TeleSelection): TeleSelection {
        if (candidate.equivFocalMm <= 0f) return current ?: candidate
        if (current == null) return candidate
        return if (abs(candidate.equivFocalMm - TARGET_EQUIV_MM) < abs(current.equivFocalMm - TARGET_EQUIV_MM)) {
            candidate
        } else current
    }

    private fun equivFocalOf(manager: CameraManager, id: String): Float {
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return 0f
        val focalMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = physical?.let { hypot(it.width, it.height) } ?: 0f
        return if (diag > 0f) focalMm * FULL_FRAME_DIAGONAL_MM / diag else focalMm
    }
}
