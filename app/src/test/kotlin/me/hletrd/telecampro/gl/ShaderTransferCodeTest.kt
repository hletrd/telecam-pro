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
        // O-Log2 (back 2026-10-15) takes a NEW code: 3 belonged to the retired de-log branch.
        assertEquals(6, shaderTransferCode(ColorTransfer.OLOG2))
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
        val curveCodes = listOf(
            ColorTransfer.HLG, ColorTransfer.SLOG3, ColorTransfer.SLOG3_CINE, ColorTransfer.LOGC3, ColorTransfer.OLOG2,
        ).map { shaderTransferCode(it) }
        assertEquals(curveCodes.size, curveCodes.toSet().size)
        curveCodes.forEach { code ->
            org.junit.Assert.assertNotEquals("must not alias SDR", 0, code)
            org.junit.Assert.assertNotEquals("must not alias the vacant code", 3, code)
        }
    }

    @Test
    fun `every non-zero code has a shader branch`() {
        // The mapping and the fragment shader must move in lockstep: a code with no
        // `uTransfer == N` branch would silently render as the display path.
        ColorTransfer.entries.map { shaderTransferCode(it) }.filter { it != 0 }.forEach { code ->
            org.junit.Assert.assertTrue("no branch for code $code", Shaders.FRAGMENT.contains("uTransfer == $code)"))
        }
    }
}
