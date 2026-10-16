package me.hletrd.telecampro.ui.overlays

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import me.hletrd.telecampro.camera.AfIndication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusReticlePresentationTest {
    @Test
    fun `scanning focused and failed states have deterministic non-color cues`() {
        assertEquals(FocusReticleCue.NONE, focusReticleCue(AfIndication.SCANNING))
        assertEquals(FocusReticleCue.CHECK, focusReticleCue(AfIndication.FOCUSED))
        assertEquals(FocusReticleCue.CROSS, focusReticleCue(AfIndication.FAILED))

        assertNotEquals(
            focusReticleCue(AfIndication.FOCUSED),
            focusReticleCue(AfIndication.FAILED),
        )
    }

    @Test
    fun `idle reticle stays quiet until a terminal verdict exists`() {
        assertEquals(FocusReticleCue.NONE, focusReticleCue(AfIndication.IDLE))
    }

    @Test
    fun `Canvas cue geometry renders distinct in-bounds check and cross paths`() {
        val check = geometry(FocusReticleCue.CHECK)
        val cross = geometry(FocusReticleCue.CROSS)

        assertTrue(check.segments.isNotEmpty())
        assertTrue(cross.segments.isNotEmpty())
        assertNotEquals(check.segments, cross.segments)
        listOf(check, cross).forEach { geometry ->
            assertTrue(geometry.inkWidthPx > 0f)
            assertTrue(geometry.keylineWidthPx > geometry.inkWidthPx)
            geometry.segments.flatMap { listOf(it.start, it.end) }.forEach { point ->
                assertTrue("x=${point.x}", point.x in 41f..59f)
                assertTrue("y=${point.y}", point.y in 41f..59f)
            }
        }
        assertTrue(geometry(FocusReticleCue.NONE).segments.isEmpty())
    }

    @Test
    fun `terminal ink survives dark fixtures and keyline survives bright fixtures`() {
        assertTrue(contrast(FocusReticleFocusedInk, Color.Black) >= 3.0)
        assertTrue(contrast(FocusReticleFailedInk, Color.Black) >= 3.0)
        assertTrue(contrast(FocusReticleKeylineInk, Color.White) >= 3.0)
        // The wider keyline must remain visible around, rather than underneath, the terminal ink.
        val check = geometry(FocusReticleCue.CHECK)
        assertTrue(check.keylineWidthPx > check.inkWidthPx)
    }

    private fun geometry(cue: FocusReticleCue): FocusReticleCueGeometry =
        focusReticleCueGeometry(
            cue = cue,
            centerX = 50f,
            centerY = 50f,
            halfSizePx = 9f,
            inkWidthPx = 2f,
            keylineWidthPx = 4f,
        )

    private fun contrast(foreground: Color, background: Color): Double {
        val foregroundLuminance = luminance(foreground.toArgb())
        val backgroundLuminance = luminance(background.toArgb())
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val encoded = ((argb shr shift) and 0xFF) / 255.0
            return if (encoded <= 0.04045) encoded / 12.92
            else Math.pow((encoded + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
