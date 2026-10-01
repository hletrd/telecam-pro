package me.hletrd.telecampro.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.HardwareKeyAction
import me.hletrd.telecampro.storage.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * AGG4-74: momentary AEL / PUNCH_IN holds restore the operator's own value on release and never
 * persist the held value. They used to write `false` on release through the persistent toggles.
 */
@RunWith(RobolectricTestRunner::class)
class MomentaryHoldRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var viewModel: CameraViewModel? = null

    @After fun tearDown() {
        viewModel?.let(ViewModelTestAccess::clear)
    }

    private fun vm(): CameraViewModel {
        RobolectricEglSentinels.ensure()
        return CameraViewModel(app, CameraEngine(app)).also { viewModel = it }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun saveNow(vm: CameraViewModel) = ViewModelTestAccess.invoke(vm, "saveSettingsIfEnabled")

    @Test fun `pure hold snapshots once and restores exactly`() {
        val hold = MomentaryHold()
        assertNull(hold.release())
        assertTrue(hold.press(current = true))
        assertTrue(hold.press(current = false)) // a repeat keeps the first snapshot
        assertTrue(hold.persistedValue(live = false))
        assertEquals(true, hold.release())
        assertFalse(hold.persistedValue(live = false))
        hold.press(current = false)
        hold.cancel()
        assertNull(hold.release())
    }

    @Test fun `a punch-in hold over a latched loupe leaves it on and persisted on`() {
        val v = vm()
        v.onTogglePunchIn(true)
        v.onHalfPressAction(HardwareKeyAction.PUNCH_IN)
        idle(600)
        v.onHardwareHalfPress(true)
        assertTrue(v.state.value.punchIn)
        v.onHardwareHalfPress(false)
        assertTrue("release restores the operator's latched loupe", v.state.value.punchIn)
        idle(600)
        assertEquals(true, SettingsStore(app).load()?.extras?.punchIn)
    }

    @Test fun `a punch-in hold from off shows the loupe but never persists it`() {
        val v = vm()
        v.onHalfPressAction(HardwareKeyAction.PUNCH_IN)
        idle(600)
        v.onHardwareHalfPress(true)
        assertTrue(v.state.value.punchIn)
        saveNow(v) // a save landing mid-hold (background, another control) writes the operator's value
        assertEquals(false, SettingsStore(app).load()?.extras?.punchIn)
        assertTrue("a hold is not a setting change", v.state.value.recentSettingSlots.isEmpty())
        v.onHardwareHalfPress(false)
        assertFalse(v.state.value.punchIn)
    }

    @Test fun `an AE-lock hold keeps a latched lock and persists the operator's unlocked state`() {
        val v = vm()
        v.onToggleAeLock(true)
        v.onVolumeKeyAction(HardwareKeyAction.AEL)
        idle(600)
        v.onHardwareFullKey(true)
        v.onHardwareFullKey(false)
        assertTrue("one tap of an AEL key must not unlock a latched lock", v.state.value.controls.aeLock)

        v.onToggleAeLock(false)
        idle(600)
        v.onHardwareFullKey(true)
        assertTrue(v.state.value.controls.aeLock)
        saveNow(v)
        assertEquals(false, SettingsStore(app).load()?.controls?.aeLock)
        v.onHardwareFullKey(false)
        assertFalse(v.state.value.controls.aeLock)
    }

    @Test fun `an explicit toggle mid-hold survives the release`() {
        val v = vm()
        v.onVolumeKeyAction(HardwareKeyAction.AEL)
        v.onHardwareFullKey(true)
        v.onToggleAeLock(true) // the operator latches it while holding
        v.onHardwareFullKey(false)
        assertTrue(v.state.value.controls.aeLock)
    }
}
