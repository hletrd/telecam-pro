package com.hletrd.findx9tele

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hletrd.findx9tele.ui.CameraViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented SMOKE tier (docs/TESTING.md): the external device-tests/ harness owns functional
 * depth — this suite exists to drive the real MainActivity → CameraViewModel → CameraEngine →
 * Camera2/GL path in-process so instrumented coverage attributes real lines. Every test is
 * independent (its own ActivityScenario) and crash-focused: no captures, no control changes,
 * nothing persisted beyond what a normal launch/background cycle already writes (the on-stop
 * settings save rewrites the values the launch restored — the suite never mutates one).
 *
 * Camera readiness is observed through the SAME ViewModel instance MainActivity uses:
 * ComponentActivity's `by viewModels()` and `ViewModelProvider(activity)` share one
 * ViewModelStore and default factory, so no production seam is needed. `cameraReady` is
 * CLAUDE.md's "Ready" truth — accepted session, atomically committed, only after the first
 * successful real-camera-frame preview swap — the strongest cheap health signal available
 * without touching a single control.
 *
 * Runtime permissions: CAMERA/RECORD_AUDIO are pre-granted by hand on this ColorOS device and
 * preserved across replace-installs. GrantPermissionRule must NOT be used for them (`pm grant`
 * fails on ColorOS, so a dropped grant is unrecoverable from the host).
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Before
    fun wakeDevice() {
        // The suite must not depend on operator screen state: launching behind the keyguard
        // delivers onStop mid-session-config (CLAUDE.md lifecycle race) and the activity never
        // resumes. The test device has no secure keyguard; dismiss-keyguard clears the swipe
        // layer, and MainActivity's FLAG_KEEP_SCREEN_ON holds the display for the rest of a test.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @Test
    fun launchReachesResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun cameraBecomesReadyAfterLaunch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitCameraReady(viewModelOf(scenario), phase = "cold launch")
        }
    }

    @Test
    fun recreateCycleReturnsToReady() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitCameraReady(viewModelOf(scenario), phase = "pre-recreate")
            // The activity is portrait-locked with configChanges covering orientation, so a
            // physical rotation never recreates it — recreate() IS the rotation-analog cycle.
            scenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            // The ViewModel is retained across recreate; Ready must come back after the
            // onStop/onStart → engine pause/resume pair the recreate drives, on the NEW
            // TextureView surface the recreated activity provides.
            awaitCameraReady(viewModelOf(scenario), phase = "post-recreate")
        }
    }

    @Test
    fun backgroundForegroundCycleReturnsToReady() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = viewModelOf(scenario)
            awaitCameraReady(vm, phase = "pre-background")
            // CREATED = stopped-but-not-destroyed: exercises MainActivity.onStop → vm.onStop →
            // engine.pause (the background door that must never race the session, CLAUDE.md).
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitCameraReady(vm, phase = "post-foreground")
        }
    }

    // -- helpers ---------------------------------------------------------------------------------

    private fun viewModelOf(scenario: ActivityScenario<MainActivity>): CameraViewModel {
        lateinit var vm: CameraViewModel
        // onActivity runs synchronously on the main thread — ViewModelProvider is main-thread-only.
        scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[CameraViewModel::class.java]
        }
        return vm
    }

    private fun awaitCameraReady(vm: CameraViewModel, phase: String) {
        val deadline = SystemClock.elapsedRealtime() + CAMERA_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            // StateFlow.value is thread-safe to read from the instrumentation thread; polling
            // avoids blocking main and needs no coroutines machinery in this source set.
            if (vm.state.value.cameraReady) return
            SystemClock.sleep(POLL_MS)
        }
        val s = vm.state.value
        fail(
            "camera not ready ($phase) within $CAMERA_READY_TIMEOUT_MS ms: " +
                "cameraReady=${s.cameraReady} facing=${s.facing} mode=${s.mode} " +
                "status=${s.statusMessage}",
        )
    }

    private fun shell(command: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        // Drain (and thereby close) the pipe so the command has completed before the test proceeds.
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private companion object {
        // Cold open + session config + first real-frame swap lands in a few seconds on device; the
        // generous margin keeps a bounded transient-preflight retry (CLAUDE.md) from reading as a
        // flake, while a genuine wedge still fails loudly with the state snapshot in the message.
        const val CAMERA_READY_TIMEOUT_MS = 30_000L
        const val POLL_MS = 200L
    }
}
