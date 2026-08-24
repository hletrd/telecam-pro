package me.hletrd.telecampro.ui.controls

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isNotFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class SliderKeyboardComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val fraction = mutableFloatStateOf(0.5f)
    private var emissions = 0

    private fun showCameraSlider(rtl: Boolean, enabled: Boolean = true, initial: Float = 0.5f) {
        fraction.floatValue = initial
        emissions = 0
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                TeleCamProTheme {
                    CameraSlider(
                        fraction = fraction.floatValue,
                        onFraction = {
                            emissions++
                            fraction.floatValue = it
                        },
                        enabled = enabled,
                        semanticLabel = "Test slider",
                        valueDescription = "${(fraction.floatValue * 100).toInt()} percent",
                        modifier = Modifier.testTag("camera-slider"),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showRuler(rtl: Boolean, enabled: Boolean = true, initial: Float = 0.5f) {
        fraction.floatValue = initial
        emissions = 0
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                TeleCamProTheme {
                    RulerSlider(
                        fraction = fraction.floatValue,
                        onFractionChange = {
                            emissions++
                            fraction.floatValue = it
                        },
                        enabled = enabled,
                        totalUnits = 10,
                        majorEvery = 2,
                        snap = true,
                        semanticLabel = "Test ruler",
                        valueDescription = "${(fraction.floatValue * 10).toInt()}",
                        modifier = Modifier.testTag("ruler-slider"),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `settings slider accepts keyboard domain commands and exposes focus`() {
        showCameraSlider(rtl = false)
        val slider = compose.onNodeWithTag("camera-slider").requestFocus().assertIsFocused()

        slider.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(0.51f, fraction.floatValue, 0.0001f)
        slider.performKeyInput { pressKey(Key.PageUp) }
        compose.waitForIdle()
        assertEquals(0.61f, fraction.floatValue, 0.0001f)
        slider.performKeyInput { pressKey(Key.MoveHome) }
        compose.waitForIdle()
        assertEquals(0f, fraction.floatValue, 0f)
        slider.performKeyInput { pressKey(Key.MoveEnd) }
        compose.waitForIdle()
        assertEquals(1f, fraction.floatValue, 0f)
    }

    @Test
    fun `labeled settings row keeps its merged name on the keyboard focus target`() {
        fraction.floatValue = 0.5f
        emissions = 0
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            TeleCamProTheme {
                LabeledSlider(
                    label = "Gain",
                    valueLabel = "0.5",
                    value = fraction.floatValue,
                    onValueChange = {
                        emissions++
                        fraction.floatValue = it
                    },
                    valueRange = 0f..1f,
                )
            }
        }

        compose.onNodeWithContentDescription("Gain")
            .requestFocus()
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(0.51f, fraction.floatValue, 0.0001f)
        assertEquals(1, emissions)
    }

    @Test
    fun `production timelapse interval reaches every integer with keyboard commands`() {
        val interval = mutableIntStateOf(5)
        emissions = 0
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            TeleCamProTheme {
                TimelapseIntervalSlider(interval.intValue) {
                    emissions++
                    interval.intValue = it
                }
            }
        }
        val slider = compose.onNodeWithContentDescription("Interval")
            .requestFocus()
            .assertIsFocused()

        repeat(3) { slider.performKeyInput { pressKey(Key.DirectionRight) } }
        compose.waitForIdle()
        assertEquals(8, interval.intValue)

        slider.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals(7, interval.intValue)

        slider.performKeyInput { pressKey(Key.PageUp) }
        compose.waitForIdle()
        assertEquals(9, interval.intValue)
        slider.performKeyInput { pressKey(Key.PageDown) }
        compose.waitForIdle()
        assertEquals(7, interval.intValue)

        compose.runOnIdle { interval.intValue = 17 }
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(18, interval.intValue)
        slider.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals(17, interval.intValue)

        slider.performKeyInput { pressKey(Key.MoveHome) }
        compose.waitForIdle()
        assertEquals(1, interval.intValue)
        val atLowEndpoint = emissions
        slider.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals(1, interval.intValue)
        assertEquals(atLowEndpoint, emissions)

        slider.performKeyInput { pressKey(Key.MoveEnd) }
        compose.waitForIdle()
        assertEquals(30, interval.intValue)
        val atHighEndpoint = emissions
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(30, interval.intValue)
        assertEquals(atHighEndpoint, emissions)
    }

    @Test
    fun `timelapse presentation normalizes an out-of-domain restored value`() {
        compose.setContent {
            TeleCamProTheme {
                TimelapseIntervalSlider(300) { }
            }
        }

        compose.onNodeWithContentDescription("Interval")
            .assert(hasStateDescription("30s"))
    }

    @Test
    fun `settings horizontal keys mirror in RTL`() {
        showCameraSlider(rtl = true)
        val slider = compose.onNodeWithTag("camera-slider").requestFocus().assertIsFocused()

        slider.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(0.49f, fraction.floatValue, 0.0001f)
        slider.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals(0.5f, fraction.floatValue, 0.0001f)
    }

    @Test
    fun `settings touch endpoints run left to right in LTR`() {
        showCameraSlider(rtl = false)
        val slider = compose.onNodeWithTag("camera-slider")
        slider.performTouchInput { click(Offset(1f, centerY)) }
        compose.waitForIdle()
        assertEquals(0f, fraction.floatValue, 0f)
        slider.performTouchInput { click(Offset(width - 1f, centerY)) }
        compose.waitForIdle()
        assertEquals(1f, fraction.floatValue, 0f)
    }

    @Test
    fun `settings touch endpoints mirror in RTL`() {
        showCameraSlider(rtl = true)
        val slider = compose.onNodeWithTag("camera-slider")
        slider.performTouchInput { click(Offset(1f, centerY)) }
        compose.waitForIdle()
        assertEquals(1f, fraction.floatValue, 0f)
        slider.performTouchInput { click(Offset(width - 1f, centerY)) }
        compose.waitForIdle()
        assertEquals(0f, fraction.floatValue, 0f)
    }

    @Test
    fun `disabled settings slider neither focuses nor emits`() {
        showCameraSlider(rtl = false, enabled = false)
        compose.onNodeWithTag("camera-slider")
            .assertIsNotEnabled()
            .assert(isNotFocusable())
            .performTouchInput { click() }
        assertEquals(0, emissions)
    }

    @Test
    fun `disabled ruler neither focuses nor emits`() {
        showRuler(rtl = false, enabled = false)
        compose.onNodeWithTag("ruler-slider")
            .assertIsNotEnabled()
            .assert(isNotFocusable())
            .performTouchInput { swipeLeft() }
        assertEquals(0, emissions)
    }

    @Test
    fun `physical ruler keyboard and drag direction stay unchanged under RTL`() {
        showRuler(rtl = true)
        val slider = compose.onNodeWithTag("ruler-slider").requestFocus().assertIsFocused()

        slider.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(0.6f, fraction.floatValue, 0.0001f)
        slider.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue(fraction.floatValue > 0.6f)
    }
}
