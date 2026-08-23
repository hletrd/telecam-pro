package me.hletrd.telecampro.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import me.hletrd.telecampro.R
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

    private fun armTimer() {
        CameraViewModel::class.java.getDeclaredMethod("startCountdown", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(vm, 3)
        assertEquals(3, vm.state.value.timerCountdownSec)
    }

    private fun showArmedCameraScreen() {
        // Camera readiness is orthogonal: arm the real one-shot scheduler, then render the actual
        // production CameraScreen. Only the native preview host is substituted; shipping chrome,
        // semantics, modal state, and action wiring remain untouched.
        armTimer()
        compose.setContent {
            TeleCamProTheme {
                val state by vm.state.collectAsState()
                CameraScreen(
                    state = state,
                    actions = vm,
                    previewViewFactory = { View(it) },
                    windowRotationOverrideDeg = 0,
                )
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
    fun `direct modal ownership still cancels an armed one-shot timer`() {
        armTimer()

        vm.onCameraInputBlockedChange(true)

        assertCancelledWithoutLateCapture()
    }

    @Test
    fun `shipping Settings door cancels a one-shot timer before opening`() {
        showArmedCameraScreen()

        compose.onNodeWithContentDescription(app.getString(R.string.a11y_open_settings)).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(app.getString(R.string.a11y_close_settings)).assertExists()
        assertCancelledWithoutLateCapture()
    }

    @Test
    fun `shipping Fn door cancels a one-shot timer before opening`() {
        showArmedCameraScreen()

        compose.onNodeWithContentDescription(app.getString(R.string.a11y_open_function_menu)).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(app.getString(R.string.a11y_close_function_menu)).assertExists()
        assertCancelledWithoutLateCapture()
    }
}
