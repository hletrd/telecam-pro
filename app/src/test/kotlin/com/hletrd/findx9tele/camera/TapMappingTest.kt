package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tap-to-focus / loupe coordinate mappings ([viewTapToSensorPoint] /
 * [viewTapToLoupeCenter]). Their own KDoc flags the sensor mapping as approximate and pending
 * on-device calibration — these tests pin the CURRENT behavior so a future calibration lands as an
 * intentional test change, not a silent sign flip (the documented shipped-wrong-once bug class).
 */
class TapMappingTest {

    private val eps = 1e-4f

    @Test
    fun centerTap_isInvariant_atEveryRotation() {
        for (deg in listOf(0, 90, 180, 270)) {
            for (afocal in listOf(false, true)) {
                val (sx, sy) = viewTapToSensorPoint(0.5f, 0.5f, deg, afocal)
                assertEquals(0.5f, sx, eps)
                assertEquals(0.5f, sy, eps)
            }
            val (lx, ly) = viewTapToLoupeCenter(0.5f, 0.5f, deg)
            assertEquals(0.5f, lx, eps)
            assertEquals(0.5f, ly, eps)
        }
    }

    @Test
    fun sensorPoint_sensor90_teleOff_rotatesMinus90() {
        // Right-of-center tap on the 90°-oriented sensor: (0.7, 0.5) → (0.5, 0.3).
        val (x, y) = viewTapToSensorPoint(0.7f, 0.5f, 90, afocal180 = false)
        assertEquals(0.5f, x, eps)
        assertEquals(0.3f, y, eps)
    }

    @Test
    fun sensorPoint_sensor90_teleOn_addsTheAfocal180() {
        // Same tap with the converter mounted: total 270° → (0.5, 0.7).
        val (x, y) = viewTapToSensorPoint(0.7f, 0.5f, 90, afocal180 = true)
        assertEquals(0.5f, x, eps)
        assertEquals(0.7f, y, eps)
    }

    @Test
    fun sensorPoint_cornerTap_staysClamped() {
        for (deg in listOf(0, 90, 180, 270)) {
            val (x, y) = viewTapToSensorPoint(1f, 1f, deg, afocal180 = true)
            assertTrue(x in 0f..1f)
            assertTrue(y in 0f..1f)
        }
    }

    @Test
    fun loupeCenter_appliesTheDocumentedYFlip() {
        // No content rotation (1× lens): a top-of-view tap maps to the TOP of the y-up texcoord
        // space, i.e. the flipped offset — device-verified as the correct direction.
        val (x, y) = viewTapToLoupeCenter(0.5f, 0.2f, 0)
        assertEquals(0.5f, x, eps)
        assertEquals(0.8f, y, eps)
    }

    @Test
    fun loupeCenter_afocal180_invertsBothAxes() {
        val (x, y) = viewTapToLoupeCenter(0.5f, 0.2f, 180)
        assertEquals(0.5f, x, eps)
        assertEquals(0.2f, y, eps)
    }

    // ---- loupeAdjustedTap (AGG4-11/P2.8): magnified taps compose through the loupe crop ----

    @Test
    fun loupeAdjustedTap_centerTapLandsOnTheLoupeCenter() {
        // A tap dead-center of the magnified view IS the loupe center, wherever the loupe sits.
        val (x, y) = loupeAdjustedTap(0.5f, 0.5f, 0.3f, 0.7f, 0.4f)
        assertEquals(0.3f, x, eps)
        assertEquals(0.7f, y, eps)
    }

    @Test
    fun loupeAdjustedTap_offsetShrinksByTheSampledSpan() {
        // At 2.5x magnification (span 0.4), a tap at the view edge (offset 0.5 from center) covers
        // only 0.2 of the frame from the loupe center.
        val (x, y) = loupeAdjustedTap(1f, 0.5f, 0.5f, 0.5f, 0.4f)
        assertEquals(0.7f, x, eps)
        assertEquals(0.5f, y, eps)
    }

    @Test
    fun loupeAdjustedTap_clampsToTheFrame() {
        // A loupe parked near a corner plus an outward tap cannot escape [0,1].
        val (x, y) = loupeAdjustedTap(1f, 0f, 0.9f, 0.1f, 0.4f)
        assertTrue(x in 0f..1f)
        assertTrue(y in 0f..1f)
        assertEquals(1f, x, eps) // 0.9 + 0.4*0.5 = 1.1 -> clamped
    }

    @Test
    fun loupeAdjustedTap_repeatedTapsConverge() {
        // Tapping the same on-screen point repeatedly walks the center monotonically toward the
        // subject (fixed step span*(p-0.5) per tap, clamped at the frame edge), never oscillates.
        var cx = 0.5f
        repeat(10) { cx = loupeAdjustedTap(0.8f, 0.5f, cx, 0.5f, 0.4f).first }
        // After 10 fixed steps of 0.12 the center has clamped against the frame edge.
        assertTrue(cx > 0.9f)
    }

    @Test
    fun mirroredTap_unflipsViewXForContentMappingsButKeepsTheReticlePoint() {
        // Selfie preview: the DISPLAYED image is x-flipped, so a tap at view x=0.25 sits over the
        // scene point an unmirrored view shows at x=0.75. Sensor/loupe mappings must use the
        // unflipped coordinate; the reticle (viewPoint) stays at the raw tap in UI space.
        val mirrored = mapTapFocusGeometry(
            nx = 0.25f,
            ny = 0.4f,
            sensorOrientation = 0,
            teleconverter = false,
            punchActive = false,
            sensorCenter = 0.5f to 0.5f,
            loupeCenter = 0.5f to 0.5f,
            previewRotationDegrees = 0,
            mirrorX = true,
        )
        assertEquals(0.75f, mirrored.sensorPoint.first, eps)
        assertEquals(0.4f, mirrored.sensorPoint.second, eps)
        assertEquals(0.75f, mirrored.loupePoint.first, eps)
        assertEquals(0.25f, mirrored.viewPoint.first, eps)
        assertEquals(0.4f, mirrored.viewPoint.second, eps)
    }

    @Test
    fun mirrorOff_isTheExistingRearMapping() {
        val plain = mapTapFocusGeometry(
            nx = 0.25f,
            ny = 0.4f,
            sensorOrientation = 0,
            teleconverter = false,
            punchActive = false,
            sensorCenter = 0.5f to 0.5f,
            loupeCenter = 0.5f to 0.5f,
            previewRotationDegrees = 0,
            mirrorX = false,
        )
        assertEquals(0.25f, plain.sensorPoint.first, eps)
        assertEquals(0.25f, plain.loupePoint.first, eps)
    }

    @Test
    fun rapidDeferredTap_keepsTheEventTimeVisibleLoupeCenter() {
        val deferred = mapTapFocusGeometry(
            nx = 0.25f,
            ny = 0.5f,
            sensorOrientation = 0,
            teleconverter = false,
            punchActive = true,
            sensorCenter = 0.5f to 0.5f,
            loupeCenter = 0.5f to 0.5f,
            previewRotationDegrees = 0,
        )
        // If an older in-flight tap later moves the center to 0.6, re-mapping the raw view point
        // would incorrectly produce 0.5. The deferred snapshot must retain the visible-time 0.4.
        val futureCenterRemap = mapTapFocusGeometry(
            nx = 0.25f,
            ny = 0.5f,
            sensorOrientation = 0,
            teleconverter = false,
            punchActive = true,
            sensorCenter = 0.6f to 0.5f,
            loupeCenter = 0.6f to 0.5f,
            previewRotationDegrees = 0,
        )

        assertEquals(0.4f, deferred.sensorPoint.first, eps)
        assertEquals(0.4f, deferred.loupePoint.first, eps)
        assertEquals(0.5f, futureCenterRemap.sensorPoint.first, eps)
        assertEquals(0.5f, futureCenterRemap.loupePoint.first, eps)
    }
}
