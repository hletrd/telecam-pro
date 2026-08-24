package me.hletrd.telecampro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
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
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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

            val diameter = minOf(focused.width, focused.height).toFloat()
            val radiusRange = if (frame == Color.White) {
                diameter * 0.43f..diameter * 0.50f // black outer ring
            } else {
                diameter * 0.38f..diameter * 0.45f // inset Accent ring
            }
            val coveredBins = focusRingContrastBins(
                before = before,
                focused = focused,
                frame = frame,
                radiusRange = radiusRange,
            )
            assertTrue(
                "$name rendered focus ring covered only $coveredBins/$FOCUS_RING_ANGLE_BINS angular bins",
                focusRingCoverageSufficient(coveredBins),
            )
        }
    }

    @Test
    fun `focus ring coverage rejects a pixel and short arc but accepts a circumference`() {
        assertTrue(!focusRingCoverageSufficient(1))
        assertTrue(!focusRingCoverageSufficient(FOCUS_RING_ANGLE_BINS / 4))
        assertTrue(focusRingCoverageSufficient(FOCUS_RING_REQUIRED_BINS))
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

    private fun focusRingContrastBins(
        before: PixelMap,
        focused: PixelMap,
        frame: Color,
        radiusRange: ClosedFloatingPointRange<Float>,
    ): Int {
        val centerX = (focused.width - 1) / 2f
        val centerY = (focused.height - 1) / 2f
        return (0 until FOCUS_RING_ANGLE_BINS).count { bin ->
            val angle = 2.0 * PI * (bin + 0.5) / FOCUS_RING_ANGLE_BINS
            val start = radiusRange.start.roundToInt()
            val end = radiusRange.endInclusive.roundToInt()
            (start..end).any { radius ->
                val x = (centerX + cos(angle).toFloat() * radius).roundToInt()
                val y = (centerY + sin(angle).toFloat() * radius).roundToInt()
                x in 0 until focused.width && y in 0 until focused.height &&
                    before[x, y] != focused[x, y] && contrast(focused[x, y], frame) >= 3.0
            }
        }
    }

    private fun focusRingCoverageSufficient(coveredBins: Int): Boolean =
        coveredBins >= FOCUS_RING_REQUIRED_BINS

    private companion object {
        const val FOCUS_RING_ANGLE_BINS = 72
        val FOCUS_RING_REQUIRED_BINS = ceil(FOCUS_RING_ANGLE_BINS * 0.8).toInt()
    }
}
