package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [RotationMath] — pure Kotlin, no Android framework dependencies. */
class RotationMathTest {

    @Test
    fun `preview rotation is afocal 180 in tele mode, 0 otherwise`() {
        assertEquals(180, RotationMath.previewRotationDegrees(true))
        assertEquals(0, RotationMath.previewRotationDegrees(false))
    }

    @Test
    fun `capture rotation adds sensor + afocal + device, normalized to 0-359`() {
        // sensor 90, tele on (+180), device 0 -> 270
        assertEquals(270, RotationMath.captureRotationDegrees(90, true, 0))
        // sensor 90, tele on, device 90 -> 360 -> 0
        assertEquals(0, RotationMath.captureRotationDegrees(90, true, 90))
        // sensor 90, tele off, device 0 -> 90
        assertEquals(90, RotationMath.captureRotationDegrees(90, false, 0))
        // sensor 90, tele off, device 270 -> 360 -> 0
        assertEquals(0, RotationMath.captureRotationDegrees(90, false, 270))
    }

    @Test
    fun `capture rotation normalizes negative and over-360 sums`() {
        // sensor 270, tele on (+180), device 180 -> 630 -> 270
        assertEquals(270, RotationMath.captureRotationDegrees(270, true, 180))
        // negative device orientation still normalizes into range
        assertEquals(0, RotationMath.captureRotationDegrees(0, false, -360))
        assertEquals(90, RotationMath.captureRotationDegrees(0, false, -270))
    }

    @Test
    fun `exif orientation maps the four quadrants to TIFF tags`() {
        assertEquals(RotationMath.ORIENTATION_NORMAL, RotationMath.exifOrientationFor(0))
        assertEquals(RotationMath.ORIENTATION_ROTATE_90, RotationMath.exifOrientationFor(90))
        assertEquals(RotationMath.ORIENTATION_ROTATE_180, RotationMath.exifOrientationFor(180))
        assertEquals(RotationMath.ORIENTATION_ROTATE_270, RotationMath.exifOrientationFor(270))
    }

    @Test
    fun `exif orientation constants match the canonical EXIF TIFF values`() {
        assertEquals(1, RotationMath.ORIENTATION_NORMAL)
        assertEquals(3, RotationMath.ORIENTATION_ROTATE_180)
        assertEquals(6, RotationMath.ORIENTATION_ROTATE_90)
        assertEquals(8, RotationMath.ORIENTATION_ROTATE_270)
    }

    @Test
    fun `exif orientation normalizes out-of-range and non-multiples fall back to normal`() {
        assertEquals(RotationMath.ORIENTATION_ROTATE_90, RotationMath.exifOrientationFor(450)) // 450 -> 90
        assertEquals(RotationMath.ORIENTATION_ROTATE_270, RotationMath.exifOrientationFor(-90)) // -90 -> 270
        assertEquals(RotationMath.ORIENTATION_NORMAL, RotationMath.exifOrientationFor(45)) // non-multiple -> normal
    }

    @Test
    fun `normalize wraps into 0-359`() {
        assertEquals(0, RotationMath.normalize(360))
        assertEquals(350, RotationMath.normalize(-10))
        assertEquals(10, RotationMath.normalize(730))
    }
}
