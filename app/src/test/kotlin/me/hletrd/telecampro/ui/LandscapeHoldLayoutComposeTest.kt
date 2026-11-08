package me.hletrd.telecampro.ui

import android.content.Context
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone held sideways, through the production CameraScreen at PMA110's 411 x 905 dp. The
 * activity stays portrait-locked; only readouts turn. Three regressions found on 2026-11-08: a
 * rotated mode label grew the bottom cluster and the seam rule lifted the focal rail ~105 dp onto
 * the image, the turned OSD strip ran through the M exposure meter, and the REC/scope stack reached
 * the Fn chip row. Plus AFL, which sat at the strip's tail and was scrolled out of view.
 */
@RunWith(RobolectricTestRunner::class)
// NATIVE: the layout under test depends on real text metrics; the legacy shadow measures a label
// as a few pixels, so a rotated pill never outgrows its 48 dp slot and the regression hides.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h905dp-xxhdpi")
class LandscapeHoldLayoutComposeTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val actions: CameraActions = Proxy.newProxyInstance(
        CameraActions::class.java.classLoader,
        arrayOf(CameraActions::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as CameraActions

    private val ready = CameraUiState(
        cameraReady = true,
        cameraReadyEstablished = true,
        photoSessionOutputs = PhotoSessionOutputs(processed = true),
        batteryPct = 82,
        freeBytes = 120_000_000_000L,
    )

    private val busyVideo = ready.copy(
        mode = CaptureMode.VIDEO,
        isRecording = true,
        recordElapsedMs = 83_000,
        recordAudio = true,
        histogram = true,
        waveform = true,
        lens = LensChoice.TELE3X,
        teleconverterMode = true,
        punchIn = true,
        controls = ready.controls.copy(
            exposureMode = ExposureMode.MANUAL,
            meteringMode = MeteringMode.SPOT,
            aeLock = true,
            awbLock = true,
            afLock = true,
        ),
    )

    private val state = mutableStateOf(ready)

    private fun show(initial: CameraUiState, details: Boolean) {
        state.value = initial
        compose.setContent {
            TeleCamProTheme {
                CameraScreen(
                    state = state.value,
                    actions = actions,
                    previewViewFactory = { View(it) },
                    windowRotationOverrideDeg = 0,
                )
            }
        }
        if (details) {
            compose.onNodeWithContentDescription(context.getString(R.string.a11y_show_shooting_info))
                .performClick()
        }
        compose.waitForIdle()
    }

    private fun hold(orientation: Int) {
        state.value = state.value.copy(deviceOrientation = orientation)
        compose.waitForIdle()
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun visible(rect: Rect) = rect.width > 0f && rect.height > 0f

    private fun textBounds(text: String): List<Rect> =
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun described(description: String): Rect =
        compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true)
            .fetchSemanticsNodes().first().boundsInRoot

    @Test fun `turning the phone moves neither the mode row nor the focal rail`() {
        // Recording in M through the converter: the state whose 4:3 preview edge lands just above
        // the rail, where the rotated mode labels' extra 16 dp tripped the seam rule.
        show(busyVideo, details = false)
        val video = context.getString(R.string.a11y_mode_button, context.getString(R.string.mode_video))
        val fn = context.getString(R.string.a11y_open_function_menu)
        hold(0)
        val portraitMode = described(video).top
        val portraitFn = described(fn).top
        for (orientation in listOf(90, 270)) {
            hold(orientation)
            assertEquals("mode row at $orientation", portraitMode, described(video).top, 0.5f)
            assertEquals("Fn row at $orientation", portraitFn, described(fn).top, 0.5f)
        }
    }

    @Test fun `held sideways each lane stops above what sits below it`() {
        show(busyVideo, details = true)
        val meter = context.getString(R.string.a11y_exposure_meter)
        val stripTags = listOf("AEL", "AWL", "AFL", "SPOT", "709")
        val stack = listOf("Recording", "Audio level meter", "Histogram", "Waveform")
        for (orientation in listOf(90, 270)) {
            hold(orientation)
            val meterTop = described(meter).top
            stripTags.flatMap(::textBounds).filter(::visible).forEach { tag ->
                assertTrue("strip tag at $orientation ends ${tag.bottom} over meter $meterTop", tag.bottom <= meterTop)
            }
            // The dial-chip row is the top of the bottom chrome in this DISP state.
            val chromeTop = described(context.getString(R.string.label_mode)).top
            val stackRects = stack.map(::described).filter(::visible)
            assertEquals("every stack item drawn at $orientation", stack.size, stackRects.size)
            stackRects.forEach { rect ->
                assertTrue("stack item at $orientation ends ${rect.bottom} over chrome $chromeTop", rect.bottom <= chromeTop)
            }
            stackRects.forEachIndexed { i, a ->
                stackRects.drop(i + 1).forEach { b ->
                    assertTrue("stack items overlap at $orientation: $a / $b", !a.overlaps(b))
                }
            }
        }
    }

    // The Loupe Overview sits at the bottom-right of the image, on the stack's edge once the phone
    // turns; the stack ran over it (host sweep 2026-11-09, both turns, shooting info open).
    @Test fun `held sideways the stack stops above the Loupe Overview`() {
        show(busyVideo.copy(teleFinder = true), details = true)
        val stack = listOf("Recording", "Audio level meter", "Histogram", "Waveform")
        for (orientation in listOf(90, 270)) {
            hold(orientation)
            val overview = described(context.getString(R.string.a11y_loupe_overview))
            assertTrue("overview drawn at $orientation", visible(overview))
            stack.map(::described).filter(::visible).forEach { rect ->
                assertTrue("stack item at $orientation $rect over overview $overview", !rect.overlaps(overview))
            }
        }
    }

    /** Layout bounds, not the clipped [bounds]: a chip the rail covers still has its full slot. */
    private fun slot(description: String): Rect =
        compose.onAllNodes(hasContentDescription(description), useUnmergedTree = true)
            .fetchSemanticsNodes().first().let { Rect(it.positionInRoot, it.size.toSize()) }

    // 840 dp tall puts the 4:3 preview's bottom edge across the focal rail, so the seam rule lifts
    // it. On PMA110 the status bar does the same at 905 dp (device-seen 2026-11-09, held sideways
    // with the shooting info open): the lifted rail landed on the dial chips above it.
    @Config(qualifiers = "w411dp-h840dp-xxhdpi")
    @Test fun `a rail the seam rule lifts takes the dial chips with it`() {
        show(busyVideo.copy(isRecording = false), details = true)
        for (orientation in listOf(0, 90)) {
            hold(orientation)
            val dial = slot(context.getString(R.string.label_mode))
            val rail = slot("13× zoom")
            assertTrue("dial row at $orientation ends ${dial.bottom} over rail ${rail.top}", dial.bottom <= rail.top)
        }
    }

    @Test fun `AF lock stays on screen in a full strip, upright and sideways`() {
        show(busyVideo, details = true)
        for (orientation in listOf(0, 90, 270)) {
            hold(orientation)
            assertTrue("AFL drawn at $orientation", textBounds("AFL").any(::visible))
        }
    }
}
