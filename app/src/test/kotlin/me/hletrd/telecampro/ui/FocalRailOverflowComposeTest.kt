package me.hletrd.telecampro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.ui.controls.TrailingEdgeFadeVisible
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp-xxhdpi")
class FocalRailOverflowComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `constrained focal rail exposes viewport fade until logical end`() {
        compose.setContent {
            TeleCamProTheme {
                Box(Modifier.requiredWidth(120.dp)) {
                    FocalRail(
                        state = CameraUiState(),
                        onLens = {},
                        onTeleZoomMark = {},
                        modifier = Modifier.testTag(RAIL_TAG),
                    )
                }
            }
        }
        compose.waitForIdle()

        val rail = compose.onNodeWithTag(RAIL_TAG)
        val initial = rail.fetchSemanticsNode().config
        val initialRange = initial[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("fixture must overflow", initialRange.maxValue() > 0f)
        assertTrue("viewport fade missing at logical start", initial[TrailingEdgeFadeVisible])

        repeat(4) {
            rail.performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }

        val terminal = rail.fetchSemanticsNode().config
        val terminalRange = terminal[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(terminalRange.maxValue(), terminalRange.value(), 1f)
        assertFalse("viewport fade survived at logical end", terminal[TrailingEdgeFadeVisible])
    }

    private companion object {
        const val RAIL_TAG = "focal-rail"
    }
}
