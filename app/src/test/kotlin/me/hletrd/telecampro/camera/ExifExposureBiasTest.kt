package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** AGG5-46: ExposureBias comes from the owner that applied EV, not a key app-side AE never writes. */
class ExifExposureBiasTest {
    @Test
    fun `app-side loop modes record the dialed EV over the echoed template default`() {
        for (mode in listOf(ExposureMode.PROGRAM, ExposureMode.SHUTTER, ExposureMode.ISO)) {
            assertEquals(
                "$mode",
                -2,
                exifExposureBiasSteps(manualAe = true, exposureMode = mode, resultSteps = 0, intentSteps = -2),
            )
        }
    }

    @Test
    fun `manual exposure runs no loop, so a dialed EV moved nothing`() {
        assertEquals(
            0,
            exifExposureBiasSteps(manualAe = true, exposureMode = ExposureMode.MANUAL, resultSteps = 0, intentSteps = 3),
        )
    }

    @Test
    fun `HAL AE keeps the wire answer and falls back to intent only without one`() {
        assertEquals(
            1,
            exifExposureBiasSteps(manualAe = false, exposureMode = ExposureMode.PROGRAM, resultSteps = 1, intentSteps = 2),
        )
        assertEquals(
            2,
            exifExposureBiasSteps(manualAe = false, exposureMode = ExposureMode.PROGRAM, resultSteps = null, intentSteps = 2),
        )
    }
}
