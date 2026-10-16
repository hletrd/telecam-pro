package me.hletrd.telecampro.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.CaptureMode
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
class SelfTimerAccessibilityComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val timerLabel = context.getString(R.string.a11y_self_timer)
    private val cancelLabel = context.getString(R.string.a11y_cancel_self_timer)
    private val countdownState = context.resources.getQuantityString(
        R.plurals.a11y_seconds_remaining,
        3,
        3,
    )

    private var cancellations = 0

    private fun showCountdown() {
        cancellations = 0
        compose.setContent {
            TeleCamProTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 320.dp, height = 640.dp)
                        .testTag("timer-root"),
                ) {
                    SelfTimerCountdownOverlay(
                        seconds = 3,
                        accessibilityLabel = timerLabel,
                        accessibilityStateDescription = countdownState,
                        rotationDegrees = 0f,
                        onCancel = { cancellations++ },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("timer-touch-surface"),
                    )
                    ShutterButton(
                        mode = CaptureMode.PHOTO,
                        isRecording = false,
                        timerCountdownSec = 3,
                        onClick = { cancellations++ },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `countdown exports one semantic cancel action`() {
        showCountdown()

        compose.onAllNodes(
            hasContentDescription(cancelLabel).and(hasClickAction()),
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    @Test
    fun `centered countdown is a bounded non-actionable polite live region`() {
        showCountdown()

        val liveNode = compose.onNode(
            hasContentDescription(timerLabel).and(hasStateDescription(countdownState)),
            useUnmergedTree = true,
        )
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            .fetchSemanticsNode()
        val root = compose.onNodeWithTag("timer-root").fetchSemanticsNode().boundsInRoot

        assertTrue("live region still spans root width: ${liveNode.boundsInRoot} vs $root", liveNode.boundsInRoot.width < root.width)
        assertTrue("live region still spans root height: ${liveNode.boundsInRoot} vs $root", liveNode.boundsInRoot.height < root.height)
    }

    @Test
    fun `full-screen touch and shutter each cancel exactly once`() {
        showCountdown()

        compose.onNodeWithTag("timer-touch-surface").performTouchInput { click() }
        assertEquals(1, cancellations)

        compose.onNodeWithContentDescription(cancelLabel).performClick()
        assertEquals(2, cancellations)
    }
}
