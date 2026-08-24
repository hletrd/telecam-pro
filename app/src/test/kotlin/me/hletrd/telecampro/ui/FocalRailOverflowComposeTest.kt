package me.hletrd.telecampro.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocalRailOverflowComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `focal rail fade is rendered at viewport edge and clears at scroll end`() {
        lateinit var scroll: ScrollState
        compose.setContent {
            scroll = rememberScrollState()
            Box(
                Modifier
                    .requiredWidth(120.dp)
                    .height(32.dp)
                    .background(VIEWPORT_COLOR)
                    .testTag(VIEWPORT_TAG),
            ) {
                Row(
                    Modifier
                        .requiredWidth(120.dp)
                        .height(32.dp)
                        .focalRailViewportScroll(scroll),
                ) {
                    Box(Modifier.width(240.dp).height(32.dp).background(CONTENT_COLOR))
                }
            }
        }
        compose.waitForIdle()
        assertTrue("render fixture must overflow", scroll.maxValue > 0)

        val initial = compose.onNodeWithTag(VIEWPORT_TAG).captureToImage().toPixelMap()
        val initialInterior = initial[initial.width / 2, initial.height / 2]
        val initialEdge = initial[initial.width - 2, initial.height / 2]
        assertCloserTo(
            actual = initialInterior,
            expected = CONTENT_COLOR,
            alternative = VIEWPORT_COLOR,
            message = "solid scrolling content was not rendered inside the viewport",
        )
        assertCloserTo(
            actual = initialEdge,
            expected = VIEWPORT_COLOR,
            alternative = CONTENT_COLOR,
            message = "fade ramp did not reveal the viewport at its fixed trailing edge",
        )

        compose.runOnIdle { scroll.dispatchRawDelta(scroll.maxValue.toFloat()) }
        compose.waitForIdle()
        assertEquals(scroll.maxValue, scroll.value)
        val terminal = compose.onNodeWithTag(VIEWPORT_TAG).captureToImage().toPixelMap()
        assertCloserTo(
            actual = terminal[terminal.width - 2, terminal.height / 2],
            expected = CONTENT_COLOR,
            alternative = VIEWPORT_COLOR,
            message = "fade pixels remained after canScrollForward became false",
        )
    }

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
        val VIEWPORT_COLOR = Color(0xFF174EA6)
        val CONTENT_COLOR = Color.White
        const val VIEWPORT_TAG = "focal-rail-render-viewport"
        const val RAIL_TAG = "focal-rail"
    }

    private fun assertCloserTo(
        actual: Color,
        expected: Color,
        alternative: Color,
        message: String,
    ) {
        fun distance(a: Color, b: Color): Float =
            kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) +
                kotlin.math.abs(a.blue - b.blue)
        assertTrue(
            "$message: actual=$actual expected=$expected alternative=$alternative",
            distance(actual, expected) < distance(actual, alternative),
        )
    }
}
