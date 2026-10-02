package me.hletrd.telecampro.camera

import android.app.Application
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import me.hletrd.telecampro.gl.AtomicOwnerSlot
import me.hletrd.telecampro.gl.GlPipeline
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * AGG5-1 / AGG5-26: a pause that lands inside the cold-start GL window, or across a preview-recovery
 * rebind, must be replayed by resume(). Both interleavings drive the real Engine bodies; the GL
 * pipeline is never started, so its generation counter and handler-backed fields are the probes.
 */
@RunWith(RobolectricTestRunner::class)
class CameraEngineInterruptedColdStartTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val engines = mutableListOf<CameraEngine>()
    private val releases = mutableListOf<() -> Unit>()

    init {
        RobolectricEglSentinels.ensure()
    }

    @After
    fun tearDown() {
        engines.forEach { engine ->
            // Keep any scheduled preview-recovery retry inert before release.
            setField(engine, "paused", true)
            setField(engine, "started", false)
            runCatching { engine.release() }
        }
        releases.forEach { runCatching(it) }
    }

    @Test
    fun `input-ready while paused still re-seeds the GL generation and lens inventory`() {
        val engine = engine()
        val gl = currentGl(engine)
        // A handler on the (paused) main looper stands in for the GL thread so posted setters land.
        setField(gl, "handler", Handler(Looper.getMainLooper()))
        val inventories = mutableListOf<LensInventory>()
        engine.onLensInventory = { inventories += it }
        setField(engine, "paused", true)
        setField(engine, "glInputPending", true)

        invoke(engine, "completeGlInputReady", gl, null)
        shadowOf(Looper.getMainLooper()).idle()
        drainSetup(engine)

        assertFalse(getField(engine, "glInputPending") as Boolean)
        assertNotNull(
            "the analysis callback that feeds app-side AE/scopes is installed for this generation",
            getField(gl, "analysisCallback"),
        )
        assertNotNull(getField(gl, "eisProvider"))
        assertEquals("lens inventory is enumerated despite the pause", 1, inventories.size)
        assertTrue(getField(engine, "lensInventoryPublished") as Boolean)
    }

    // AGG6-17: the paused input-ready branch resolves the route before it enumerates the lens
    // inventory. Against the UNKNOWN back-first default an external-only device found no back lens
    // and latched LensInventory.ALL (0.6/1/3/10x) for the process.
    @Test
    fun `paused input-ready on an external-only device enumerates the external lens`() {
        val manager = app.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val chars = org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(chars).set(
            android.hardware.camera2.CameraCharacteristics.LENS_FACING,
            android.hardware.camera2.CameraMetadata.LENS_FACING_EXTERNAL,
        )
        shadowOf(chars).set(
            android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
            floatArrayOf(26f),
        )
        shadowOf(manager).addCamera("7", chars)
        val engine = engine()
        val gl = currentGl(engine)
        setField(gl, "handler", Handler(Looper.getMainLooper()))
        val inventories = mutableListOf<LensInventory>()
        engine.onLensInventory = { inventories += it }
        setField(engine, "paused", true)
        setField(engine, "glInputPending", true)

        invoke(engine, "completeGlInputReady", gl, null)
        shadowOf(Looper.getMainLooper()).idle()
        drainSetup(engine)

        assertEquals(1, inventories.size)
        assertTrue("the rail reflects the external lens, not the PMA110 set", inventories.single() != LensInventory.ALL)
    }

    @Test
    fun `resume re-binds a cold-start preview bind that the pause dropped`() {
        val engine = engine()
        retainSurface(engine)
        setField(engine, "started", true)
        setField(engine, "paused", true)
        setField(engine, "glInputPending", true)
        setField(engine, "previewReady", false)
        val generation = previewOutputGeneration(engine)
        val before = generation.get()

        engine.resume()
        drainSetup(engine)

        assertTrue("resume attached the retained surface to GL", generation.get() > before)
    }

    @Test
    fun `resume re-binds after a preview-recovery rebind dropped while paused`() {
        val engine = engine()
        retainSurface(engine)
        installInput(engine)
        installController(engine)
        setField(engine, "started", true)
        setField(engine, "paused", true)
        setField(engine, "previewReady", false)
        val generation = previewOutputGeneration(engine)
        val before = generation.get()

        engine.resume()
        drainSetup(engine)

        assertTrue(generation.get() > before)
    }

    @Test
    fun `ordinary foreground return with a live preview does not re-bind`() {
        val engine = engine()
        retainSurface(engine)
        installInput(engine)
        installController(engine)
        setField(engine, "started", true)
        setField(engine, "paused", true)
        setField(engine, "previewReady", true)
        val generation = previewOutputGeneration(engine)
        val before = generation.get()

        engine.resume()
        drainSetup(engine)

        assertEquals(before, generation.get())
    }

    // AGG6-20: the cold-start task's `paused` decision and its `starting` retirement are one monitor
    // step. The test holds the Engine monitor while the setup thread is parked on it, then resumes:
    // reading `paused` outside the monitor, the task had already decided to refuse, resume() was
    // turned away by the still-set `starting`, and nothing ever started.
    @Test
    fun `a resume racing the cold-start pause check still starts`() {
        val engine = engine()
        retainSurface(engine)
        val executor = getField(engine, "setupExecutor") as ExecutorService
        val parked = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val setupThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        executor.execute {
            setupThread.set(Thread.currentThread())
            parked.countDown()
            gate.await(5, TimeUnit.SECONDS)
        }
        assertTrue(parked.await(5, TimeUnit.SECONDS))
        engine.onPreviewSurfaceAvailable(getField(engine, "previewSurface") as Surface, 1080, 1440)
        assertTrue(getField(engine, "starting") as Boolean)
        setField(engine, "paused", true)

        synchronized(engine) {
            gate.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (setupThread.get().state != Thread.State.BLOCKED) {
                check(System.nanoTime() < deadline) { "setup task never reached the Engine monitor" }
                Thread.yield()
            }
            engine.resume()
        }
        drainSetup(engine)

        assertTrue(
            "the start was dispatched by exactly one of the two racers",
            (getField(engine, "started") as Boolean) || (getField(engine, "glInputPending") as Boolean),
        )
    }

    @Test
    fun `rebind predicate is false only for a live input with a presented preview`() {
        assertFalse(resumePreviewRebindWanted(inputSurfacePresent = true, previewReady = true))
        assertTrue(resumePreviewRebindWanted(inputSurfacePresent = false, previewReady = true))
        assertTrue(resumePreviewRebindWanted(inputSurfacePresent = true, previewReady = false))
        assertTrue(resumePreviewRebindWanted(inputSurfacePresent = false, previewReady = false))
    }

    private fun engine(): CameraEngine = CameraEngine(app).also(engines::add)

    private fun currentGl(engine: CameraEngine): GlPipeline =
        (getField(engine, "glOwners") as AtomicOwnerSlot<*>).current() as GlPipeline

    private fun previewOutputGeneration(engine: CameraEngine): AtomicLong =
        getField(currentGl(engine), "previewOutputGeneration") as AtomicLong

    private fun retainSurface(engine: CameraEngine) {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        releases += { surface.release(); texture.release() }
        setField(engine, "previewSurface", surface)
        setField(engine, "previewSurfaceW", 1080)
        setField(engine, "previewSurfaceH", 1440)
    }

    private fun installInput(engine: CameraEngine) {
        val texture = SurfaceTexture(0)
        val input = Surface(texture)
        releases += { input.release(); texture.release() }
        setField(currentGl(engine), "inputSurface", input)
    }

    /** A live controller makes resume's reopen task return before any Camera2 work. */
    private fun installController(engine: CameraEngine) {
        setField(engine, "controller", CameraController(app))
    }

    private fun drainSetup(engine: CameraEngine) {
        val executor = getField(engine, "setupExecutor") as ExecutorService
        // Two passes: a task drained by the first may enqueue a follow-up (lens inventory, bind).
        repeat(2) { executor.submit {}.get(5, TimeUnit.SECONDS) }
    }

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? =
        target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }
            .invoke(target, *args)

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
