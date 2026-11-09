package me.hletrd.telecampro.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.update
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.ui.controls.performQuickFn
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every Focus surface toggles AF <-> MF (owner request 2026-11-09): they used to force MF and open the
 * ruler, and nothing on the viewfinder led back to AF.
 */
@RunWith(RobolectricTestRunner::class)
class ManualFocusToggleRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `the Focus press goes to MF and back to the AF mode in use before`() {
        RobolectricEglSentinels.ensure()
        val vm = CameraViewModel(app, CameraEngine(app))
        try {
            ViewModelTestAccess.state(vm).update {
                it.copy(caps = ViewModelTestAccess.caps().copy(minFocusDistanceDiopters = 10f, afModes = intArrayOf(0, 1, 4)))
            }
            vm.onFocusMode(FocusMode.AUTO)
            performQuickFn(FnSlot.FOCUS, vm.state.value, vm)
            assertEquals(FocusMode.MANUAL, vm.state.value.controls.focusMode)
            performQuickFn(FnSlot.FOCUS, vm.state.value, vm)
            assertEquals(FocusMode.AUTO, vm.state.value.controls.focusMode)
        } finally {
            ViewModelTestAccess.clear(vm)
        }
    }
}
