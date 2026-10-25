package me.hletrd.telecampro.camera

import android.app.Application
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import me.hletrd.telecampro.ui.ViewModelTestAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bounded recovery after a camera failure (device-seen 2026-10-26, RAW log + TELE on PMA110): one
 * controller that reports more than once gets ONE scheduled reopen, and a RAW log attempt that failed
 * the device is left out of the reopens that follow.
 */
@RunWith(RobolectricTestRunner::class)
class CameraRecoveryOwnershipTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun installEglSentinels() {
        RobolectricEglSentinels.ensure()
    }

    @Test
    fun `repeated failures of one controller schedule one recovery`() {
        val engine = CameraEngine(app)
        val failed = CameraController(app)
        setField(engine, "controller", failed)
        setField(engine, "started", true)
        setField(engine, "previewReady", true)

        // A configure failure and the device's own onError, both from the same controller.
        handleFailure(engine, failed, IllegalStateException("fallback ladder exhausted"))
        handleFailure(engine, failed, IllegalStateException("Camera error 4"))

        assertEquals(1, field(engine, "cameraRecoveryAttempts"))
        assertSame(failed, field(engine, "recoveryScheduledFor"))
        // Retire the failed controller so the reopen scheduled one second out stays inert in this test.
        setField(engine, "controller", CameraController(app))
    }

    @Test
    fun `a RAW log fatality leaves RAW log out of that route until the operator turns it on again`() {
        val engine = CameraEngine(app)
        val failed = CameraController(app)
        setField(engine, "controller", failed)
        setField(engine, "videoMode", true)
        setField(engine, "transfer", ColorTransfer.SLOG3)
        setField(engine, "rawLogEnabled", true)
        setField(engine, "teleconverterMode", true)
        val caps = ViewModelTestAccess.caps().copy(
            supportsRaw = true,
            rawSize = Size(4096, 3072),
            rawLogDevelopable = true,
            rawMinFrameDurationNs = 33_322_200L,
        )
        val tele = TeleSelection("4", null, 70f)
        val stream = Size(1920, 1080)
        assertNotNull("RAW log is offered before any failure", rawLogStreamFor(engine, tele, caps, stream))

        handleFailure(
            engine,
            failed,
            RawLogConfigureFatalException(rawLogRouteKey("4", null, true), IllegalStateException("-38")),
        )
        setField(engine, "controller", CameraController(app))

        assertNull("the reopen leaves RAW log out", rawLogStreamFor(engine, tele, caps, stream))
        assertNotNull("another route keeps it", rawLogStreamFor(engine, TeleSelection("2", null, 23f), caps, stream))

        // Off, then on again: the operator's explicit retry clears the refusal.
        engine.setRawLogEnabled(false)
        engine.setRawLogEnabled(true)
        assertNotNull(rawLogStreamFor(engine, tele, caps, stream))
    }

    private fun handleFailure(engine: CameraEngine, failed: CameraController, failure: Throwable) {
        CameraEngine::class.java.declaredMethods.single { it.name == "handleActiveCameraFailure" }
            .apply { isAccessible = true }
            .invoke(engine, failed, failure)
    }

    private fun rawLogStreamFor(engine: CameraEngine, sel: TeleSelection, caps: CameraCaps, stream: Size): Size? =
        CameraEngine::class.java.declaredMethods.single { it.name == "rawLogStreamFor" }
            .apply { isAccessible = true }
            .invoke(engine, sel, caps, stream) as Size?

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
