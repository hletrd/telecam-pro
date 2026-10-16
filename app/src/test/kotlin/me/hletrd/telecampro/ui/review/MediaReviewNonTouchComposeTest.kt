package me.hletrd.telecampro.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaReviewNonTouchComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val geometry = reviewStillGeometry(1200, 800, 1600, 900)
    private var offset by mutableStateOf(Offset.Zero)

    private fun show(rtl: Boolean) {
        offset = Offset.Zero
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                TeleCamProTheme {
                    val actions = ReviewPanDirection.entries.mapNotNull { direction ->
                        geometry.panTarget(offset, 4f, direction)?.let {
                            ReviewPanAccessibilityAction(direction, direction.name)
                        }
                    }
                    Box(
                        Modifier
                            .size(300.dp, 200.dp)
                            .testTag("review")
                            .reviewStillNonTouchControls(
                                state = geometry.position(4f, offset).name,
                                panActions = actions,
                                onPan = { direction ->
                                    val target = geometry.panTarget(offset, 4f, direction)
                                    if (target == null) {
                                        false
                                    } else {
                                        offset = target
                                        true
                                    }
                                },
                                otherActions = emptyList(),
                            ),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `direction actions disappear at their bounds and expose coarse position`() {
        show(rtl = false)
        assertEquals(ReviewPanDirection.entries.map { it.name }, actionLabels())

        while (runAction(ReviewPanDirection.LEFT.name)) compose.waitForIdle()
        while (runAction(ReviewPanDirection.UP.name)) compose.waitForIdle()

        compose.onNodeWithTag("review").assert(hasStateDescription(ReviewStillPosition.TOP_LEFT.name))
        assertFalse(ReviewPanDirection.LEFT.name in actionLabels())
        assertFalse(ReviewPanDirection.UP.name in actionLabels())
        assertTrue(ReviewPanDirection.RIGHT.name in actionLabels())
        assertTrue(ReviewPanDirection.DOWN.name in actionLabels())
    }

    @Test
    fun `arrow panning follows physical image directions in LTR`() = assertArrowPanning(rtl = false)

    @Test
    fun `arrow panning follows physical image directions in RTL`() = assertArrowPanning(rtl = true)

    @Test
    fun `directional navigation exposes every cardinal position`() {
        show(rtl = false)
        val node = compose.onNodeWithTag("review").requestFocus().assertIsFocused()

        fun press(key: Key, count: Int) {
            repeat(count) {
                node.performKeyInput { pressKey(key) }
                compose.waitForIdle()
            }
        }

        press(Key.DirectionLeft, 3)
        node.assert(hasStateDescription(ReviewStillPosition.LEFT.name))
        press(Key.DirectionRight, 3)
        node.assert(hasStateDescription(ReviewStillPosition.CENTER.name))
        press(Key.DirectionRight, 3)
        node.assert(hasStateDescription(ReviewStillPosition.RIGHT.name))
        press(Key.DirectionLeft, 3)
        press(Key.DirectionUp, 2)
        node.assert(hasStateDescription(ReviewStillPosition.TOP.name))
        press(Key.DirectionDown, 2)
        press(Key.DirectionDown, 2)
        node.assert(hasStateDescription(ReviewStillPosition.BOTTOM.name))
    }

    private fun assertArrowPanning(rtl: Boolean) {
        show(rtl)
        val node = compose.onNodeWithTag("review").requestFocus().assertIsFocused()

        node.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertEquals("rtl=$rtl", Offset(300f, 0f), offset)
        node.performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals("rtl=$rtl", Offset.Zero, offset)
        node.performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        assertEquals("rtl=$rtl", Offset(0f, 200f), offset)
        node.performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals("rtl=$rtl", Offset.Zero, offset)
    }

    private fun actionLabels(): List<String> = compose.onNodeWithTag("review")
        .fetchSemanticsNode()
        .config[SemanticsActions.CustomActions]
        .map { it.label }

    private fun runAction(label: String): Boolean = compose.onNodeWithTag("review")
        .fetchSemanticsNode()
        .config[SemanticsActions.CustomActions]
        .singleOrNull { it.label == label }
        ?.action()
        ?: false
}
