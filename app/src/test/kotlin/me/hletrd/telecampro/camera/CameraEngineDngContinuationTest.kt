package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TE5-6: the AGG2-3 exactly-one-continuation rule proven through the ENGINE's own DNG wiring, not
 * a test-side `settleRegisteredShot`. With the shared pre-native allocator saturated, a DNG chain
 * step is rejected synchronously; the engine's retirement lambda must settle with the handoff's
 * continuation, so the chain sees exactly one of {onDone ran, dispatch returned false}. A lambda
 * that handed the raw `onDone` instead would produce both (2^n timelapse ticks, AEB mid-bracket
 * resets) and fail here.
 */
@RunWith(RobolectricTestRunner::class)
class CameraEngineDngContinuationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var engine: CameraEngine? = null

    init {
        RobolectricEglSentinels.ensure()
    }

    @After
    fun tearDown() {
        engine?.release()
    }

    @Test
    fun `a saturated allocator hands a DNG chain step exactly one continuation`() {
        val camera = acceptedEngine()
        val release = CountDownLatch(1)
        var saturated = false
        try {
            // Fill the process-wide pre-native lane (workers + backlog) with blocked work.
            // Every slot held by THIS test's blocked work: another test's draining tasks could
            // otherwise free capacity between the saturation probe and the dispatch below.
            var held = 0
            val deadline = System.nanoTime() + 5_000_000_000L
            while (held < RECORDING_PRE_NATIVE_WORKER_COUNT + RECORDING_PRE_NATIVE_BACKLOG_CAPACITY) {
                check(System.nanoTime() < deadline) { "the shared allocator never freed its slots" }
                val submission = ProcessPreNativeMediaAllocator.dispatch { release.await() }
                if (submission.dispatch == RecordingPreNativeDispatch.ACCEPTED) held++ else Thread.yield()
            }
            saturated = ProcessPreNativeMediaAllocator.dispatch {}.dispatch != RecordingPreNativeDispatch.ACCEPTED
            assertTrue("the shared allocator must be saturated for this proof", saturated)
            assertTrue(ProcessDngPreCaptureAdmission.owner.canAdmit())

            val continuations = AtomicInteger()
            val dispatched = dispatchStillCapture(camera) { continuations.incrementAndGet() }
            val chainSteps = continuations.get() + if (dispatched) 0 else 1

            assertEquals("exactly one owner continues the chain", 1, chainSteps)
            assertTrue("the rejected step released its DNG slot", ProcessDngPreCaptureAdmission.owner.canAdmit())
        } finally {
            release.countDown()
        }
    }

    private fun dispatchStillCapture(camera: CameraEngine, onDone: () -> Unit): Boolean {
        val optics = CameraEngine::class.java.getDeclaredMethod("snapshotShotOptics")
            .apply { isAccessible = true }
            .invoke(camera)
        val method = CameraEngine::class.java.declaredMethods
            .single { it.name == "dispatchStillCapture" && it.parameterCount == 10 }
            .apply { isAccessible = true }
        return method.invoke(
            camera,
            getField(camera, "acceptedCameraSession"),
            PhotoFormats(heif = false, jpeg = false, dngRaw = true),
            ManualControls(),
            false,
            optics,
            null,
            CaptureFamilyTraceAdmission(),
            false,
            false,
            onDone,
        ) as Boolean
    }

    private fun acceptedEngine(): CameraEngine {
        val camera = CameraEngine(app).also { engine = it }
        val controller = CameraController(app)
        val generation = (getField(camera, "cameraSessionGeneration") as AtomicLong).get()
        val acceptedType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "AcceptedCameraSession" }
        val accepted = acceptedType.declaredConstructors.single { it.parameterCount == 4 }
            .apply { isAccessible = true }
            .newInstance(controller, generation, PhotoSessionOutputs(raw = true), false)
        setField(camera, "controller", controller)
        setField(camera, "readyController", controller)
        setField(camera, "acceptedCameraSession", accepted)
        setField(camera, "cameraReady", true)
        return camera
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
