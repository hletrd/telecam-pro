package me.hletrd.telecampro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
                Column {
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
                    }
                }
            }
        }
        compose.waitForIdle()

        frames.forEach { (name, frame) ->
            val tag = "shutter-$name"
            compose.onNodeWithTag("focus-sink").requestFocus()
            compose.waitForIdle()
            val before = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            compose.onNodeWithTag(tag).requestFocus()
            compose.waitForIdle()
            compose.onNodeWithTag(tag).assert(
                SemanticsMatcher.expectValue(ShutterKeyboardFocused, true),
            )
            val focused = compose.onNodeWithTag(tag).captureToImage().toPixelMap()

            val edge = buildList {
                for (x in 0 until focused.width) {
                    for (y in 0 until focused.height) {
                        if (x < 7 || x >= focused.width - 7 || y < 7 || y >= focused.height - 7) {
                            add(x to y)
                        }
                    }
                }
            }
            val changedEdge = edge.filter { (x, y) -> before[x, y] != focused[x, y] }
            assertTrue("$name production shutter focus must change rendered edge pixels", changedEdge.isNotEmpty())
            val strongest = changedEdge.maxBy { (x, y) -> contrast(focused[x, y], frame) }
            val renderedColor = focused[strongest.first, strongest.second]
            val renderedContrast = contrast(renderedColor, frame)
            assertTrue(
                "$name rendered production keyline contrast was $renderedContrast " +
                    "at #${renderedColor.toArgb().toUInt().toString(16)}",
                renderedContrast >= 3.0,
            )
        }
    }

    @Test
    fun `running timelapse paints a stop shape on the production shutter`() {
        compose.setContent {
            TeleCamProTheme {
                Column(Modifier.background(Color.Black)) {
                    ShutterButton(
                        mode = CaptureMode.PHOTO,
                        isRecording = false,
                        timerCountdownSec = 0,
                        timelapseRunning = false,
                        onClick = {},
                        modifier = Modifier.testTag("idle-timelapse"),
                    )
                    ShutterButton(
                        mode = CaptureMode.PHOTO,
                        isRecording = false,
                        timerCountdownSec = 0,
                        timelapseRunning = true,
                        onClick = {},
                        modifier = Modifier.testTag("running-timelapse"),
                    )
                }
            }
        }
        compose.waitForIdle()
        val idle = compose.onNodeWithTag("idle-timelapse").captureToImage().toPixelMap()
        val running = compose.onNodeWithTag("running-timelapse").captureToImage().toPixelMap()
        val center = idle.width / 2 to idle.height / 2
        assertTrue("running stop paint must replace the idle photo disc", idle[center.first, center.second] != running[center.first, center.second])
        assertTrue("running stop center must use non-text record red", contrast(running[center.first, center.second], Color.Black) >= 3.0)
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
