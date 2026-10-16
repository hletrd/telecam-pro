package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicLong
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AGG5-45: BURST/AEB answer the press with their HEAD dispatch, like SINGLE. A refused head
 * ("Finishing previous photo") used to return true, so the UI blinked the shutter for nothing.
 */
@RunWith(RobolectricTestRunner::class)
class CameraEngineDriveHeadTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val engines = mutableListOf<CameraEngine>()
    private val heldLeases = mutableListOf<ProcessedSnapshotBudget.Lease>()

    init {
        RobolectricEglSentinels.ensure()
    }

    @After
    fun tearDown() {
        heldLeases.forEach { it.release() }
        engines.forEach { runCatching { it.release() } }
    }

    @Test
    fun `a refused BURST head is a refused press`() {
        assertHeadRefused(DriveMode.BURST)
    }

    @Test
    fun `a refused AEB head is a refused press`() {
        assertHeadRefused(DriveMode.AEB)
    }

    // MRG5-7: AGG5-10 removed the per-press RAW_UNAVAILABLE. On a RAW-less accepted session (the
    // FRONT route's only shape) a DNG-wanting press must stay quiet; the Ready fold owns the notice.
    @Test
    fun `a DNG-wanting press on a RAW-less session raises no RAW notice`() {
        val engine = acceptedEngine()
        setField(engine, "activeCameraRoute", CameraRoute.FRONT)
        val statuses = mutableListOf<CameraStatusMessage>()
        engine.onStatus = { it?.message?.let(statuses::add) }
        // Stop the press before dispatch: there is no opened camera behind this controller.
        while (true) heldLeases += ProcessProcessedSnapshotBudget.owner.tryAcquire() ?: break

        engine.capturePhoto(PhotoFormats(heif = true, dngRaw = true))

        assertTrue("the press reached the head", CameraStatusMessage.FINISHING_PREVIOUS_PHOTO in statuses)
        assertFalse(CameraStatusMessage.RAW_UNAVAILABLE in statuses)
    }

    // AGG6-27: the DNG-only substitution is announced by the Ready fold's rising edge and kept by
    // the Output caption; a press on that session must not repeat it.
    @Test
    fun `a press on a DNG-only session repeats no processed-still notice`() {
        val engine = acceptedEngine(PhotoSessionOutputs(raw = true))
        val statuses = mutableListOf<CameraStatusMessage>()
        engine.onStatus = { it?.message?.let(statuses::add) }
        // Stop the press at the DNG allocator: there is no opened camera behind this controller.
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            var held = 0
            val deadline = System.nanoTime() + 5_000_000_000L
            while (held < RECORDING_PRE_NATIVE_WORKER_COUNT + RECORDING_PRE_NATIVE_BACKLOG_CAPACITY) {
                check(System.nanoTime() < deadline) { "the shared allocator never freed its slots" }
                val submission = ProcessPreNativeMediaAllocator.dispatch { release.await() }
                if (submission.dispatch == RecordingPreNativeDispatch.ACCEPTED) held++ else Thread.yield()
            }
            engine.capturePhoto(PhotoFormats(heif = true, dngRaw = true))
        } finally {
            release.countDown()
        }

        assertTrue("the press reached the DNG head", CameraStatusMessage.DNG_SAVE_FAILED in statuses)
        assertFalse(CameraStatusMessage.PROCESSED_STILL_UNAVAILABLE_DNG_ONLY in statuses)
    }

    private fun assertHeadRefused(drive: DriveMode) {
        val engine = acceptedEngine()
        engine.setDriveMode(drive)
        val statuses = mutableListOf<CameraStatusMessage>()
        engine.onStatus = { it?.message?.let(statuses::add) }
        // Every retained processed-snapshot slot is held by an earlier shot still saving.
        while (true) heldLeases += ProcessProcessedSnapshotBudget.owner.tryAcquire() ?: break

        val admitted = engine.capturePhoto(PhotoFormats(heif = true))

        assertFalse("the head was refused, so the press must not read as taken", admitted)
        assertTrue(CameraStatusMessage.FINISHING_PREVIOUS_PHOTO in statuses)
    }

    private fun acceptedEngine(outputs: PhotoSessionOutputs = PhotoSessionOutputs(processed = true)): CameraEngine {
        val engine = CameraEngine(app).also(engines::add)
        val controller = CameraController(app)
        val generation = (getField(engine, "cameraSessionGeneration") as AtomicLong).get()
        val acceptedType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "AcceptedCameraSession" }
        val accepted = acceptedType.declaredConstructors.single { it.parameterCount == 4 }
            .apply { isAccessible = true }
            .newInstance(controller, generation, outputs, false)
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
