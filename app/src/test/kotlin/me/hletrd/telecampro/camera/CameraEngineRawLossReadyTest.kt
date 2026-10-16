package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicLong
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import me.hletrd.telecampro.ui.ViewModelTestAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MRG5-1 / MRG5-7: commitOpticsReady publishes the RAW-loss INPUTS on the Ready publication and
 * never emits RAW_UNAVAILABLE itself. Latching at emission lost the notice for a whole session shape
 * whenever the plate dropped it, so the ViewModel's Ready fold owns the latch.
 */
@RunWith(RobolectricTestRunner::class)
class CameraEngineRawLossReadyTest {
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
    fun `a RAW-less Ready on a RAW-capable route carries the facts and emits nothing`() {
        val engine = CameraEngine(app).also(engines::add)
        val controller = CameraController(app)
        setField(engine, "controller", controller)
        setField(engine, "previewReady", true)
        setField(engine, "rawWanted", true)
        setField(engine, "caps", ViewModelTestAccess.caps().copy(supportsRaw = true))
        val statuses = mutableListOf<CameraStatusMessage>()
        val publications = mutableListOf<CameraReadyPublication>()
        engine.onStatus = { it?.message?.let(statuses::add) }
        engine.onCameraReadyChange = publications::add
        val outputs = PhotoSessionOutputs(processed = true)

        repeat(2) { assertTrue(commit(engine, controller, outputs)) }

        assertFalse(CameraStatusMessage.RAW_UNAVAILABLE in statuses)
        val expected = RawLossReadyFacts(rawWanted = true, rawSelectable = true, shape = "none|$outputs")
        assertEquals(listOf(expected, expected), publications.map { it.rawLoss })
    }

    // AGG6-13: a commit before the first real preview frame publishes Not-Ready; the session's Ready
    // then comes from handlePreviewReady and must carry the SAME facts, or a drop-RAW rung reached
    // at cold start, resume, or preview recovery is never announced.
    @Test
    fun `the first-frame Ready of a committed session carries its RAW-loss facts`() {
        val engine = CameraEngine(app).also(engines::add)
        val controller = CameraController(app)
        setField(engine, "controller", controller)
        setField(engine, "previewReady", false)
        setField(engine, "rawWanted", true)
        setField(engine, "caps", ViewModelTestAccess.caps().copy(supportsRaw = true))
        val texture = android.graphics.SurfaceTexture(0)
        val surface = android.view.Surface(texture)
        try {
            setField(engine, "previewSurface", surface)
            val surfaceGeneration = (getField(engine, "previewSurfaceGeneration") as AtomicLong).get()
            val publications = mutableListOf<CameraReadyPublication>()
            engine.onCameraReadyChange = publications::add
            val outputs = PhotoSessionOutputs(processed = true)

            assertTrue(commit(engine, controller, outputs))
            val gl = (getField(engine, "glOwners") as me.hletrd.telecampro.gl.AtomicOwnerSlot<*>).current()
            CameraEngine::class.java.declaredMethods.single { it.name == "handlePreviewReady" }
                .apply { isAccessible = true }
                .invoke(engine, gl, surface, surfaceGeneration)

            val expected = RawLossReadyFacts(rawWanted = true, rawSelectable = true, shape = "none|$outputs")
            assertEquals(listOf(false, true), publications.map { it.ready })
            assertEquals(expected, publications.last().rawLoss)
        } finally {
            surface.release()
            texture.release()
        }
    }

    private fun commit(engine: CameraEngine, controller: CameraController, outputs: PhotoSessionOutputs): Boolean =
        CameraEngine::class.java.declaredMethods.single {
            it.name == "commitOpticsReady" && it.parameterTypes.size == 6
        }.apply { isAccessible = true }.invoke(
            engine,
            (getField(engine, "opticsIntentGeneration") as AtomicLong).get(),
            controller,
            outputs,
            (getField(engine, "cameraSessionGeneration") as AtomicLong).get(),
            {},
            null,
        ) as Boolean

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
