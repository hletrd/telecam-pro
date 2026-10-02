package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AGG6-23: a BURST/AEB head with DNG on that the shared pre-native allocator refuses synchronously
 * is a refused press, and the chain does not walk its remaining shots against the same refusal.
 */
@RunWith(RobolectricTestRunner::class)
class CameraEngineDngHeadRefusalTest {
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
    fun `a refused DNG BURST head refuses the press without walking the chain`() {
        assertHeadRefused(DriveMode.BURST)
    }

    @Test
    fun `a refused DNG AEB head refuses the press without walking the bracket`() {
        assertHeadRefused(DriveMode.AEB)
    }

    private fun assertHeadRefused(drive: DriveMode) {
        val engine = acceptedEngine()
        engine.setDriveMode(drive)
        val statuses = mutableListOf<CameraStatusMessage>()
        engine.onStatus = { it?.message?.let(statuses::add) }
        val release = CountDownLatch(1)
        try {
            // Every slot held by THIS test's blocked work: an earlier test's draining tasks could
            // otherwise free capacity between the saturation probe and the press.
            var held = 0
            val deadline = System.nanoTime() + 5_000_000_000L
            while (held < RECORDING_PRE_NATIVE_WORKER_COUNT + RECORDING_PRE_NATIVE_BACKLOG_CAPACITY) {
                check(System.nanoTime() < deadline) { "the shared allocator never freed its slots" }
                val submission = ProcessPreNativeMediaAllocator.dispatch { release.await() }
                if (submission.dispatch == RecordingPreNativeDispatch.ACCEPTED) held++ else Thread.yield()
            }
            assertTrue(
                "the shared allocator must be saturated for this proof",
                ProcessPreNativeMediaAllocator.dispatch {}.dispatch != RecordingPreNativeDispatch.ACCEPTED,
            )

            val admitted = engine.capturePhoto(PhotoFormats(heif = false, jpeg = false, dngRaw = true))

            assertFalse("the head was refused, so the press must not read as taken", admitted)
            assertEquals(
                "only the head was attempted",
                1,
                statuses.count { it == CameraStatusMessage.DNG_SAVE_FAILED },
            )
            assertTrue(ProcessDngPreCaptureAdmission.owner.canAdmit())
        } finally {
            release.countDown()
        }
    }

    private fun acceptedEngine(): CameraEngine {
        val engine = CameraEngine(app).also(engines::add)
        val controller = CameraController(app)
        val generation = (getField(engine, "cameraSessionGeneration") as AtomicLong).get()
        val acceptedType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "AcceptedCameraSession" }
        val accepted = acceptedType.declaredConstructors.single { it.parameterCount == 4 }
            .apply { isAccessible = true }
            .newInstance(controller, generation, PhotoSessionOutputs(raw = true), false)
        setField(engine, "controller", controller)
        setField(engine, "readyController", controller)
        setField(engine, "acceptedCameraSession", accepted)
        setField(engine, "cameraReady", true)
        return engine
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
