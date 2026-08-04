package me.hletrd.telecampro.gl

import me.hletrd.telecampro.camera.ColorTransfer
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
        assertEquals(0, shaderTransferCode(null))
        assertEquals(1, shaderTransferCode(ColorTransfer.HLG))
        assertEquals(2, shaderTransferCode(ColorTransfer.SLOG3))
        assertEquals(4, shaderTransferCode(ColorTransfer.SLOG3_CINE))
        assertEquals(5, shaderTransferCode(ColorTransfer.LOGC3))
        // SDR = no OETF, deliberately the same branch as the null path.
        assertEquals(0, shaderTransferCode(ColorTransfer.SDR))
    }

    @Test
    fun `code 3 stays vacant so the surviving branches keep their numbers`() {
        // 3 was the de-log branch for a scene-referred vendor stream; that path was declined and the
        // branch removed 2026-08-04. The gap is deliberate — renumbering would silently re-map every
        // code above it, and the shader's own `uTransfer == N` comparisons would have to move in
        // lockstep. Asserting the gap (rather than the deleted behaviour) keeps the mapping from
        // quietly drifting into a slot the fragment shader no longer implements.
        val used = (ColorTransfer.entries.map { shaderTransferCode(it) } + shaderTransferCode(null)).toSet()
        org.junit.Assert.assertFalse("code 3 must stay unused", used.contains(3))
    }

    @Test
    fun `forward curves never collide with each other or the vacant code`() {
        val curveCodes = listOf(ColorTransfer.HLG, ColorTransfer.SLOG3, ColorTransfer.SLOG3_CINE, ColorTransfer.LOGC3)
            .map { shaderTransferCode(it) }
        assertEquals(curveCodes.size, curveCodes.toSet().size)
        curveCodes.forEach { code ->
            org.junit.Assert.assertNotEquals("must not alias SDR", 0, code)
            org.junit.Assert.assertNotEquals("must not alias the vacant code", 3, code)
        }
    }
}
