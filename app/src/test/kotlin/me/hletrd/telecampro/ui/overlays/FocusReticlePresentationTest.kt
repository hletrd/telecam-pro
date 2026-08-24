package me.hletrd.telecampro.ui.overlays

import me.hletrd.telecampro.camera.AfIndication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
