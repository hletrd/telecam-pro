package me.hletrd.telecampro.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.HardwareKeyAction
import me.hletrd.telecampro.camera.MemorySlot
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
        val key = HardwareKeySource.FULL_KEY
        assertNull(hold.release(key))
        assertTrue(hold.press(current = true, key = key))
        assertTrue(hold.press(current = false, key = key)) // a repeat keeps the first snapshot
        assertTrue(hold.persistedValue(live = false))
        assertEquals(true, hold.release(key))
        assertFalse(hold.persistedValue(live = false))
        hold.press(current = false, key = key)
        hold.cancel()
        assertNull(hold.release(key))
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

    // MRG4-8: a recall or restore owns the setting; the held key's release must not overwrite it.
    @Test fun `a recall mid-hold survives the release of an AEL hold`() {
        val v = vm()
        v.onToggleAeLock(true)
        v.onStoreMemorySlot(MemorySlot.MR1) // MR1 carries aeLock = true
        v.onToggleAeLock(false)
        v.onVolumeKeyAction(HardwareKeyAction.AEL)
        idle(600)
        v.onHardwareFullKey(true) // snapshot: unlocked
        v.onRecallMemorySlot(MemorySlot.MR1)
        assertTrue(v.state.value.controls.aeLock)
        v.onHardwareFullKey(false)
        assertTrue("the release must not restore the pre-recall unlocked state", v.state.value.controls.aeLock)
    }

    @Test fun `a recall mid-hold survives the release of a punch-in hold`() {
        val v = vm()
        v.onTogglePunchIn(true)
        v.onStoreMemorySlot(MemorySlot.MR2) // MR2 carries punchIn = true
        v.onTogglePunchIn(false)
        v.onHalfPressAction(HardwareKeyAction.PUNCH_IN)
        idle(600)
        v.onHardwareHalfPress(true) // snapshot: off
        v.onRecallMemorySlot(MemorySlot.MR2)
        assertTrue(v.state.value.punchIn)
        v.onHardwareHalfPress(false)
        assertTrue("the release must not switch the recalled loupe off", v.state.value.punchIn)
    }

    // AGG6-29: two keys on one momentary action. Rebinding the volume key used to end the
    // action-wide hold while the half-press was still under the operator's finger.
    @Test fun `rebinding one key keeps another key's hold of the same action`() {
        val v = vm()
        v.onVolumeKeyAction(HardwareKeyAction.PUNCH_IN)
        v.onHalfPressAction(HardwareKeyAction.PUNCH_IN)
        idle(600)
        v.onHardwareHalfPress(true)
        assertTrue(v.state.value.punchIn)

        v.onVolumeKeyAction(HardwareKeyAction.SHUTTER) // the volume key never held it
        assertTrue("the half-press still holds the loupe", v.state.value.punchIn)
        v.onHardwareHalfPress(false)
        assertFalse("its own release restores the operator's value", v.state.value.punchIn)

        // Both keys holding: the hold ends with the LAST release, and a rebind of one only drops it.
        v.onVolumeKeyAction(HardwareKeyAction.PUNCH_IN)
        v.onHardwareFullKey(true)
        v.onHardwareHalfPress(true)
        v.onHalfPressAction(HardwareKeyAction.SHUTTER)
        assertTrue(v.state.value.punchIn)
        v.onHardwareFullKey(false)
        assertFalse(v.state.value.punchIn)
    }

    @Test fun `backgrounding ends a hold whose key-up never arrives`() {
        val v = vm()
        v.onStart()
        v.onVolumeKeyAction(HardwareKeyAction.AEL)
        v.onHalfPressAction(HardwareKeyAction.PUNCH_IN)
        idle(600)
        v.onHardwareFullKey(true)
        v.onHardwareHalfPress(true)
        assertTrue(v.state.value.controls.aeLock)
        assertTrue(v.state.value.punchIn)
        v.onStop()
        assertFalse("the held lock does not outlive the foreground", v.state.value.controls.aeLock)
        assertFalse("the held loupe does not outlive the foreground", v.state.value.punchIn)
        // The lost key-up now finds no hold, so it cannot overwrite a later operator choice.
        v.onToggleAeLock(true)
        v.onHardwareFullKey(false)
        assertTrue(v.state.value.controls.aeLock)
    }

    @Test fun `an explicit toggle mid-hold survives the release`() {
        val v = vm()
        v.onVolumeKeyAction(HardwareKeyAction.AEL)
        v.onHardwareFullKey(true)
        v.onToggleAeLock(true) // the operator latches it while holding
        v.onHardwareFullKey(false)
        assertTrue(v.state.value.controls.aeLock)
    }

    // ---- AGG5-20: the focus-ruler assist and the PUNCH_IN hold / toggle / recall ----
    // ManualDials engages the assist only while `!punchIn` and releases it on ruler close while the
    // loupe is up; every ordering below drives the VM doors the way that composable does.

    private fun punchInVm(): CameraViewModel = vm().also {
        it.onHalfPressAction(HardwareKeyAction.PUNCH_IN)
        idle(600)
    }

    private fun persistedPunchIn(v: CameraViewModel): Boolean? {
        saveNow(v)
        return SettingsStore(app).load()?.extras?.punchIn
    }

    @Test fun `ruler then hold - the release restores the operator value, not the assist's`() {
        val v = punchInVm()
        v.onAutoPunchIn(true) // ruler opens over an off loupe
        v.onHardwareHalfPress(true)
        v.onAutoPunchIn(false) // ruler closes while the key is held: the hold owns the loupe now
        assertTrue("the held loupe stays up", v.state.value.punchIn)
        v.onHardwareHalfPress(false)
        assertFalse("CR5-2: the release restored the assist's transient true", v.state.value.punchIn)
        assertEquals(false, persistedPunchIn(v))
    }

    @Test fun `ruler then hold then release then ruler close leaves the loupe off`() {
        val v = punchInVm()
        v.onAutoPunchIn(true)
        v.onHardwareHalfPress(true)
        v.onHardwareHalfPress(false)
        assertFalse(v.state.value.punchIn)
        v.onAutoPunchIn(false) // the composable's close (it may still think it owns the loupe)
        assertFalse(v.state.value.punchIn)
        assertEquals(false, persistedPunchIn(v))
    }

    @Test fun `hold then ruler - the assist snapshots the operator value and owns the field`() {
        val v = punchInVm()
        v.onHardwareHalfPress(true) // loupe up by the hold (operator value off)
        v.onAutoPunchIn(true) // defensive: the composable does not engage over an up loupe
        assertEquals("a save mid-assist writes the operator value", false, persistedPunchIn(v))
        v.onHardwareHalfPress(false) // the assist took the field over; the release restores nothing
        assertTrue(v.state.value.punchIn)
        v.onAutoPunchIn(false)
        assertFalse(v.state.value.punchIn)
        assertEquals(false, persistedPunchIn(v))
    }

    @Test fun `hold then ruler open and close - the composable never engages, the hold restores`() {
        val v = punchInVm()
        v.onHardwareHalfPress(true)
        // Ruler opens over an up loupe: no assist. Ruler closes: the composable never engaged.
        v.onHardwareHalfPress(false)
        assertFalse(v.state.value.punchIn)
        assertEquals(false, persistedPunchIn(v))
    }

    @Test fun `an explicit toggle during the assist survives the ruler close`() {
        val v = vm()
        v.onAutoPunchIn(true)
        v.onTogglePunchIn(false)
        v.onTogglePunchIn(true) // operator-owned on, with the ruler still open
        v.onAutoPunchIn(false)
        assertTrue("DB5-5 A: the ruler close switched the operator's loupe off", v.state.value.punchIn)
        assertEquals(true, persistedPunchIn(v))
    }

    @Test fun `a recall during the assist owns punch-in and survives the ruler close`() {
        val v = vm()
        v.onTogglePunchIn(true)
        v.onStoreMemorySlot(MemorySlot.MR3) // MR3 carries punchIn = true
        v.onTogglePunchIn(false)
        idle(600)
        v.onAutoPunchIn(true)
        v.onRecallMemorySlot(MemorySlot.MR3)
        assertTrue(v.state.value.punchIn)
        assertEquals("saves write the recalled value, not the pre-assist snapshot", true, persistedPunchIn(v))
        v.onAutoPunchIn(false)
        assertTrue("TR5-12: the ruler close switched the recalled loupe off", v.state.value.punchIn)
    }

    @Test fun `the assist alone restores the pre-assist value and a stray release is a no-op`() {
        val v = vm()
        v.onAutoPunchIn(false) // never engaged
        assertFalse(v.state.value.punchIn)
        v.onAutoPunchIn(true)
        assertTrue(v.state.value.punchIn)
        assertEquals(false, persistedPunchIn(v))
        v.onAutoPunchIn(false)
        assertFalse(v.state.value.punchIn)
    }

    // ---- AGG5-65: rebinding a key mid-hold ends the old action's hold ----

    @Test fun `rebinding an AEL key mid-hold restores the operator's lock state`() {
        val v = vm()
        v.onVolumeKeyAction(HardwareKeyAction.AEL)
        idle(600)
        v.onHardwareFullKey(true)
        assertTrue(v.state.value.controls.aeLock)
        v.onVolumeKeyAction(HardwareKeyAction.NONE)
        assertFalse("the hold ended with the binding", v.state.value.controls.aeLock)
        v.onHardwareFullKey(false) // the release now goes to NONE
        assertFalse(v.state.value.controls.aeLock)
        // A later AEL press snapshots afresh instead of reusing a stale one.
        v.onToggleAeLock(true)
        v.onVolumeKeyAction(HardwareKeyAction.AEL)
        v.onHardwareFullKey(true)
        v.onHardwareFullKey(false)
        assertTrue(v.state.value.controls.aeLock)
    }

    @Test fun `rebinding a punch-in key mid-hold restores the loupe`() {
        val v = punchInVm()
        v.onHardwareHalfPress(true)
        assertTrue(v.state.value.punchIn)
        v.onHalfPressAction(HardwareKeyAction.AF_ON)
        assertFalse(v.state.value.punchIn)
        assertEquals(false, persistedPunchIn(v))
        // Rebinding a key that holds nothing changes nothing.
        v.onTogglePunchIn(true)
        v.onQuickButtonAction(HardwareKeyAction.PUNCH_IN)
        v.onQuickButtonAction(HardwareKeyAction.SHUTTER)
        assertTrue(v.state.value.punchIn)
    }
}
