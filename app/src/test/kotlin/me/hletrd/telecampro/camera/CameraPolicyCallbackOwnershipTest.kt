package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraPolicyCallbackOwnershipTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `ready publishes policy unblock only after the optics monitor releases`() {
        val engine = CameraEngine(app)
        val controller = CameraController(app)
        setField(engine, "controller", controller)
        setField(engine, "previewReady", true)
        setField(engine, "cameraPolicyBlocked", true)
        val lockStates = mutableListOf<Boolean>()
        var reentered = false
        engine.onCameraPolicyBlocked = { blocked ->
            assertFalse(blocked)
            lockStates += Thread.holdsLock(engine)
            synchronized(engine) {
                reentered = engine.isOpticsGenerationCurrent(0L)
            }
        }

        val method = CameraEngine::class.java.declaredMethods.single {
            it.name == "commitOpticsReady" && it.parameterTypes.size == 6
        }.apply { isAccessible = true }
        val committed = method.invoke(
            engine,
            0L,
            controller,
            PhotoSessionOutputs(),
            0L,
            {},
            null,
        ) as Boolean

        assertTrue(committed)
        assertEquals(listOf(false), lockStates)
        assertTrue(reentered)
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
