package me.hletrd.telecampro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.camera.CaptureMode
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
class ShutterFocusComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `keyboard focus paints two-tone shutter keyline over bright and dark frames`() {
        val frames = listOf("dark" to Color.Black, "bright" to Color.White)
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            TeleCamProTheme {
                Row {
                    Box(Modifier.size(1.dp).focusable().testTag("focus-sink"))
                    frames.forEach { (name, frame) ->
                        Box(Modifier.background(frame).padding(8.dp)) {
                            ShutterButton(
                                mode = CaptureMode.PHOTO,
                                isRecording = false,
                                timerCountdownSec = 0,
                                onClick = {},
                                modifier = Modifier.testTag("shutter-$name"),
                            )
                        }
                        Box(Modifier.background(frame).padding(8.dp)) {
                            ShutterFocusIndicator(
                                focused = false,
                                modifier = Modifier.size(76.dp).testTag("indicator-$name-off"),
                            )
                        }
                        Box(Modifier.background(frame).padding(8.dp)) {
                            ShutterFocusIndicator(
                                focused = true,
                                modifier = Modifier.size(76.dp).testTag("indicator-$name-on"),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        frames.forEach { (name, frame) ->
            val tag = "shutter-$name"
            compose.onNodeWithTag("focus-sink").requestFocus()
            compose.waitForIdle()
            compose.onNodeWithTag(tag).requestFocus()
            compose.waitForIdle()
            compose.onNodeWithTag(tag).assert(
                SemanticsMatcher.expectValue(ShutterKeyboardFocused, true),
            )
            val before = compose.onNodeWithTag("indicator-$name-off").captureToImage().toPixelMap()
            val focused = compose.onNodeWithTag("indicator-$name-on").captureToImage().toPixelMap()

            val edge = buildList {
                for (x in 0 until focused.width) {
                    for (y in 0 until minOf(7, focused.height)) add(x to y)
                }
            }
            val changedEdge = edge.filter { (x, y) -> before[x, y] != focused[x, y] }
            assertTrue("$name focus must change rendered edge pixels", changedEdge.isNotEmpty())
            val contrastColor = if (frame == Color.Black) CameraColors.Accent else Color.Black
            assertTrue(
                "$name focus keyline must reach 3 to 1 against its finder frame",
                contrast(contrastColor, frame) >= 3.0,
            )
        }
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
