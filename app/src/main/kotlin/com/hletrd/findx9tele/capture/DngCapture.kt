package com.hletrd.findx9tele.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.ExifInterface
import android.media.Image
import java.io.OutputStream

/** Writes a RAW_SENSOR [Image] out as a DNG using the capture's own characteristics/result. */
object DngCapture {

    /** Caller owns [out] and [image]; neither is closed here. */
    fun writeDng(
        out: OutputStream,
        image: Image,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
    ) {
        val creator = DngCreator(characteristics, result)
        try {
            // The afocal teleconverter needs a 180deg flip; RAW carries this via the orientation
            // tag rather than rotating pixels.
            creator.setOrientation(ExifInterface.ORIENTATION_ROTATE_180)
            creator.writeImage(out, image)
        } finally {
            creator.close()
        }
    }
}
