package com.hletrd.findx9tele.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata

/**
 * Identifies which camera/physical-lens to use.
 * @param physicalId non-null when the tele is a physical sub-camera of a logical multi-camera;
 *                   streams must then be routed with OutputConfiguration.setPhysicalCameraId().
 */
data class TeleSelection(
    val logicalId: String,
    val physicalId: String?,
    val focalMm: Float,
)

/**
 * Picks the back telephoto lens by choosing the largest available focal length among back-facing
 * cameras and their physical sub-cameras. A manual override id lets the user pin a specific lens
 * ("logicalId" or "logicalId:physicalId") since physical ids vary by firmware.
 */
object CameraSelector2 {

    fun select(manager: CameraManager, overrideId: String?): TeleSelection? {
        if (!overrideId.isNullOrBlank()) {
            val parts = overrideId.split(":")
            return if (parts.size == 2) {
                TeleSelection(parts[0], parts[1], maxFocalOf(manager, parts[1]))
            } else {
                TeleSelection(overrideId, null, maxFocalOf(manager, overrideId))
            }
        }

        var best: TeleSelection? = null
        for (id in manager.cameraIdList) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue

            val physicalIds = chars.physicalCameraIds
            if (physicalIds.isEmpty()) {
                best = pickLonger(best, TeleSelection(id, null, maxFocalOf(manager, id)))
            } else {
                for (pid in physicalIds) {
                    best = pickLonger(best, TeleSelection(id, pid, maxFocalOf(manager, pid)))
                }
            }
        }
        return best
    }

    private fun pickLonger(current: TeleSelection?, candidate: TeleSelection): TeleSelection =
        if (current == null || candidate.focalMm > current.focalMm) candidate else current

    private fun maxFocalOf(manager: CameraManager, id: String): Float {
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return 0f
        return chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.maxOrNull() ?: 0f
    }
}
