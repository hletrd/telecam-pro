package me.hletrd.telecampro.ui

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class ModalTimerComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var vm: CameraViewModel
    private lateinit var engine: CameraEngine

    @Before
    fun setUp() {
        RobolectricEglSentinels.ensure()
        engine = CameraEngine(app)
        vm = CameraViewModel(app, engine)
    }

    @After
    fun tearDown() {
        CameraViewModel::class.java.getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(vm)
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun showArmedTimer(modalLabel: String) {
        // Camera readiness is orthogonal here: arm the real one-shot scheduler directly, then
        // exercise the production Compose modal door that must acquire and cancel its ownership.
        CameraViewModel::class.java.getDeclaredMethod("startCountdown", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(vm, 3)
        assertEquals(3, vm.state.value.timerCountdownSec)
        compose.setContent {
            TeleCamProTheme {
                val state by vm.state.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    SelfTimerCountdownOverlay(
                        seconds = state.timerCountdownSec,
                        accessibilityLabel = "Self-timer",
                        accessibilityStateDescription = "3 seconds remaining",
                        rotationDegrees = 0f,
                        onCancel = vm::onCapturePhoto,
                    )
                    // Production Settings/Fn doors call this same ownership seam before making the
                    // modal visible. Keeping the harness lightweight avoids creating a TextureView
                    // and tests the ordering itself: the later sibling wins the touch, then must
                    // synchronously retire the scheduler before it could fire behind the modal.
                    Button(
                        onClick = { vm.onCameraInputBlockedChange(true) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) { Text(modalLabel) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertCancelledWithoutLateCapture() {
        assertEquals(0, vm.state.value.timerCountdownSec)
        assertTrue(vm.state.value.cameraInputBlocked)
        idleFor(3_100)
        compose.waitForIdle()
        // A leaked timer calls the never-started Engine and publishes CAMERA_RECONFIGURING.
        assertNull(vm.state.value.status)
        assertEquals(0, vm.state.value.shutterFlashTick)
    }

    @Test
    fun `Settings acquires modal ownership before a one-shot timer can fire`() {
        showArmedTimer("Settings")

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()

        assertCancelledWithoutLateCapture()
    }

    @Test
    fun `Fn acquires modal ownership before a one-shot timer can fire`() {
        showArmedTimer("Fn")

        compose.onNodeWithText("Fn").performClick()
        compose.waitForIdle()

        assertCancelledWithoutLateCapture()
    }
}
