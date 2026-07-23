package com.hletrd.findx9tele.gl

import com.hletrd.findx9tele.camera.ColorTransfer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exhaustive ColorTransfer→uTransfer shader-branch mapping. shaderTransferCode exists
 * BECAUSE the old inline `when` ended in `else -> 0`: a future ColorTransfer member compiled clean
 * while silently rendering as SDR (unlike the container-tag side, where the exhaustive `when`
 * breaks the build). This test is the host-side tripwire for that regression.
 */
class ShaderTransferCodeTest {

    @Test
    fun `every transfer maps to its exact shader branch`() {
        // null = the preview path: camera frames are already SDR.
        assertEquals(0, shaderTransferCode(null, delogAssist = false))
        assertEquals(1, shaderTransferCode(ColorTransfer.HLG, delogAssist = false))
        assertEquals(2, shaderTransferCode(ColorTransfer.SLOG3, delogAssist = false))
        assertEquals(4, shaderTransferCode(ColorTransfer.SLOG3_CINE, delogAssist = false))
        assertEquals(5, shaderTransferCode(ColorTransfer.LOGC3, delogAssist = false))
        // SDR = no OETF, deliberately the same branch as the null path.
        assertEquals(0, shaderTransferCode(ColorTransfer.SDR, delogAssist = false))
    }

    @Test
    fun `delog assist wins over every transfer including none`() {
        // The dormant native-log monitor de-log replaces the forward curve entirely (branch 3),
        // whatever the selected transfer is.
        assertEquals(3, shaderTransferCode(null, delogAssist = true))
        for (transfer in ColorTransfer.entries) {
            assertEquals(transfer.name, 3, shaderTransferCode(transfer, delogAssist = true))
        }
    }

    @Test
    fun `forward curves never collide with each other or the de-log branch`() {
        val curveCodes = listOf(ColorTransfer.HLG, ColorTransfer.SLOG3, ColorTransfer.SLOG3_CINE, ColorTransfer.LOGC3)
            .map { shaderTransferCode(it, delogAssist = false) }
        assertEquals(curveCodes.size, curveCodes.toSet().size)
        curveCodes.forEach { code ->
            org.junit.Assert.assertNotEquals("must not alias SDR", 0, code)
            org.junit.Assert.assertNotEquals("must not alias the de-log monitor", 3, code)
        }
    }
}
