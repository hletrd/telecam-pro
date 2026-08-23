package me.hletrd.telecampro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.ui.overlays.StatusBar
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The status plate (STEADY / OIS+ / MUTE / 4:3 …) counter-rotates with the phone like every other
 * readout. It was left screen-fixed for years because the only tool was `Modifier.rotate`, a DRAW
 * transform that leaves the UNROTATED box in layout — so a turned plate overhung its slot.
 *
 * `rotateLayout`'s geometry helpers are unit-tested on their own, and the phone derives orientation
 * from GRAVITY (held flat it holds its last value), so the turned LOOK cannot be captured over adb.
 * What CAN be pinned host-side is the property that actually made this risky: a WIDE plate, once
 * turned 90°, must still fit the slot its parent gives it.
 *
 * The measurement trick: `rotateLayout` calls `layout(...)` with the ROTATED bounds, so a
 * wrap-content Box around it takes exactly those bounds. Tagging that wrapper reads the reserved
 * slot without adding a test hook to production. Measuring a CHILD instead would be vacuous —
 * rotateLayout clips to its own bounds, so any child is inside by construction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class StatusPlateRotationTest {

    @get:Rule
    val compose = createComposeRule()

    /** Video mode with every optional tag on — the widest the plate ever gets. */
    private val busyState = CameraUiState(
        mode = CaptureMode.VIDEO,
        videoStabMode = VideoStabMode.ENHANCED,
        recordAudio = false,
        openGate = true,
    )

    private val slot = 360.dp

    /**
     * Rotation is driven through STATE inside ONE composition rather than a fresh `setContent` per
     * angle — the rule allows only one, and this also matches the device, where `overlayRotation` is
     * an animated float flowing through a composition that never restarts.
     */
    private val rotation = mutableFloatStateOf(0f)

    private fun show(degrees: Float) {
        if (!composed) {
            composed = true
            compose.mainClock.autoAdvance = true
            compose.setContent {
                TeleCamProTheme {
                    Box(modifier = Modifier.size(slot)) {
                        Box(modifier = Modifier.testTag("plate")) {
                            StatusBar(
                                state = busyState,
                                modifier = Modifier.rotateLayout(rotation.floatValue),
                            )
                        }
                    }
                }
            }
        }
        rotation.floatValue = degrees
        compose.waitForIdle()
    }

    private var composed = false

    private fun plateBounds() = compose.onNodeWithTag("plate").fetchSemanticsNode().boundsInRoot

    @Test
    fun `the busiest plate still fits its slot after a quarter turn`() {
        // The regression this guards: a plate wider than the slot is tall would, once turned,
        // reserve a box taller than its parent and shove the whole top chrome around. rotateLayout
        // clamps the reserved bounds to the parent's constraints — this proves the plate is really
        // wired through that clamp, not merely drawn rotated.
        show(90f)
        val b = plateBounds()
        assertTrue("rotated plate overflows slot width: $b", b.width <= slot.value + 1f)
        assertTrue("rotated plate overflows slot height: $b", b.height <= slot.value + 1f)
    }

    @Test
    fun `a quarter turn actually changes the reserved box`() {
        // Guards the opposite failure: passing 0f by accident, or wiring a draw-only rotate, would
        // leave the reserved bounds identical and make the fit assertion above pass vacuously.
        show(0f)
        val flat = plateBounds()
        show(90f)
        val turned = plateBounds()
        assertTrue(
            "reserved box unchanged across a quarter turn (flat=$flat turned=$turned)",
            turned.height > flat.height,
        )
    }

    @Test
    fun `the plate survives every orientation gravity can report`() {
        // Gravity yields exactly 0/90/180/270; none of them may drop or collapse the plate.
        listOf(0f, 90f, 180f, 270f).forEach { deg ->
            show(deg)
            compose.onNodeWithTag("plate").assertIsDisplayed()
            val b = plateBounds()
            assertTrue("plate collapsed at $deg: $b", b.width > 0f && b.height > 0f)
        }
    }
}
