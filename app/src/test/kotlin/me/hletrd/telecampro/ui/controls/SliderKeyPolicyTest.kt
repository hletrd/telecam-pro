package me.hletrd.telecampro.ui.controls

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SliderKeyPolicyTest {
    private fun target(
        key: Key,
        current: Float = 0.5f,
        units: Int = 100,
        rtl: Boolean = false,
    ): Float = requireNotNull(sliderKeyTargetFraction(current, key, units, rtl, enabled = true))

    @Test
    fun `arrows follow value direction and mirror only their horizontal RTL axis`() {
        assertEquals(0.49f, target(Key.DirectionLeft), 0.0001f)
        assertEquals(0.51f, target(Key.DirectionRight), 0.0001f)
        assertEquals(0.51f, target(Key.DirectionLeft, rtl = true), 0.0001f)
        assertEquals(0.49f, target(Key.DirectionRight, rtl = true), 0.0001f)

        assertEquals(0.51f, target(Key.DirectionUp, rtl = false), 0.0001f)
        assertEquals(0.51f, target(Key.DirectionUp, rtl = true), 0.0001f)
        assertEquals(0.49f, target(Key.DirectionDown, rtl = false), 0.0001f)
        assertEquals(0.49f, target(Key.DirectionDown, rtl = true), 0.0001f)
    }

    @Test
    fun `page home and end use the normalized domain`() {
        assertEquals(0.6f, target(Key.PageUp), 0.0001f)
        assertEquals(0.4f, target(Key.PageDown), 0.0001f)
        assertEquals(0f, target(Key.MoveHome), 0f)
        assertEquals(1f, target(Key.MoveEnd), 0f)

        // Fewer than ten units still advances exactly one real unit per Page key.
        assertEquals(0.75f, target(Key.PageUp, current = 0.5f, units = 4), 0.0001f)
        assertEquals(0.25f, target(Key.PageDown, current = 0.5f, units = 4), 0.0001f)
    }

    @Test
    fun `recognized keys clamp at both endpoints`() {
        for (key in listOf(Key.DirectionLeft, Key.DirectionDown, Key.PageDown, Key.MoveHome)) {
            assertEquals("$key at minimum", 0f, target(key, current = 0f), 0f)
        }
        for (key in listOf(Key.DirectionRight, Key.DirectionUp, Key.PageUp, Key.MoveEnd)) {
            assertEquals("$key at maximum", 1f, target(key, current = 1f), 0f)
        }
        assertEquals(0.01f, target(Key.DirectionRight, current = -2f), 0.0001f)
        assertEquals(1f, target(Key.DirectionLeft, current = 3f, rtl = true), 0f)
    }

    @Test
    fun `disabled invalid and unrelated inputs emit no target`() {
        assertNull(sliderKeyTargetFraction(0.5f, Key.DirectionRight, 100, false, enabled = false))
        assertNull(sliderKeyTargetFraction(Float.NaN, Key.DirectionRight, 100, false, enabled = true))
        assertNull(sliderKeyTargetFraction(0.5f, Key.DirectionRight, 0, false, enabled = true))
        assertNull(sliderKeyTargetFraction(0.5f, Key.A, 100, false, enabled = true))
    }

    @Test
    fun `settings axis mirrors endpoints and is involutive`() {
        assertEquals(0f, cameraSliderAxisFraction(0f, rtl = false), 0f)
        assertEquals(1f, cameraSliderAxisFraction(1f, rtl = false), 0f)
        assertEquals(1f, cameraSliderAxisFraction(0f, rtl = true), 0f)
        assertEquals(0f, cameraSliderAxisFraction(1f, rtl = true), 0f)
        assertEquals(0.25f, cameraSliderAxisFraction(0.25f, rtl = false), 0f)
        assertEquals(0.75f, cameraSliderAxisFraction(0.25f, rtl = true), 0f)
        assertEquals(
            0.25f,
            cameraSliderAxisFraction(cameraSliderAxisFraction(0.25f, rtl = true), rtl = true),
            0f,
        )
        assertEquals(0f, cameraSliderAxisFraction(Float.NaN, rtl = false), 0f)
    }
}
