package me.hletrd.telecampro.camera

import android.app.Application
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.gl.GlPipeline
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicLong

/**
 * MRG4-2 / MRG4-3: the A.2 wiring itself. A bare `reopenForSession()` door whose preflight fails
 * goes through [CameraEngine]'s `handlePreflightFailure` and must end in a scheduled retry — never
 * the Not-Ready rollback it used to take — and, when no retry can be scheduled, in the terminal
 * reopen status rather than a silent park. The pure disposition table cannot catch a revert at the
 * call site; these drive the real engine method.
 */
@RunWith(RobolectricTestRunner::class)
class BarePreflightRetryRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var engine: CameraEngine? = null
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null

    @Test
    fun `a bare door preflight failure schedules a retry and never rolls back`() {
        val camera = runnableEngine(withInputSurface = true)
        val statuses = captureStatuses(camera)
        val sessions = field(camera, "cameraSessionGeneration") as AtomicLong
        val transaction = bareTransaction(camera)
        val preflight = sessions.incrementAndGet()

        handlePreflightFailure(camera, transaction, preflight)
        // Keep the 1 s retry body inert once it fires: its claim requires !paused.
        setBoolean(camera, "paused", true)
        shadowOf(Looper.getMainLooper()).idle()

        val gate = field(camera, "coldStartRetryGate")!!
        assertNotNull("a retry is scheduled for the bare door", field(gate, "scheduled"))
        assertEquals("no Not-Ready rollback retired the session", preflight, sessions.get())
        assertEquals(listOf(CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING), statuses)
    }

    @Test
    fun `a bare door whose retry cannot be scheduled publishes the terminal reopen status`() {
        // No GL input and none pending: nothing will ever retry this intent.
        val camera = runnableEngine(withInputSurface = false)
        val statuses = captureStatuses(camera)
        val sessions = field(camera, "cameraSessionGeneration") as AtomicLong
        val transaction = bareTransaction(camera)
        val preflight = sessions.incrementAndGet()

        handlePreflightFailure(camera, transaction, preflight)
        setBoolean(camera, "paused", true)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(field(field(camera, "coldStartRetryGate")!!, "scheduled"))
        assertEquals(listOf(CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN), statuses)
        assertEquals(preflight, sessions.get())
    }

    @Test
    fun `a paused bare door leaves the next open to resume without a status`() {
        val camera = runnableEngine(withInputSurface = true)
        setBoolean(camera, "paused", true)
        val statuses = captureStatuses(camera)
        val sessions = field(camera, "cameraSessionGeneration") as AtomicLong
        val transaction = bareTransaction(camera)

        handlePreflightFailure(camera, transaction, sessions.incrementAndGet())
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(statuses.isEmpty())
    }

    private fun runnableEngine(withInputSurface: Boolean): CameraEngine {
        RobolectricEglSentinels.ensure()
        val camera = CameraEngine(app)
        engine = camera
        setBoolean(camera, "cameraReady", true)
        setField(camera, "activeCameraRoute", CameraRoute.BACK)
        setField(camera, "facing", CameraRoute.BACK.facing)
        setBoolean(camera, "started", true)
        setBoolean(camera, "paused", false)
        if (withInputSurface) {
            val st = SurfaceTexture(0).also { texture = it }
            val input = Surface(st).also { surface = it }
            val gl = CameraEngine::class.java.getDeclaredMethod("getGl")
                .apply { isAccessible = true }
                .invoke(camera) as GlPipeline
            GlPipeline::class.java.getDeclaredField("inputSurface")
                .apply { isAccessible = true }
                .set(gl, input)
        }
        return camera
    }

    private fun captureStatuses(camera: CameraEngine): MutableList<CameraStatusMessage> {
        val statuses = mutableListOf<CameraStatusMessage>()
        camera.onStatus = { status -> status?.let { statuses += it.message } }
        return statuses
    }

    private fun bareTransaction(camera: CameraEngine): Any {
        val desired = CameraEngine::class.java.declaredMethods
            .single { it.name == "currentOpticsReconfiguration" }
            .apply { isAccessible = true }
            .invoke(camera)!!
        val transaction = field(desired, "transaction")!!
        assertFalse("a bare door's baseline follows its mutation", field(transaction, "baselinePrecedesMutation") as Boolean)
        return transaction
    }

    private fun handlePreflightFailure(camera: CameraEngine, transaction: Any, preflight: Long) {
        CameraEngine::class.java.declaredMethods.single { it.name == "handlePreflightFailure" }
            .apply { isAccessible = true }
            .invoke(camera, transaction, false, preflight, null)
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(owner)

    private fun setField(owner: Any, name: String, value: Any?) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    private fun setBoolean(owner: Any, name: String, value: Boolean) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.setBoolean(owner, value)
    }

    @After
    fun tearDown() {
        engine?.let { setBoolean(it, "paused", true) }
        engine?.release()
        surface?.release()
        texture?.release()
    }
}
