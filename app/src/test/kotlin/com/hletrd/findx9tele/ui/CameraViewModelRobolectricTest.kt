package com.hletrd.findx9tele.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.hletrd.findx9tele.camera.CameraEngine
import com.hletrd.findx9tele.camera.CameraFacing
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.ManualControls
import com.hletrd.findx9tele.camera.ShutterTimer
import com.hletrd.findx9tele.storage.ExtraSettings
import com.hletrd.findx9tele.storage.SettingsStore
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric-driven construction/init-contract tests for [CameraViewModel] — the first host tests
 * that execute the ViewModel CLASS BODY (Partition B by policy; see docs/TESTING.md) instead of its
 * extracted pure policy.
 *
 * ENGINE STRATEGY (the truthful option of the two considered for this phase): `CameraEngine` is a
 * FINAL class, so a subclassed fake is not constructible without adding production `open` markers.
 * These tests therefore inject a REAL `CameraEngine(app)` through the ViewModel's constructor seam
 * and NEVER call `onStart()` — the camera only opens on `resume()` (CLAUDE.md lifecycle chain), and
 * everything the VM touches during construction/interaction is pre-open safe by design: every
 * controller call is `controller?.`-guarded, `GlPipeline.post` is a no-op before `start()`, and the
 * engine's executors only run inert bookkeeping (the orphan sweep runs against Robolectric's empty
 * MediaStore, where the missing provider cursor is swallowed by its own `runCatching` per
 * collection). Holding the injected engine reference also lets tests drive the VM through the
 * exact callback fields the engine would use (`onStatus` etc.), not through test-only side doors.
 *
 * Compose UI remains OUT of scope for this phase: the compose-ui-test deps land with the build
 * infra, but semantics-tree tests are a separately gated decision (lane report).
 */
@RunWith(RobolectricTestRunner::class)
class CameraViewModelRobolectricTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var vm: CameraViewModel? = null
    private var engine: CameraEngine? = null

    private fun createViewModel(): Pair<CameraViewModel, CameraEngine> {
        RobolectricEglSentinels.ensure() // GlPipeline field init reads EGL14.EGL_NO_SURFACE
        val e = CameraEngine(app)
        val v = CameraViewModel(app, e)
        vm = v
        engine = e
        return v to e
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @After fun tearDown() {
        // Detaches engine callbacks and hands the (never-started) engine to its release thread.
        // onCleared is protected (androidx contract) — reflection is the lifecycle-honest teardown
        // without dragging a ViewModelProvider/Store harness into every test.
        runCatching {
            vm?.let { v ->
                CameraViewModel::class.java.getDeclaredMethod("onCleared")
                    .apply { isAccessible = true }
                    .invoke(v)
            }
        }
    }

    // ---- Construction / init contract ----

    @Test fun `fresh launch publishes the documented CameraUiState defaults`() {
        val (v, _) = createViewModel()
        val s = v.state.value
        // Fresh launch is the 1× main lens, rear camera, TELE off, photo mode (CLAUDE.md contract).
        assertEquals(CaptureMode.PHOTO, s.mode)
        assertEquals(LensChoice.MAIN, s.lens)
        assertEquals(CameraFacing.BACK, s.facing)
        assertFalse(s.teleconverterMode)
        // Camera health starts NOT ready — only an owned engine Ready publication may set it.
        assertFalse(s.cameraReady)
        assertNull(s.statusMessage)
        assertEquals(ShutterTimer.OFF, s.timer)
        assertEquals(0, s.timerCountdownSec)
        // "Remember settings" defaults ON even when nothing was ever saved.
        assertTrue(s.rememberSettings)
        // init's refreshProgramAppSide: photo PROGRAM with flash OFF runs the APP-SIDE program line
        // (the HAL AE takes no min-shutter hint), published synchronously during construction.
        assertEquals(ExposureMode.PROGRAM, s.controls.exposureMode)
        assertTrue(s.controls.programAppSide)
        assertEquals(1f, s.controls.zoomRatio)
    }

    @Test fun `init wires every engine callback the ViewModel depends on`() {
        val (_, e) = createViewModel()
        // The init block's callback assignments are the VM↔engine contract: a missing wire means a
        // whole feature silently dies (status toasts, review ownership, Ready gating, tap AF...).
        assertNotNull(e.onStatus)
        assertNotNull(e.onTapFocusChange)
        assertNotNull(e.onCapsReady)
        assertNotNull(e.onVideoSizeChosen)
        assertNotNull(e.onPreviewAspect)
        assertNotNull(e.onCameraReadyChange)
        assertNotNull(e.onOpticsRollback)
        assertNotNull(e.onAfIndication)
        assertNotNull(e.onAnalysis)
        assertNotNull(e.onAudioLevel)
        assertNotNull(e.onAudioRoute)
        assertNotNull(e.onStandbyAudioAvailable)
        assertNotNull(e.onStandbyAudioUnavailable)
        assertNotNull(e.onRecordingStarted)
        assertNotNull(e.onRecordingTerminated)
        assertNotNull(e.onExposureInfo)
        assertNotNull(e.onFocusDistance)
        assertNotNull(e.onMediaSaved)
        assertNotNull(e.onRawSaved)
    }

    @Test fun `a persisted settings packet restores through the store during construction`() {
        // Seed the REAL SharedPreferences file ("camera_settings") the VM's own store reads —
        // the exact restore wiring, not a fake around it.
        SettingsStore(app).save(
            ManualControls(exposureMode = ExposureMode.MANUAL, iso = 1600),
            ExtraSettings(mode = CaptureMode.VIDEO),
        )
        val (v, _) = createViewModel()
        val s = v.state.value
        assertTrue(s.rememberSettings)
        assertEquals(CaptureMode.VIDEO, s.mode)
        assertEquals(ExposureMode.MANUAL, s.controls.exposureMode)
        assertEquals(1600, s.controls.iso)
        // Facing is deliberately never persisted — a restored packet is rear-route optics.
        assertEquals(CameraFacing.BACK, s.facing)
    }

    // ---- Status auto-clear (Handler-timed, deterministic via the shadow main looper) ----

    @Test fun `engine status publications auto-clear after the ordinary display duration`() {
        val (v, e) = createViewModel()
        e.onStatus!!.invoke("Test status")
        assertEquals("Test status", v.state.value.statusMessage)
        idleFor(2_499)
        assertEquals("Test status", v.state.value.statusMessage)
        idleFor(1) // ordinary messages clear at 2.5 s (statusDisplayDurationMs)
        assertNull(v.state.value.statusMessage)
    }

    @Test fun `saved-confirmation statuses clear on the shorter timer and re-arm per message`() {
        val (v, e) = createViewModel()
        e.onStatus!!.invoke("Video saved")
        idleFor(1_400)
        assertEquals("Video saved", v.state.value.statusMessage)
        // A newer message replaces the text AND re-arms its own timer; the old deadline is dead.
        e.onStatus!!.invoke("Photo saved")
        idleFor(1_499)
        assertEquals("Photo saved", v.state.value.statusMessage)
        idleFor(1) // "saved" confirmations clear at 1.5 s
        assertNull(v.state.value.statusMessage)
    }

    // ---- Mode change ----

    @Test fun `mode change publishes the video optics and returns photo faithfully`() {
        val (v, _) = createViewModel()
        assertTrue(v.state.value.controls.programAppSide)
        v.onModeChange(CaptureMode.VIDEO)
        val video = v.state.value
        assertEquals(CaptureMode.VIDEO, video.mode)
        // Video PROGRAM stays on the HAL AE (flash metering/cadence); only photo-P runs app-side.
        assertFalse(video.controls.programAppSide)
        v.onModeChange(CaptureMode.PHOTO)
        val photo = v.state.value
        assertEquals(CaptureMode.PHOTO, photo.mode)
        assertTrue(photo.controls.programAppSide)
        // The photographer's Photo shutter survives the round trip (the hidden photo bank).
        assertEquals(ManualControls().exposureTimeNs, photo.controls.exposureTimeNs)
    }
}
