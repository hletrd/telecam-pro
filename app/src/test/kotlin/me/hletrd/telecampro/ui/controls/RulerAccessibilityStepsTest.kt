package me.hletrd.telecampro.ui.controls

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.roundToInt

/** AGG4-64: one TalkBack swipe on a snapped ruler is exactly one detent. */
@RunWith(RobolectricTestRunner::class)
class RulerAccessibilityStepsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun `snapped rulers publish their detent count and continuous ones none`() {
        assertEquals(6, rulerSemanticSteps(snap = true, totalUnits = 7))
        assertEquals(0, rulerSemanticSteps(snap = true, totalUnits = 1))
        assertEquals(0, rulerSemanticSteps(snap = true, totalUnits = 0))
        assertEquals(0, rulerSemanticSteps(snap = false, totalUnits = 300))
    }

    @Test fun `a full-stop ISO ruler advances one detent per accessibility step`() {
        // 50–6400 at FULL step: 8 stops, 7 units. The 5 % default step rounded back to the same stop.
        val units = 7
        val fraction = mutableFloatStateOf(3f / units)
        compose.setContent {
            TeleCamProTheme {
                RulerSlider(
                    fraction = fraction.floatValue,
                    onFractionChange = { f -> fraction.floatValue = (f * units).roundToInt() / units.toFloat() },
                    totalUnits = units,
                    snap = true,
                    semanticLabel = "ISO",
                    valueDescription = "ISO",
                    modifier = Modifier.testTag("iso"),
                )
            }
        }
        val node = compose.onNodeWithTag("iso").fetchSemanticsNode()
        val range = node.config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(units - 1, range.steps)
        // What an accessibility service sends for "one step up": current + one step width.
        val stepWidth = 1f / (range.steps + 1)
        compose.onNodeWithTag("iso").performSemanticsAction(SemanticsActions.SetProgress) {
            it(fraction.floatValue + stepWidth)
        }
        compose.waitForIdle()
        assertEquals(4f / units, fraction.floatValue, 1e-4f)
    }
}
