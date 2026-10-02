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

    private fun acceptedEngine(): CameraEngine {
        val engine = CameraEngine(app).also(engines::add)
        val controller = CameraController(app)
        val generation = (getField(engine, "cameraSessionGeneration") as AtomicLong).get()
        val acceptedType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "AcceptedCameraSession" }
        val accepted = acceptedType.declaredConstructors.single { it.parameterCount == 4 }
            .apply { isAccessible = true }
            .newInstance(controller, generation, PhotoSessionOutputs(processed = true), false)
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
