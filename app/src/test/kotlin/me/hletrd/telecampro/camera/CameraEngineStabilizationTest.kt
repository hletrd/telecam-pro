package me.hletrd.telecampro.camera

import android.app.Application
import android.hardware.camera2.CameraMetadata
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Engine-entry coverage for stabilization intent and Camera2/session side-effect ordering. */
@RunWith(RobolectricTestRunner::class)
class CameraEngineStabilizationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val engines = mutableListOf<CameraEngine>()

    init {
        RobolectricEglSentinels.ensure()
    }

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.release() } }
    }

    @Test
    fun `same-effective normalization stores intent without Engine effects`() {
        val onOnly = engine(
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
        )
        onOnly.engine.setVideoStabMode(VideoStabMode.ENHANCED)
        onOnly.effects.clear()

        onOnly.engine.setVideoStabMode(VideoStabMode.STANDARD)

        assertEquals(VideoStabMode.STANDARD, onOnly.engine.requestedMode())
        assertEquals(emptyList<String>(), onOnly.effects)

        val offOnly = engine(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        offOnly.engine.setVideoStabMode(VideoStabMode.ENHANCED)
        offOnly.effects.clear()

        offOnly.engine.setVideoStabMode(VideoStabMode.OFF)

        assertEquals(VideoStabMode.OFF, offOnly.engine.requestedMode())
        assertEquals(emptyList<String>(), offOnly.effects)
    }

    @Test
    fun `real HAL transitions store intent then apply and reopen exactly once`() {
        val harness = engine(
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION,
        )

        harness.engine.setVideoStabMode(VideoStabMode.STANDARD)
        assertEquals(VideoStabMode.STANDARD, harness.engine.requestedMode())
        assertEquals(listOf("apply", "reopen"), harness.effects)

        harness.effects.clear()
        harness.engine.setVideoStabMode(VideoStabMode.ENHANCED)
        assertEquals(VideoStabMode.ENHANCED, harness.engine.requestedMode())
        assertEquals(listOf("apply", "reopen"), harness.effects)
    }

    private fun engine(vararg modes: Int): Harness {
        val effects = mutableListOf<String>()
        val engine = CameraEngine(
            context = app,
            stabilizationOverrides = StabilizationEngineOverrides(
                videoStabModes = modes,
                apply = { effects += "apply" },
                reopen = { effects += "reopen" },
            ),
        ).also(engines::add)
        return Harness(engine, effects)
    }

    private fun CameraEngine.requestedMode(): VideoStabMode =
        javaClass.getDeclaredField("videoStabMode")
            .apply { isAccessible = true }
            .get(this) as VideoStabMode

    private data class Harness(
        val engine: CameraEngine,
        val effects: MutableList<String>,
    )
}
