package me.hletrd.telecampro.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.time.Duration
import kotlinx.coroutines.flow.update
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.AfIndication
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import me.hletrd.telecampro.camera.offerLockSlots
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * AE/AF/AWB lock without the menu (owner request 2026-11-09): Fn toggles, a tap on a lit badge to
 * release it, and a long press on the viewfinder to lock AE and AF at that point.
 */
@RunWith(RobolectricTestRunner::class)
class LockControlsRobolectricTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun recordingActions(calls: MutableList<String>): CameraActions = Proxy.newProxyInstance(
        CameraActions::class.java.classLoader,
        arrayOf(CameraActions::class.java),
    ) { _, method, args ->
        calls += method.name + (args?.joinToString(",", "(", ")") ?: "")
        if (method.returnType == java.lang.Boolean.TYPE) false else null
    } as CameraActions

    @Test fun `a long press locks AE and AF at the point once its scan settles, and a tap lets go`() {
        RobolectricEglSentinels.ensure()
        val engine = CameraEngine(app)
        val vm = CameraViewModel(app, engine)
        try {
            ViewModelTestAccess.state(vm).update {
                it.copy(caps = ViewModelTestAccess.caps().copy(minFocusDistanceDiopters = 10f, afModes = intArrayOf(0, 1, 4)))
            }
            vm.onLongPressLock(0.3f, 0.4f)
            idleFor(100)
            assertFalse("nothing locks before the point is measured", vm.state.value.controls.afLock)
            engine.onAfIndication?.invoke(AfIndication.FOCUSED) // the previous frame's verdict
            idleFor(10)
            assertFalse("a verdict before the press's own scan is not its result", vm.state.value.controls.afLock)
            engine.onAfIndication?.invoke(AfIndication.SCANNING)
            engine.onAfIndication?.invoke(AfIndication.FOCUSED)
            idleFor(100)
            assertTrue("AF locked", vm.state.value.controls.afLock)
            assertTrue("AE locked", vm.state.value.controls.aeLock)
            vm.onTapFocus(0.6f, 0.6f)
            idleFor(100)
            assertFalse("tap releases AF", vm.state.value.controls.afLock)
            assertFalse("tap releases AE", vm.state.value.controls.aeLock)
        } finally {
            ViewModelTestAccess.clear(vm)
        }
    }

    @Test fun `a tap leaves a lock set from the menu alone`() {
        RobolectricEglSentinels.ensure()
        val vm = CameraViewModel(app, CameraEngine(app))
        try {
            ViewModelTestAccess.state(vm).update {
                it.copy(caps = ViewModelTestAccess.caps().copy(minFocusDistanceDiopters = 10f, afModes = intArrayOf(0, 1, 4)))
            }
            vm.onToggleAeLock(true)
            vm.onTapFocus(0.5f, 0.5f)
            idleFor(100)
            assertTrue(vm.state.value.controls.aeLock)
        } finally {
            ViewModelTestAccess.clear(vm)
        }
    }

    @Test fun `long press on the viewfinder locks, a tap focuses, and a lit badge releases its lock`() {
        val calls = mutableListOf<String>()
        val state = mutableStateOf(
            CameraUiState(
                cameraReady = true,
                cameraReadyEstablished = true,
                photoSessionOutputs = PhotoSessionOutputs(processed = true),
                controls = ManualControls(aeLock = true),
            ),
        )
        compose.setContent {
            TeleCamProTheme {
                CameraScreen(
                    state = state.value,
                    actions = recordingActions(calls),
                    previewViewFactory = { View(it) },
                    windowRotationOverrideDeg = 0,
                )
            }
        }
        val finder = compose.onNode(hasContentDescription(app.getString(R.string.a11y_camera_viewfinder)), useUnmergedTree = true)
        finder.performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue("long press locks: $calls", calls.any { it.startsWith("onLongPressLock(") })
        assertFalse("long press is no tap: $calls", calls.any { it.startsWith("onTapFocus(") })
        calls.clear()
        finder.performClick()
        compose.waitForIdle()
        assertTrue("tap focuses: $calls", calls.any { it.startsWith("onTapFocus(") })
        calls.clear()
        val ael = app.getString(R.string.osd_ael)
        compose.onNode(hasContentDescription(app.getString(R.string.a11y_release_lock, ael)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf("onToggleAeLock(false)"), calls.filter { it.startsWith("onToggleAeLock") })
    }

    @Test fun `a bar saved before the lock tiles gets AE and AF lock once, where there is room`() {
        val legacy = listOf(FnSlot.EXPOSURE_MODE, FnSlot.FOCUS, FnSlot.SHUTTER, FnSlot.ISO, FnSlot.WB, FnSlot.EV)
        assertEquals(legacy + listOf(FnSlot.AE_LOCK, FnSlot.AF_LOCK), offerLockSlots(legacy, offered = false))
        assertEquals(legacy, offerLockSlots(legacy, offered = true))
        val full = FnSlot.VIDEO_DEFAULT
        assertEquals(full, offerLockSlots(full, offered = false))
        // One free slot takes AE lock; a bar that already holds one lock takes only the other.
        assertEquals(legacy + FnSlot.TIMER + FnSlot.AE_LOCK, offerLockSlots(legacy + FnSlot.TIMER, offered = false))
        assertEquals(legacy + FnSlot.AE_LOCK + FnSlot.AF_LOCK, offerLockSlots(legacy + FnSlot.AE_LOCK, offered = false))
    }
}
