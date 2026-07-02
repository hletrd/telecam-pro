package com.hletrd.findx9tele.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import com.hletrd.findx9tele.camera.RotationMath
import java.io.OutputStream

/** Writes a RAW_SENSOR [Image] out as a DNG using the capture's own characteristics/result. */
object DngCapture {

    /**
     * Caller owns [out] and [image]; neither is closed here.
     *
     * [orientation] is an EXIF/TIFF `ORIENTATION_*` tag (see [RotationMath]) carrying the full display
     * rotation (sensor + afocal 180° + device tilt) computed by the caller — RAW records it as an
     * orientation tag rather than rotating the Bayer pixels (which would break the CFA). The value is
     * numerically identical to `ExifInterface.ORIENTATION_*`, which `DngCreator.setOrientation` expects.
     */
    fun writeDng(
        out: OutputStream,
        image: Image,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        orientation: Int = RotationMath.ORIENTATION_ROTATE_180,
    ) {
        val creator = DngCreator(characteristics, result)
        try {
            creator.setOrientation(orientation)
            creator.writeImage(out, image)
        } finally {
            creator.close()
        }
    }
}
