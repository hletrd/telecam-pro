package me.hletrd.telecampro.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.ui.theme.CameraColors
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import me.hletrd.telecampro.ui.CameraControlSelectionState
import me.hletrd.telecampro.ui.FocalRailState
import me.hletrd.telecampro.ui.RailChip
import me.hletrd.telecampro.ui.focalRailVisualColors
import me.hletrd.telecampro.ui.overlays.HudPlate
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    @Test
    fun `focal rail keeps enabled edge strong and disabled states quiet`() {
        val enabled = railState(selected = false, enabled = true)
        val disabled = railState(selected = false, enabled = false)
        val selectedDisabled = railState(selected = true, enabled = false)
        assertTrue(focalRailVisualColors(enabled).border == CameraColors.AffordanceEdge)
        assertEquals(0.12f, focalRailVisualColors(disabled).border.alpha, 0.002f)
        assertEquals(HudPlate, focalRailVisualColors(selectedDisabled).container)
        assertEquals(0.12f, focalRailVisualColors(selectedDisabled).selectionOverlay.alpha, 0.002f)
        assertEquals(0.38f, focalRailVisualColors(selectedDisabled).label.alpha, 0.002f)

        compose.setContent {
            TeleCamProTheme {
                Row(Modifier.background(CameraColors.Pill).padding(16.dp)) {
                    RailChip(
                        label = "1×",
                        contentDescription = "enabled",
                        presentation = enabled,
                        onClick = {},
                        glyphRotation = 0f,
                        modifier = Modifier.testTag("rail-enabled"),
                    )
                    RailChip(
                        label = "3×",
                        contentDescription = "disabled",
                        presentation = disabled,
                        onClick = {},
                        glyphRotation = 0f,
                        modifier = Modifier.testTag("rail-disabled"),
                    )
                }
            }
        }
        compose.waitForIdle()

        val enabledContrast = renderedRailEdgeContrast("rail-enabled")
        val disabledContrast = renderedRailEdgeContrast("rail-disabled")
        // The circular 1 dp stroke is antialiased in the mdpi capture, so the rendered sample is
        // necessarily below the token's exact 3:1 math (pinned separately above for FilterChip and
        // in HudContrastTest). It must still remain materially strong and distinct from disabled.
        assertTrue("enabled rail edge contrast was $enabledContrast", enabledContrast >= 2.5)
        assertTrue(
            "disabled edge $disabledContrast was not quieter than enabled $enabledContrast",
            disabledContrast < enabledContrast,
        )
    }

    @Test
    fun `focal rail state matrix remains visible over bright and dark frames`() {
        val states = listOf(
            "idle" to railState(selected = false, enabled = true),
            "selected" to railState(selected = true, enabled = true),
            "disabled" to railState(selected = false, enabled = false),
            "selected-disabled" to railState(selected = true, enabled = false),
        )
        compose.setContent {
            TeleCamProTheme {
                Column {
                    listOf("bright" to Color.White, "dark" to Color.Black).forEach { (frame, color) ->
                        Row(Modifier.background(color).padding(16.dp)) {
                            states.forEach { (name, state) ->
                                RailChip(
                                    label = "3×",
                                    contentDescription = "$frame-$name",
                                    presentation = state,
                                    onClick = {},
                                    glyphRotation = 0f,
                                    modifier = Modifier.testTag("$frame-$name"),
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        listOf("bright" to Color.White, "dark" to Color.Black).forEach { (frame, color) ->
            states.forEach { (name, _) ->
                val contrast = renderedRailPeakContrast("$frame-$name", color)
                assertTrue("$frame $name focal state disappeared at $contrast:1", contrast >= 3.0)
            }
        }
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

    private fun renderedRailEdgeContrast(tag: String): Double {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val center = pixels.width / 2
        val edgePixels = (center - 2..center + 2).flatMap { x ->
            (7..minOf(17, pixels.height - 1)).map { y -> pixels[x, y] }
        }
        return edgePixels.maxOf { contrast(it, CameraColors.Pill) }
    }

    private fun renderedRailPeakContrast(tag: String, frame: Color): Double {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        return (0 until pixels.width).maxOf { x ->
            (0 until pixels.height).maxOf { y -> contrast(pixels[x, y], frame) }
        }
    }

    private fun railState(selected: Boolean, enabled: Boolean) = FocalRailState(
        selected = selected,
        enabled = enabled,
        state = when {
            !enabled -> CameraControlSelectionState.CAMERA_RECONFIGURING
            selected -> CameraControlSelectionState.SELECTED
            else -> CameraControlSelectionState.NOT_SELECTED
        },
        accessibilityRole = Role.RadioButton,
    )

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
