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
import me.hletrd.telecampro.camera.FocusMode
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

    // Owner report 2026-11-09: "the MF bar seems to be missing" — it lived only behind Fn or the Focus
    // chip. MF now keeps it on screen, with an AF key back, and the rest of the bottom chrome in place.
    @Test fun `MF keeps the focus bar and its AF key on screen, upright and sideways`() {
        val mfCaps = ViewModelTestAccess.caps().copy(minFocusDistanceDiopters = 10f, afModes = intArrayOf(0, 1, 4))
        show(ready.copy(caps = mfCaps, controls = ready.controls.copy(focusMode = FocusMode.MANUAL)), details = false)
        val bar = context.getString(R.string.a11y_focus_distance)
        val af = context.getString(R.string.a11y_return_to_af)
        val rail = context.getString(R.string.a11y_mode_button, context.getString(R.string.mode_video))
        for (orientation in listOf(0, 90, 270)) {
            hold(orientation)
            assertTrue("MF bar drawn at $orientation", visible(described(bar)))
            assertTrue("AF key drawn at $orientation", visible(described(af)))
            assertTrue("bar above the mode row at $orientation", described(bar).bottom <= described(rail).top)
        }
        state.value = state.value.copy(controls = state.value.controls.copy(focusMode = FocusMode.CONTINUOUS))
        compose.waitForIdle()
        assertTrue(
            "no bar in AF",
            compose.onAllNodes(hasContentDescription(bar), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    // UI review 2026-11-09: DISP moved Fn up into the dial-chip row, about 56 dp above where it sat.
    @Test fun `opening the shooting info leaves Fn where it was`() {
        show(busyVideo.copy(isRecording = false), details = false)
        val fn = context.getString(R.string.a11y_open_function_menu)
        val compact = described(fn)
        compose.onNodeWithContentDescription(context.getString(R.string.a11y_show_shooting_info)).performClick()
        compose.waitForIdle()
        val fnNodes = compose.onAllNodes(hasContentDescription(fn), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one Fn entry with the info open", 1, fnNodes.size)
        assertEquals("Fn row", compact.top, fnNodes.single().boundsInRoot.top, 0.5f)
        assertEquals("Fn side", compact.left, fnNodes.single().boundsInRoot.left, 0.5f)
    }

    // UI review 2026-11-09: compact packed the visible toggles left, so Grid sat at 12 dp there and
    // 124 dp with DISP on, and a toggle cycled back to its default vanished under the thumb.
    @Test fun `compact top-bar toggles keep their DISP slots and stay once touched`() {
        show(ready.copy(grid = me.hletrd.telecampro.camera.GridType.THIRDS), details = false)
        val grid = context.getString(R.string.label_grid)
        val compactGrid = described(grid)
        compose.onNodeWithContentDescription(context.getString(R.string.a11y_show_shooting_info)).performClick()
        compose.waitForIdle()
        assertEquals("grid slot", described(grid).left, compactGrid.left, 0.5f)
        compose.onNodeWithContentDescription(context.getString(R.string.a11y_hide_shooting_info)).performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription(grid), useUnmergedTree = true).performClick()
        state.value = state.value.copy(grid = me.hletrd.telecampro.camera.GridType.NONE)
        compose.waitForIdle()
        assertTrue("a touched toggle stays at its default", visible(described(grid)))
    }

    @Test fun `a compact toggle slot is drawn, reserved, or absent`() {
        assertEquals(ChromeSlot.DRAWN, chromeSlot(drawn = true, touched = false, compact = true))
        assertEquals(ChromeSlot.DRAWN, chromeSlot(drawn = false, touched = true, compact = true))
        assertEquals(ChromeSlot.RESERVED, chromeSlot(drawn = false, touched = false, compact = true))
        assertEquals(ChromeSlot.ABSENT, chromeSlot(drawn = false, touched = true, compact = false))
    }

    // UI review 2026-11-09: the fixed recording spec led the strip and pushed the state tags off it
    // ("... HEVC 39|" with STAB, LOUPE and OVERVIEW scrolled out of view).
    @Test fun `with the shooting info open the state tags fit before the recording spec`() {
        show(busyVideo.copy(teleFinder = true), details = true)
        hold(0)
        val tags = listOf(
            context.getString(R.string.osd_stabilization_standard),
            context.getString(R.string.osd_loupe),
            context.getString(R.string.osd_overview),
        )
        tags.forEach { tag -> assertTrue("$tag drawn", textBounds(tag).any(::visible)) }
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

    private fun Rect.turned() = height > width

    // UI review 2026-11-09: held sideways the exposure line and the DISP chips were the two readouts
    // still reading sideways. They turn draw-only, so nothing under them may move.
    @Test fun `held sideways the exposure line turns upright and the rail stays put`() {
        show(ready.copy(liveIso = 9100, liveExposureNs = 8_000_000L), details = false)
        val fn = context.getString(R.string.a11y_open_function_menu)
        hold(0)
        val rail = described(fn).top
        for (orientation in listOf(90, 270)) {
            hold(orientation)
            val shutter = textBounds("1/125s")
            assertTrue("shutter turned at $orientation: $shutter", shutter.isNotEmpty() && shutter.all { it.turned() })
            assertEquals("rail at $orientation", rail, described(fn).top, 0.5f)
        }
    }

    @Test fun `held sideways the dial chips turn upright in the same slots`() {
        val manual = ready.controls.copy(exposureMode = ExposureMode.MANUAL, exposureTimeNs = 1_000_000L)
        show(ready.copy(controls = manual), details = true)
        val mode = context.getString(R.string.label_mode)
        hold(0)
        val upright = slot(mode)
        assertTrue("upright chip reads across", textBounds("1/1000s").none { it.turned() })
        for (orientation in listOf(90, 270)) {
            hold(orientation)
            val held = slot(mode)
            assertEquals("chip slot top at $orientation", upright.top, held.top, 0.5f)
            assertEquals("chip slot height at $orientation", upright.height, held.height, 0.5f)
            assertTrue("chip value turned at $orientation", textBounds("1/1000s").any { it.turned() })
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
