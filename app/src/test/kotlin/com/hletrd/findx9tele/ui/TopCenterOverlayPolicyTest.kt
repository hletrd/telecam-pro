package com.hletrd.findx9tele.ui

import androidx.compose.ui.unit.Constraints
import com.hletrd.findx9tele.camera.HardwareKeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopCenterOverlayPolicyTest {

    @Test
    fun rotatedBounds_reserveEveryAnimatedAngle() {
        val expected = mapOf(
            0f to RotatedLayoutBounds(100, 40),
            30f to RotatedLayoutBounds(107, 85),
            44f to RotatedLayoutBounds(100, 99),
            45f to RotatedLayoutBounds(99, 99),
            60f to RotatedLayoutBounds(85, 107),
            90f to RotatedLayoutBounds(40, 100),
        )

        expected.forEach { (degrees, bounds) ->
            assertEquals("bounds at $degrees degrees", bounds, rotatedLayoutBounds(100, 40, degrees))
        }
    }

    @Test
    fun rotatedBounds_normalizeNegativeAndUnwrappedAngles() {
        assertEquals(RotatedLayoutBounds(107, 85), rotatedLayoutBounds(100, 40, -30f))
        assertEquals(RotatedLayoutBounds(40, 100), rotatedLayoutBounds(100, 40, 450f))
        assertEquals(RotatedLayoutBounds(40, 100), rotatedLayoutBounds(100, 40, -450f))
    }

    @Test
    fun rotatedBounds_cardinalRoundingDoesNotAddAPixel() {
        assertEquals(RotatedLayoutBounds(40, 100), rotatedLayoutBounds(100, 40, 90f))
        assertEquals(RotatedLayoutBounds(100, 40), rotatedLayoutBounds(100, 40, 360f))
    }

    @Test
    fun rotatedBounds_saturateInsteadOfOverflowing() {
        assertEquals(
            RotatedLayoutBounds(Int.MAX_VALUE, Int.MAX_VALUE),
            rotatedLayoutBounds(Int.MAX_VALUE, Int.MAX_VALUE, 45f),
        )
    }

    @Test
    fun constrainedRotatedBounds_respectMaxMinAndUnconstrainedAabb() {
        assertEquals(
            RotatedLayoutBounds(85, 100),
            constrainedRotatedLayoutBounds(
                widthPx = 100,
                heightPx = 40,
                degrees = 60f,
                constraints = Constraints(maxHeight = 100),
            ),
        )
        assertEquals(
            RotatedLayoutBounds(90, 80),
            constrainedRotatedLayoutBounds(
                widthPx = 40,
                heightPx = 20,
                degrees = 90f,
                constraints = Constraints(minWidth = 90, minHeight = 80),
            ),
        )
        assertEquals(
            RotatedLayoutBounds(85, 107),
            constrainedRotatedLayoutBounds(
                widthPx = 100,
                heightPx = 40,
                degrees = 60f,
                constraints = Constraints(),
            ),
        )
    }

    @Test
    fun halfPressAfOnLabelYieldsToHeldFocusControl() {
        assertFalse(
            showHalfPressLabel(active = true, action = HardwareKeyAction.AF_ON, tapFocusHeld = true),
        )
        assertTrue(
            showHalfPressLabel(active = true, action = HardwareKeyAction.AF_ON, tapFocusHeld = false),
        )
    }

    @Test
    fun otherHalfPressLabelsRemainVisibleWhileFocusIsHeld() {
        HardwareKeyAction.entries
            .filterNot { it == HardwareKeyAction.AF_ON }
            .forEach { action ->
                assertTrue(action.name, showHalfPressLabel(active = true, action, tapFocusHeld = true))
            }
    }

    @Test
    fun inactiveHalfPressNeverShowsALabel() {
        HardwareKeyAction.entries.forEach { action ->
            assertFalse(action.name, showHalfPressLabel(active = false, action, tapFocusHeld = false))
            assertFalse(action.name, showHalfPressLabel(active = false, action, tapFocusHeld = true))
        }
    }
}
