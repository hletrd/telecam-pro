package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicReference
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AGG5-5 (PR5-1): invalidateCameraReady clears Ready/session BEFORE it cancels DNG pre-capture
 * owners, so a cancelled owner's settle — which runs a BURST/AEB continuation gated on the accepted
 * session — can never observe the dying session as current and re-dispatch onto it.
 */
@RunWith(RobolectricTestRunner::class)
class CameraEngineDngInvalidationTest {
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
    fun `a cancelled DNG owner settles only after Ready and the accepted session are cleared`() {
        val camera = CameraEngine(app).also { engine = it }
        val controller = CameraController(app)
        setField(camera, "controller", controller)
        setField(camera, "readyController", controller)
        setField(camera, "cameraReady", true)
        val readyAtSettle = AtomicReference<Boolean?>(null)
        val readyControllerAtSettle = AtomicReference<Any?>(controller)
        val owner = DngPreCaptureAllocation<String>(
            dispatch = { RecordingPreNativeSubmission(RecordingPreNativeDispatch.ACCEPTED) },
            allocate = { null },
            isCurrent = { true },
            onReady = {},
            onLateValue = {},
            onFailure = {},
            onRetired = {
                // What a BURST/AEB `fire(shot + 1)` gate would read at this exact instant.
                readyAtSettle.set(getField(camera, "cameraReady") as Boolean)
                readyControllerAtSettle.set(getField(camera, "readyController"))
            },
        )
        val lock = getField(camera, "dngPreCaptureAllocationLock")!!
        @Suppress("UNCHECKED_CAST")
        val owners = getField(camera, "dngPreCaptureAllocations") as MutableSet<Any>
        synchronized(lock) { owners.add(owner) }

        val method = CameraEngine::class.java.getDeclaredMethod("invalidateCameraReady")
        method.isAccessible = true
        method.invoke(camera)

        assertEquals(false, readyAtSettle.get())
        assertNull(readyControllerAtSettle.get())
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
