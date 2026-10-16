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
 * array is 3264x2448 — a 4:3 sensor. Production therefore prefers the active-array aspect before
 * comparing area; this probe calls that same pure selector so its verdict cannot revive the
 * area-only bug it was created to diagnose.
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
            // Exactly the shipping shape-first rule, shared rather than duplicated.
            val picked = pickStillSize(
                candidates = jpeg.map { it.width to it.height },
                arrayW = array?.width() ?: 0,
                arrayH = array?.height() ?: 0,
            )
            val fourThree = jpeg.filter { it.height * 4 == it.width * 3 }
            Log.i(
                TAG,
                "  picked=${picked?.first}x${picked?.second}  " +
                    "any4:3JPEG=${fourThree.take(3).joinToString { "${it.width}x${it.height}" }.ifEmpty { "NONE" }}",
            )
        }
    }
}
