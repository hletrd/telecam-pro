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

    @Test
    fun `video orientation hint passes the device tilt through, normalized`() {
        // Pins the CURRENT mapping (hint = normalized device orientation; the afocal 180° is baked
        // into the pixels by GL, so it must NOT appear here). The hint's SIGN on the device is an
        // open Residual Field Check — if the field check flips it to (360-deg)%360, this test is
        // the one place that changes with it.
        assertEquals(0, RotationMath.videoOrientationHint(0))
        assertEquals(90, RotationMath.videoOrientationHint(90))
        assertEquals(180, RotationMath.videoOrientationHint(180))
        assertEquals(270, RotationMath.videoOrientationHint(270))
        assertEquals(270, RotationMath.videoOrientationHint(-90))
        assertEquals(90, RotationMath.videoOrientationHint(450))
    }

    @Test
    fun `content aspect swap mirrors coverScale's rotated predicate`() {
        // Net 90/270 swaps; 0/180 does not. Must stay in lockstep with gl/FlipRenderer.coverScale.
        org.junit.Assert.assertTrue(RotationMath.contentAspectSwapped(90, 0))
        org.junit.Assert.assertTrue(RotationMath.contentAspectSwapped(90, 180)) // TELE afocal flip
        org.junit.Assert.assertTrue(RotationMath.contentAspectSwapped(270, 0))
        org.junit.Assert.assertFalse(RotationMath.contentAspectSwapped(0, 0))
        org.junit.Assert.assertFalse(RotationMath.contentAspectSwapped(180, 0))
        org.junit.Assert.assertFalse(RotationMath.contentAspectSwapped(90, 90))
    }

    @Test
    fun `front capture rotation is sensor minus device and the afocal term never applies`() {
        // The front matrix (sensorOrientation 270, the typical front value). Sign is the standard
        // Camera2 front formula and DEVICE-VERIFICATION-PENDING like every rotation sign before it;
        // a device-corrected flip lands here as an intentional matrix change.
        assertEquals(270, RotationMath.captureRotationDegrees(270, false, 0, frontFacing = true))
        assertEquals(180, RotationMath.captureRotationDegrees(270, false, 90, frontFacing = true))
        assertEquals(90, RotationMath.captureRotationDegrees(270, false, 180, frontFacing = true))
        assertEquals(0, RotationMath.captureRotationDegrees(270, false, 270, frontFacing = true))
        // A (route-impossible) teleconverter flag is inert on the front path: the converter is a
        // rear-3× accessory and the facing door forces it off, but even a stale flag must not
        // rotate a selfie by 180°.
        assertEquals(270, RotationMath.captureRotationDegrees(270, true, 0, frontFacing = true))
    }

    @Test
    fun `back capture matrix through the facing-aware overload matches the rear form`() {
        // frontFacing=false must be byte-identical to the historical rear matrix pinned above.
        assertEquals(270, RotationMath.captureRotationDegrees(90, true, 0, frontFacing = false))
        assertEquals(0, RotationMath.captureRotationDegrees(90, true, 90, frontFacing = false))
        assertEquals(90, RotationMath.captureRotationDegrees(90, false, 0, frontFacing = false))
    }

    @Test
    fun `encoder surface size swaps to the displayed aspect for the 90deg sensor (ARCH4-1)`() {
        // The framing contract: the encoder buffer matches the DISPLAYED (portrait) aspect so
        // coverScale nets (1,1) and the file records the viewfinder field. 4K UHD and Open Gate:
        assertEquals(2160 to 3840, RotationMath.encoderSurfaceSize(3840, 2160, 90, 0))
        assertEquals(1920 to 2560, RotationMath.encoderSurfaceSize(2560, 1920, 90, 0))
        // TELE (afocal 180 on top of sensor 90) is still swapped.
        assertEquals(2160 to 3840, RotationMath.encoderSurfaceSize(3840, 2160, 90, 180))
        // A hypothetical 0deg sensor keeps the stream shape.
        assertEquals(3840 to 2160, RotationMath.encoderSurfaceSize(3840, 2160, 0, 0))
    }

    @Test
    fun `encoder surface size also swaps for the front 270deg sensor`() {
        // The front sensor is in the same 90-class as the rear (270 % 180 == 90), and front video
        // never adds a content rotation (no afocal), so the encoder buffer swaps to portrait —
        // the identical ARCH4-1 framing contract, no front special case.
        org.junit.Assert.assertTrue(RotationMath.contentAspectSwapped(270, 0))
        assertEquals(2160 to 3840, RotationMath.encoderSurfaceSize(3840, 2160, 270, 0))
        assertEquals(1080 to 1920, RotationMath.encoderSurfaceSize(1920, 1080, 270, 0))
    }
}
