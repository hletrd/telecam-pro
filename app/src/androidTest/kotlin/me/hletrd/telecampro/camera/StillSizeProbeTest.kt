package me.hletrd.telecampro.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE PROBE (not a shipping assertion): what still sizes does each camera actually ADVERTISE,
 * and how does that compare to its active array?
 *
 * Written because a Lenovo TB331FC (Android 15) saved a SQUARE 2448x2448 HEIF while its active
 * array is 3264x2448 — a 4:3 sensor. The still-size picker takes the largest advertised JPEG that
 * fits inside the active array, and 3264x2448 has MORE area than the square, so either the device
 * does not advertise the full-array JPEG at all (device truth, nothing to fix) or the picker is
 * choosing wrongly (our bug). Only the device can say which.
 *
 * Never fails the build. Read with `adb logcat -s StillProbe`.
 */
@RunWith(AndroidJUnit4::class)
class StillSizeProbeTest {

    private companion object { const val TAG = "StillProbe" }

    @Test
    fun probeAdvertisedStillSizes() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (id in mgr.cameraIdList) {
            val chars = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val array = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            fun sizes(fmt: Int) = runCatching { map?.getOutputSizes(fmt)?.toList().orEmpty() }
                .getOrDefault(emptyList())
                .sortedByDescending { it.width.toLong() * it.height }
            val jpeg = sizes(ImageFormat.JPEG)
            val yuv = sizes(ImageFormat.YUV_420_888)
            Log.i(TAG, "camera $id facing=$facing activeArray=${array?.width()}x${array?.height()}")
            Log.i(TAG, "  JPEG (largest first): ${jpeg.take(8).joinToString { "${it.width}x${it.height}" }}")
            Log.i(TAG, "  YUV  (largest first): ${yuv.take(6).joinToString { "${it.width}x${it.height}" }}")
            // Exactly the shipping rule, so a mismatch between this and the saved file is a real bug.
            val picked = jpeg
                .filter { array == null || (it.width <= array.width() && it.height <= array.height()) }
                .maxByOrNull { it.width.toLong() * it.height }
            val fourThree = jpeg.filter { it.height * 4 == it.width * 3 }
            Log.i(
                TAG,
                "  picked=${picked?.width}x${picked?.height}  " +
                    "any4:3JPEG=${fourThree.take(3).joinToString { "${it.width}x${it.height}" }.ifEmpty { "NONE" }}",
            )
        }
    }
}
