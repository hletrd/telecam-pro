package com.hletrd.findx9tele.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.hletrd.findx9tele.camera.CameraEngine
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.ManualControls
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric tests for [CameraViewModel]'s Handler-driven timing seams — the 40 ms controls
 * throttle, the 16 ms zoom coalescer, and the self-timer countdown — made deterministic by the
 * paused shadow main looper (`idleFor` advances the clock and runs exactly the due tasks).
 *
 * Engine strategy: same as CameraViewModelRobolectricTest — a REAL, never-resumed `CameraEngine`
 * injected through the constructor seam (subclass-faking is blocked: the class is final). Two
 * class-specific observation notes:
 *  - The throttle's ENGINE-side landing is read from the engine's private `controls` packet via
 *    reflection (read-only): the engine exposes no accessor, the controller that would receive the
 *    packet is pre-open null, and adding a production accessor for a test would be a worse seam.
 *  - `startCountdown` is reached via reflection because its ONLY production door
 *    (`dispatchPhotoShutter`, pinned pure in ShutterPolicyTest) requires `stillCaptureReady`,
 *    which is accepted-session truth a never-resumed final engine can never honestly publish.
 *    The reflection call tests the private ticking Runnable's cadence, not the entry policy.
 */
@RunWith(RobolectricTestRunner::class)
class CameraViewModelTickersRobolectricTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var engine: CameraEngine
    private lateinit var vm: CameraViewModel

    @Before fun setUp() {
        RobolectricEglSentinels.ensure() // GlPipeline field init reads EGL14.EGL_NO_SURFACE
        engine = CameraEngine(app)
        vm = CameraViewModel(app, engine)
        // Land init's own throttled apply (refreshProgramAppSide schedules one at +40 ms) so each
        // test starts from a settled applyScheduled=false window.
        idleFor(50)
    }

    @After fun tearDown() {
        // onCleared is protected (androidx contract) — see CameraViewModelRobolectricTest.tearDown.
        runCatching {
            CameraViewModel::class.java.getDeclaredMethod("onCleared")
                .apply { isAccessible = true }
                .invoke(vm)
        }
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Read-only reflection view of the engine's applied controls packet (see class header). */
    private fun engineControls(): ManualControls =
        CameraEngine::class.java.getDeclaredField("controls")
            .apply { isAccessible = true }
            .get(engine) as ManualControls

    private fun startCountdownViaReflection(seconds: Int) {
        CameraViewModel::class.java.getDeclaredMethod("startCountdown", Int::class.java)
            .apply { isAccessible = true }
            .invoke(vm, seconds)
    }

    // ---- updateControls: 40 ms trailing THROTTLE (not a debounce) ----

    @Test fun `updateControls publishes state immediately but lands the newest value on the 40ms edge`() {
        assertEquals(95, engineControls().jpegQuality) // the ManualControls default
        vm.onJpegQuality(80)
        vm.onJpegQuality(63)
        // UI state tracks every change synchronously; the engine apply is the throttled edge.
        assertEquals(63, vm.state.value.controls.jpegQuality)
        assertEquals(95, engineControls().jpegQuality)
        idleFor(39)
        assertEquals(95, engineControls().jpegQuality)
        idleFor(1)
        // ONE engine apply carrying the NEWEST value: 80 was coalesced away, never applied.
        assertEquals(63, engineControls().jpegQuality)
    }

    @Test fun `a change inside the throttle window refreshes the pending packet, not the timer`() {
        vm.onJpegQuality(80)
        idleFor(30)
        // Still inside the window opened at t=0 — this refreshes pendingControls only. A debounce
        // would RESET the timer here and starve a sustained gesture (the documented bug class).
        vm.onJpegQuality(70)
        idleFor(10) // t=40: the ORIGINAL deadline fires with the refreshed value
        assertEquals(70, engineControls().jpegQuality)
    }

    // ---- Zoom coalescer: leading apply + 16 ms trailing flush of the newest value ----

    @Test fun `zoom inputs apply on the leading edge and coalesce to the newest at the 16ms flush`() {
        vm.onZoomRatio(2f)
        // Leading edge: the first tick lands instantly (no perceived latency).
        assertEquals(2f, vm.state.value.controls.zoomRatio)
        vm.onZoomRatio(2.5f)
        vm.onZoomRatio(3.4f)
        // Mid-window ticks only refresh the pending value — state (and the engine fast path)
        // publish per flush window, not per input event (~120 Hz input vs ~60 Hz flush).
        assertEquals(2f, vm.state.value.controls.zoomRatio)
        idleFor(16)
        assertEquals(3.4f, vm.state.value.controls.zoomRatio)
        // The lens chip band follows the flushed zoom on the rear photo seamless camera.
        assertEquals(LensChoice.TELE3X, vm.state.value.lens)
    }

    @Test fun `a fresh gesture after the flush window opens a new leading edge`() {
        vm.onZoomRatio(2f)
        idleFor(16)
        assertEquals(2f, vm.state.value.controls.zoomRatio)
        vm.onZoomRatio(1.5f)
        // New window: leading edge applies instantly again.
        assertEquals(1.5f, vm.state.value.controls.zoomRatio)
        idleFor(16)
        assertEquals(1.5f, vm.state.value.controls.zoomRatio)
    }

    // ---- Self-timer countdown ----

    @Test fun `countdown ticks once per second and a shutter press while counting cancels`() {
        startCountdownViaReflection(3)
        assertEquals(3, vm.state.value.timerCountdownSec)
        idleFor(1_000)
        assertEquals(2, vm.state.value.timerCountdownSec)
        idleFor(1_000)
        assertEquals(1, vm.state.value.timerCountdownSec)
        // The primary shutter is a CANCEL action while a countdown runs (dispatchPhotoShutter's
        // first branch — reachable through the real public entry point).
        vm.onCapturePhoto()
        assertEquals(0, vm.state.value.timerCountdownSec)
        idleFor(2_000)
        // Cancelled means cancelled: no late tick, no fire (no status from a capture attempt).
        assertEquals(0, vm.state.value.timerCountdownSec)
        assertNull(vm.state.value.statusMessage)
    }

    @Test fun `countdown reaching zero fires the shutter against the truthful unready session`() {
        startCountdownViaReflection(1)
        assertEquals(1, vm.state.value.timerCountdownSec)
        idleFor(1_000)
        assertEquals(0, vm.state.value.timerCountdownSec)
        // The fire happened: with no accepted session (engine never resumed), capturePhoto's
        // admission gate declines with the authoritative status instead of a silent dead press.
        assertEquals("Camera reconfiguring", vm.state.value.statusMessage)
    }
}
