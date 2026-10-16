package me.hletrd.telecampro.ui.overlays

import android.util.Size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.FocusConfidenceSource
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp-xxhdpi")
class StatusBarScrollComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: MutableState<CameraUiState>

    private fun show(rtl: Boolean) {
        state = mutableStateOf(
            CameraUiState(
                mode = CaptureMode.VIDEO,
                videoResolution = Size(3840, 2160),
                videoCodec = VideoCodec.HEVC,
                transfer = ColorTransfer.SLOG3_CINE,
                gammaAssist = true,
                openGate = true,
                recordAudio = false,
                videoStabMode = VideoStabMode.ENHANCED,
                controls = CameraUiState().controls.copy(
                    meteringMode = MeteringMode.SPOT,
                    aeLock = true,
                    awbLock = true,
                    afLock = true,
                ),
                focusConfidence = FocusConfidenceSource.FRAME_DETAIL,
                punchIn = true,
            ),
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                TeleCamProTheme {
                    // Keep the production scroll owner intact while forcing a viewport narrower
                    // than even its first video-spec tag. A wider fixture can fit on Robolectric's
                    // fallback font metrics and silently stop exercising overflow.
                    Box(Modifier.width(96.dp)) {
                        StatusBar(
                            state = state.value,
                            modifier = Modifier.testTag(OSD_TAG),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("fixture must overflow", scrollRange().maxValue() > 0f)
    }

    private fun scrollRange() = compose.onNodeWithTag(OSD_TAG)
        .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]

    private fun scrollAwayFromStart(rtl: Boolean): Float {
        compose.onNodeWithTag(OSD_TAG).performTouchInput {
            if (rtl) swipeRight() else swipeLeft()
        }
        compose.waitForIdle()
        return scrollRange().value().also { value ->
            assertTrue("OSD did not leave logical Start in ${if (rtl) "RTL" else "LTR"}", value > 0f)
        }
    }

    private fun assertPriorityMutationRestoresStart(
        rtl: Boolean,
        mutation: (CameraUiState) -> CameraUiState,
    ) {
        scrollAwayFromStart(rtl)
        compose.runOnIdle { state.value = mutation(state.value) }
        compose.waitForIdle()
        assertEquals(0f, scrollRange().value(), 0.5f)
    }

    private fun assertPriorityFieldsRestoreStart(rtl: Boolean) {
        show(rtl)
        assertPriorityMutationRestoresStart(rtl) { it.copy(transfer = ColorTransfer.SLOG3) }
        assertPriorityMutationRestoresStart(rtl) { it.copy(gammaAssist = false) }
        assertPriorityMutationRestoresStart(rtl) { it.copy(openGate = false) }
        assertPriorityMutationRestoresStart(rtl) { it.copy(recordAudio = true) }
        assertPriorityMutationRestoresStart(rtl) { it.copy(videoStabMode = VideoStabMode.STANDARD) }
    }

    @Test
    fun `leading video tag changes restore logical Start in LTR`() {
        assertPriorityFieldsRestoreStart(rtl = false)
    }

    @Test
    fun `leading video tag changes restore logical Start in RTL`() {
        assertPriorityFieldsRestoreStart(rtl = true)
    }

    @Test
    fun `telemetry and tail-only analysis preserve an inspected tail`() {
        show(rtl = false)
        val before = scrollAwayFromStart(rtl = false)
        compose.runOnIdle {
            state.value = state.value.copy(
                recordElapsedMs = 12_345,
                audioLevels = listOf(0.3f, 0.8f),
                // Keep both tail slots present so any position change can only come from the reset
                // effect, not ScrollState clamping after content becomes shorter.
                focusConfidence = FocusConfidenceSource.AF_LIMIT,
                controls = state.value.controls.copy(meteringMode = MeteringMode.CENTER),
            )
        }
        compose.waitForIdle()
        assertEquals(before, scrollRange().value(), 0.5f)
    }

    private companion object {
        const val OSD_TAG = "scrollable-osd"
    }
}
