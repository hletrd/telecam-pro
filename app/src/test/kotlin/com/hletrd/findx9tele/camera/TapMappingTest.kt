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
}
