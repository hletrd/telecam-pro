package me.hletrd.telecampro.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
@Config(qualifiers = "w480dp-h240dp-mdpi")
class AffordanceEdgeComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `rendered chip states keep enabled edge strong and disabled edge quiet`() {
        compose.setContent {
            TeleCamProTheme {
                Row(
                    Modifier
                        .background(CameraColors.Pill)
                        .padding(16.dp),
                ) {
                    Chip("enabled", selected = false, enabled = true)
                    Chip("selected", selected = true, enabled = true)
                    Chip("disabled", selected = false, enabled = false)
                }
            }
        }
        compose.waitForIdle()

        val enabled = renderedTopEdgeContrast("enabled")
        val selected = renderedTopEdgeContrast("selected")
        val disabled = renderedTopEdgeContrast("disabled")
        assertTrue("enabled rendered edge contrast was $enabled", enabled >= 3.0)
        assertTrue("selected rendered fill contrast was $selected", selected >= 3.0)
        assertTrue("disabled edge $disabled was not quieter than enabled $enabled", disabled < enabled)
    }

    @androidx.compose.runtime.Composable
    private fun Chip(tag: String, selected: Boolean, enabled: Boolean) {
        FilterChip(
            selected = selected,
            enabled = enabled,
            onClick = {},
            label = { Text(tag) },
            modifier = Modifier.testTag(tag),
            colors = pixelChipColors(),
            border = pixelChipBorder(selected, enabled),
        )
    }

    private fun renderedTopEdgeContrast(tag: String): Double {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val center = pixels.width / 2
        val edgePixels = (center - 2..center + 2).flatMap { x ->
            (0..minOf(5, pixels.height - 1)).map { y -> pixels[x, y] }
        }
        return edgePixels.maxOf { contrast(it, CameraColors.Pill) }
    }

    private fun contrast(first: Color, second: Color): Double {
        fun luminance(color: Color): Double {
            fun channel(value: Float): Double =
                if (value <= 0.04045f) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) +
                0.0722 * channel(color.blue)
        }
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }
}
