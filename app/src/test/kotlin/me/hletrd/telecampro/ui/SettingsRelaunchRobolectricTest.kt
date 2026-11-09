package me.hletrd.telecampro.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.storage.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Close-and-reopen round trip through the REAL "camera_settings" file: one ViewModel per process,
 * changed through [CameraActions], backgrounded, cleared, then a fresh ViewModel restores it.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRelaunchRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val live = mutableListOf<CameraViewModel>()

    private fun launch(): CameraViewModel {
        RobolectricEglSentinels.ensure()
        return CameraViewModel(app, CameraEngine(app)).also { live += it }
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun clear(v: CameraViewModel) {
        CameraViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(v)
        live -= v
    }

    @After fun tearDown() {
        live.toList().forEach { runCatching { clear(it) } }
    }

    @Test fun `settings changed before a close survive two relaunches`() {
        val first = launch()
        first.onStart()
        first.onExposureMode(ExposureMode.MANUAL)
        first.onIso(1600)
        first.onGridType(GridType.GOLDEN)
        first.onFrameLines(FrameLineType.CINEMA)
        first.onTogglePeaking(true)
        first.onToggleHistogram(true)
        first.onToggleLevel(true)
        first.onToggleZebra(true)
        idleFor(1_000)
        first.onStop()
        clear(first)

        fun assertRestored(v: CameraViewModel, pass: String) {
            val s = v.state.value
            assertTrue("$pass rememberSettings", s.rememberSettings)
            assertEquals("$pass exposure mode", ExposureMode.MANUAL, s.controls.exposureMode)
            assertEquals("$pass iso", 1600, s.controls.iso)
            assertEquals("$pass grid", GridType.GOLDEN, s.grid)
            assertEquals("$pass frame lines", FrameLineType.CINEMA, s.frameLines)
            assertTrue("$pass peaking", s.focusPeaking)
            assertTrue("$pass histogram", s.histogram)
            assertTrue("$pass level", s.level)
            assertTrue("$pass zebra", s.zebra)
            assertEquals("$pass mode", CaptureMode.PHOTO, s.mode)
        }

        val second = launch()
        assertRestored(second, "second launch")
        second.onStart()
        idleFor(1_000)
        assertRestored(second, "second launch after start")
        second.onStop()
        clear(second)
        val stored = SettingsStore(app).load()
        assertEquals("stored after second close", GridType.GOLDEN, stored?.extras?.grid)

        val third = launch()
        assertRestored(third, "third launch")
    }

    // Owner report 2026-11-09: Video at 1/60 s came back as 1/30 s after one visit to Photo.
    @Test fun `each mode keeps its own exposure across a mode trip and a relaunch`() {
        val first = launch()
        first.onStart()
        first.onExposureMode(ExposureMode.MANUAL)
        first.onShutterNs(100_000_000L)
        first.onIso(400)
        first.onModeChange(CaptureMode.VIDEO)
        first.onExposureMode(ExposureMode.SHUTTER)
        first.onShutterNs(16_666_667L)
        first.onModeChange(CaptureMode.PHOTO)
        idleFor(1_000)
        val photo = first.state.value.controls
        assertEquals("photo mode back", ExposureMode.MANUAL, photo.exposureMode)
        assertEquals("photo shutter back", 100_000_000L, photo.exposureTimeNs)
        first.onStop()
        clear(first)

        val second = launch()
        second.onStart()
        second.onModeChange(CaptureMode.VIDEO)
        idleFor(1_000)
        val video = second.state.value.controls
        assertEquals("video mode after relaunch", ExposureMode.SHUTTER, video.exposureMode)
        assertEquals("video shutter after relaunch", 16_666_667L, video.exposureTimeNs)
    }
}
