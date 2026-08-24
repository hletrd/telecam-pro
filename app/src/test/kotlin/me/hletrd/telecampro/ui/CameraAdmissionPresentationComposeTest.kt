package me.hletrd.telecampro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import me.hletrd.telecampro.camera.ViewfinderFocusActionAvailability
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import me.hletrd.telecampro.ui.review.GalleryThumb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class CameraAdmissionPresentationComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shutter alpha and semantics follow healthy and fail closed state`() {
        val healthy = CameraUiState(
            mode = CaptureMode.PHOTO,
            cameraReady = true,
            photoSessionOutputs = PhotoSessionOutputs(processed = true),
        )
        val unhealthy = healthy.copy(cameraReady = false)
        val admissionRefused = healthy.copy(stillCaptureAdmissionAvailable = false)
        val countdown = unhealthy.copy(timerCountdownSec = 2)
        val recordingStop = unhealthy.copy(mode = CaptureMode.VIDEO, isRecording = true)
        val states = listOf(
            "healthy" to healthy,
            "unhealthy" to unhealthy,
            "admission-refused" to admissionRefused,
            "countdown" to countdown,
            "recording-stop" to recordingStop,
        )

        compose.setContent {
            TeleCamProTheme {
                Column(Modifier.background(Color.Black)) {
                    states.forEach { (tag, state) ->
                        ShutterButton(
                            mode = state.mode,
                            isRecording = state.isRecording,
                            timerCountdownSec = state.timerCountdownSec,
                            cameraHealthy = state.primaryShutterHealthy,
                            enabled = state.primaryShutterEnabled,
                            onClick = {},
                            modifier = Modifier.testTag(tag),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("healthy").assertIsEnabled()
        compose.onNodeWithTag("unhealthy").assertIsNotEnabled()
        compose.onNodeWithTag("admission-refused").assertIsNotEnabled()
        compose.onNodeWithTag("countdown").assertIsEnabled()
        compose.onNodeWithTag("recording-stop").assertIsEnabled()

        compose.onNodeWithTag("healthy").assert(
            SemanticsMatcher.expectValue(ShutterVisualAlpha, 1f),
        )
        compose.onNodeWithTag("unhealthy").assert(
            SemanticsMatcher.expectValue(ShutterVisualAlpha, 0.35f),
        )
        compose.onNodeWithTag("admission-refused").assert(
            SemanticsMatcher.expectValue(ShutterVisualAlpha, 0.35f),
        )
        // Timer Cancel and REC Stop keep their enabled behavior even while camera health keeps the
        // same dimmed graphics-layer presentation as before this change.
        compose.onNodeWithTag("countdown").assert(
            SemanticsMatcher.expectValue(ShutterVisualAlpha, 0.35f),
        )
        compose.onNodeWithTag("recording-stop").assert(
            SemanticsMatcher.expectValue(ShutterVisualAlpha, 0.35f),
        )
    }

    @Test
    fun `viewfinder custom action list tracks focus and reset availability`() {
        val availability = mutableStateOf(
            ViewfinderFocusActionAvailability(focusAtCenter = false, resetFocusPoint = false),
        )
        var focusActions = 0
        var resetActions = 0
        compose.setContent {
            Box(
                Modifier
                    .testTag("viewfinder")
                    .viewfinderFocusSemantics(
                        contentDescription = "Viewfinder",
                        availability = availability.value,
                        focusAtCenterLabel = "Focus at center",
                        resetFocusPointLabel = "Reset focus point",
                        onFocusAtCenter = { focusActions++ },
                        onResetFocusPoint = { resetActions++ },
                    ),
            )
        }

        assertEquals(emptyList<String>(), customActionLabels())
        availability.value = ViewfinderFocusActionAvailability(true, false)
        compose.waitForIdle()
        assertEquals(listOf("Focus at center"), customActionLabels())
        assertTrue(runCustomAction("Focus at center"))
        assertEquals(1, focusActions)
        assertEquals(0, resetActions)

        availability.value = ViewfinderFocusActionAvailability(false, true)
        compose.waitForIdle()
        assertEquals(listOf("Reset focus point"), customActionLabels())
        assertTrue(runCustomAction("Reset focus point"))
        assertEquals(1, focusActions)
        assertEquals(1, resetActions)

        availability.value = ViewfinderFocusActionAvailability(true, true)
        compose.waitForIdle()
        assertEquals(listOf("Focus at center", "Reset focus point"), customActionLabels())
    }

    @Test
    fun `review target is enabled only outside starting and active video capture`() {
        val activations = IntArray(3)
        val states = listOf(
            false to false,
            true to false,
            false to true,
        )
        compose.setContent {
            TeleCamProTheme {
                Column {
                    states.forEachIndexed { index, (starting, recording) ->
                        GalleryThumb(
                            uri = null,
                            onClick = { activations[index]++ },
                            enabled = reviewTargetEnabled(starting, recording),
                            modifier = Modifier.testTag("review-$index"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("review-0").assertIsEnabled().performClick()
        listOf(1, 2).forEach { index ->
            compose.onNodeWithTag("review-$index")
                .assertIsNotEnabled()
                // Raw pointer input proves the disabled visual target cannot be activated even
                // when an automation/accessibility client bypasses semantic performClick.
                .performTouchInput { click() }
        }
        compose.waitForIdle()
        assertEquals(listOf(1, 0, 0), activations.toList())
    }

    private fun customActionLabels(): List<String> = compose.onNodeWithTag("viewfinder")
        .fetchSemanticsNode()
        .config[SemanticsActions.CustomActions]
        .map { it.label }

    private fun runCustomAction(label: String): Boolean = compose.onNodeWithTag("viewfinder")
        .fetchSemanticsNode()
        .config[SemanticsActions.CustomActions]
        .single { it.label == label }
        .action()
}
