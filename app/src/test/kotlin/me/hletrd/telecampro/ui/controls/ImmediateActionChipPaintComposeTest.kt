package me.hletrd.telecampro.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.ui.theme.CameraColors
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h320dp-mdpi")
class ImmediateActionChipPaintComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `rendered command chip wires container content and border for every state`() {
        val states = listOf(
            "inactive-enabled" to (false to true),
            "inactive-disabled" to (false to false),
            "active-enabled" to (true to true),
            "active-disabled" to (true to false),
        )
        compose.setContent {
            TeleCamProTheme {
                Column(Modifier.background(CameraColors.Pill).padding(8.dp)) {
                    states.forEach { (tag, state) ->
                        ImmediateActionChip(
                            label = "██",
                            active = state.first,
                            enabled = state.second,
                            onClick = {},
                            modifier = Modifier.testTag(tag),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val rendered = states.associate { (tag, _) ->
            tag to compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        }
        assertDifferent(rendered.getValue("active-enabled"), rendered.getValue("active-disabled"), "active disabled paint")
        assertDifferent(rendered.getValue("inactive-enabled"), rendered.getValue("inactive-disabled"), "inactive disabled paint")

        val activeEnabled = peakInteriorContrast(rendered.getValue("active-enabled"))
        val activeDisabled = peakInteriorContrast(rendered.getValue("active-disabled"))
        val inactiveEnabled = peakInteriorContrast(rendered.getValue("inactive-enabled"))
        val inactiveDisabled = peakInteriorContrast(rendered.getValue("inactive-disabled"))
        assertTrue("active label must dim from $activeEnabled to $activeDisabled", activeEnabled > activeDisabled)
        assertTrue("inactive label must dim from $inactiveEnabled to $inactiveDisabled", inactiveEnabled > inactiveDisabled)

        val enabledEdge = peakTopEdgeContrast(rendered.getValue("inactive-enabled"), CameraColors.Pill)
        val disabledEdge = peakTopEdgeContrast(rendered.getValue("inactive-disabled"), CameraColors.Pill)
        assertTrue("disabled border must recede from $enabledEdge to $disabledEdge", enabledEdge > disabledEdge)
    }

    private fun assertDifferent(first: PixelMap, second: PixelMap, label: String) {
        assertTrue(
            label,
            first.width != second.width || first.height != second.height ||
                (0 until minOf(first.width, second.width)).any { x ->
                    (0 until minOf(first.height, second.height)).any { y -> first[x, y] != second[x, y] }
                },
        )
    }

    private fun peakInteriorContrast(pixels: PixelMap): Double {
        val base = pixels[4.coerceAtMost(pixels.width - 1), pixels.height / 2]
        return (3 until (pixels.width - 3).coerceAtLeast(4)).maxOf { x ->
            (3 until (pixels.height - 3).coerceAtLeast(4)).maxOf { y -> contrast(pixels[x, y], base) }
        }
    }

    private fun peakTopEdgeContrast(pixels: PixelMap, background: Color): Double =
        (0 until pixels.width).maxOf { x ->
            (0 until minOf(4, pixels.height)).maxOf { y -> contrast(pixels[x, y], background) }
        }

    private fun contrast(first: Color, second: Color): Double {
        fun luminance(color: Color): Double {
            fun channel(value: Float): Double =
                if (value <= 0.04045f) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) +
                0.0722 * channel(color.blue)
        }
        val lighter = maxOf(luminance(first), luminance(second))
        val darker = minOf(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }
}
