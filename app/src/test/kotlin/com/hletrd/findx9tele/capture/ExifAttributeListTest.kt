package com.hletrd.findx9tele.capture

import androidx.exifinterface.media.ExifInterface
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.MeteringMode
import com.hletrd.findx9tele.storage.CaptureFamilyKey
import com.hletrd.findx9tele.storage.CaptureFamilyMedia
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure EXIF tag→value math behind the processed-still stamp (exifAttributeList — the
 * value comments in StillCapturePipeline.kt reference the stock camera's 3× sample). ExifInterface
 * TAG_* values are plain String constants, host-safe like heifExifDimensionAttributes' test.
 */
class ExifAttributeListTest {

    @Test
    fun `full shot pins the stock-sample APEX and rational encodings in stamp order`() {
        val attributes = withTimeZone("Asia/Seoul") { exifAttributeList(fullShot()) }

        assertEquals(
            listOf(
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "100",
                ExifInterface.TAG_EXPOSURE_TIME to "0.008333333",
                // APEX shutter = -log2(1/120 s), rational thousandths.
                ExifInterface.TAG_SHUTTER_SPEED_VALUE to "6907/1000",
                ExifInterface.TAG_F_NUMBER to "2.2",
                // APEX aperture = 2·log2(2.2), rational hundredths.
                ExifInterface.TAG_APERTURE_VALUE to "228/100",
                ExifInterface.TAG_MAX_APERTURE_VALUE to "228/100",
                // Real lens focal (20.1 mm on the 3×), rational millimeters.
                ExifInterface.TAG_FOCAL_LENGTH to "20100/1000",
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "70",
                ExifInterface.TAG_DIGITAL_ZOOM_RATIO to "42860/10000",
                // EV bias in sixths, the stock sample's denominator.
                ExifInterface.TAG_EXPOSURE_BIAS_VALUE to "-2/6",
                ExifInterface.TAG_METERING_MODE to "2",
                ExifInterface.TAG_FLASH to "1",
                ExifInterface.TAG_EXPOSURE_PROGRAM to "1",
                ExifInterface.TAG_EXPOSURE_MODE to "1",
                ExifInterface.TAG_WHITE_BALANCE to "1",
                ExifInterface.TAG_LENS_MODEL to "OPPO 70mm f/2.2",
                ExifInterface.TAG_COLOR_SPACE to "1",
                ExifInterface.TAG_DATETIME to "1970:01:01 09:00:00",
                ExifInterface.TAG_DATETIME_ORIGINAL to "1970:01:01 09:00:00",
                ExifInterface.TAG_DATETIME_DIGITIZED to "1970:01:01 09:00:00",
                ExifInterface.TAG_OFFSET_TIME to "+09:00",
                ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "+09:00",
                ExifInterface.TAG_ORIENTATION to "1",
                ExifInterface.TAG_MAKE to "OPPO",
                ExifInterface.TAG_MODEL to "OPPO Find X9 Ultra",
            ),
            attributes,
        )
    }

    @Test
    fun `unknown sensor and lens values omit their optional tags entirely`() {
        val attributes = exifAttributeList(
            fullShot().copy(
                iso = 0,
                expNs = 0L,
                lensApertureF = 0f,
                lensFocalMm = 0f,
                focal35mm = 0,
            ),
        ).toMap()

        for (omitted in listOf(
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        )) {
            assertNull(omitted, attributes[omitted])
        }
        // The unconditional tags still stamp: a metadata-poor shot keeps make/model/orientation.
        assertEquals("1", attributes[ExifInterface.TAG_ORIENTATION])
        assertEquals("OPPO", attributes[ExifInterface.TAG_MAKE])
    }

    @Test
    fun `metering flash exposure and WB flags map to the stock sample codes`() {
        val base = fullShot()
        fun value(shot: ExifShot, tag: String): String? = exifAttributeList(shot).toMap()[tag]

        // Metering: 5 = pattern, 2 = center-weighted (the stock default), 3 = spot.
        assertEquals("5", value(base.copy(meteringMode = MeteringMode.MATRIX), ExifInterface.TAG_METERING_MODE))
        assertEquals("2", value(base.copy(meteringMode = MeteringMode.CENTER), ExifInterface.TAG_METERING_MODE))
        assertEquals("3", value(base.copy(meteringMode = MeteringMode.SPOT), ExifInterface.TAG_METERING_MODE))
        // Flash: 0x1 = fired; 0x10 = "did not fire, compulsory off".
        assertEquals("16", value(base.copy(flashFired = false), ExifInterface.TAG_FLASH))
        // Auto exposure/WB report the 0 codes.
        assertEquals("0", value(base.copy(manualExposure = false), ExifInterface.TAG_EXPOSURE_MODE))
        assertEquals("0", value(base.copy(manualWb = false), ExifInterface.TAG_WHITE_BALANCE))
        assertEquals("2", value(base.copy(exposureProgram = 2), ExifInterface.TAG_EXPOSURE_PROGRAM))
        // Zero EV bias keeps the sixths denominator (0/6, the stock sample's value).
        assertEquals("0/6", value(base.copy(evBiasStops = 0f), ExifInterface.TAG_EXPOSURE_BIAS_VALUE))
        // Unity digital zoom is still stamped explicitly.
        assertEquals("10000/10000", value(base.copy(digitalZoom = 1f), ExifInterface.TAG_DIGITAL_ZOOM_RATIO))
    }

    @Test
    fun `retained-save status copy is truthful about the recovery marker`() {
        assertEquals("HEIF save delayed. Will retry.", retainedSaveStatus("HEIF", markerDurable = true))
        assertEquals("DNG save retained. Recovery marker failed.", retainedSaveStatus("DNG", markerDurable = false))
    }

    @Test
    fun `ShotSpec defaults to a rear-facing ordinary-resolution shot`() {
        val spec = ShotSpec(
            controls = ManualControls(),
            caps = null,
            selection = null,
            teleconverter = true,
            aspectRatio = AspectRatio.W4_3,
            jpegQuality = 95,
            rotationDegrees = 180,
            captureId = 7,
            familyKey = CaptureFamilyKey(CaptureFamilyMedia.STILL, 1_752_000_000_000L, 7L),
            requestedAtMs = 1L,
            takenAtMs = 2L,
        )

        assertFalse(spec.hiRes)
        assertFalse(spec.frontFacing)
        // Data-class identity: an equal snapshot compares equal; a facing flip does not.
        assertEquals(spec, spec.copy())
        assertNotEquals(spec, spec.copy(frontFacing = true))
        assertTrue(spec.toString().contains("captureId=7"))
    }

    @Test
    fun `ExifShot snapshots compare by value`() {
        val shot = fullShot()
        assertEquals(shot, shot.copy())
        assertEquals(shot.hashCode(), shot.copy().hashCode())
        assertNotEquals(shot, shot.copy(iso = shot.iso + 1))
        assertTrue(shot.toString().contains("lensModel=OPPO 70mm f/2.2"))
    }

    private fun fullShot(): ExifShot = ExifShot(
        iso = 100,
        expNs = 8_333_333L, // 1/120 s
        lensFocalMm = 20.1f,
        lensApertureF = 2.2f,
        focal35mm = 70,
        digitalZoom = 4.286f,
        evBiasStops = -1f / 3f,
        meteringMode = MeteringMode.CENTER,
        flashFired = true,
        exposureProgram = 1,
        manualExposure = true,
        manualWb = true,
        lensModel = "OPPO 70mm f/2.2",
        takenAtMs = 0L,
    )

    /** SimpleDateFormat reads the JVM default zone; pin it so datetime/offset assertions hold. */
    private fun <T> withTimeZone(id: String, block: () -> T): T {
        val previous = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
