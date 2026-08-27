package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CameraPolicyCallbackOwnershipTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun installEglSentinels() {
        RobolectricEglSentinels.ensure()
    }

    @Test
    fun `ready publishes policy unblock only after the optics monitor releases`() {
        val engine = CameraEngine(app)
        val controller = CameraController(app)
        setField(engine, "controller", controller)
        setField(engine, "previewReady", true)
        setField(engine, "cameraPolicyBlocked", true)
        val lockStates = mutableListOf<Boolean>()
        var reentered = false
        engine.onCameraPolicyBlocked = { publication ->
            assertFalse(publication.blocked)
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

    @Test
    fun `old AppOps result cannot relatch after replacement Ready or poison later exhaustion`() {
        val queryEntered = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val callbacks = CopyOnWriteArrayList<CameraPolicyPublication>()
        val statuses = CopyOnWriteArrayList<CameraStatusMessage>()
        val engine = CameraEngine(
            app,
            cameraPolicyOverrides = CameraPolicyEngineOverrides {
                queryEntered.countDown()
                releaseQuery.await(2, TimeUnit.SECONDS)
                true
            },
        )
        val failed = CameraController(app)
        setField(engine, "controller", failed)
        setField(engine, "readyController", failed)
        setField(engine, "cameraReady", true)
        setField(engine, "previewReady", true)
        setField(engine, "started", true)
        engine.onCameraPolicyBlocked = callbacks::add
        engine.onStatus = { it?.message?.let(statuses::add) }

        val failureThread = Thread {
            CameraEngine::class.java.declaredMethods.single { it.name == "handleActiveCameraFailure" }
                .apply { isAccessible = true }
                .invoke(engine, failed, CameraPolicyBlockedException("old policy failure"))
        }.apply { start() }
        assertTrue(queryEntered.await(2, TimeUnit.SECONDS))

        val replacement = CameraController(app)
        setField(engine, "controller", replacement)
        setField(engine, "previewReady", true)
        val sessionGeneration = (field(engine, "cameraSessionGeneration") as java.util.concurrent.atomic.AtomicLong).get()
        assertTrue(commitReady(engine, replacement, sessionGeneration))

        releaseQuery.countDown()
        failureThread.join(2_000)
        assertFalse(failureThread.isAlive)
        assertFalse(field(engine, "cameraPolicyBlocked") as Boolean)
        assertTrue(callbacks.none { it.blocked })

        // A later unrelated controller exhausts its own retry budget. It must report ordinary
        // unavailability, never consume the stale old AppOps answer as current policy truth.
        setField(engine, "cameraRecoveryAttempts", 3)
        CameraEngine::class.java.declaredMethods.single { it.name == "scheduleCameraRecovery" }
            .apply { isAccessible = true }
            .invoke(engine, replacement)
        assertTrue(callbacks.none { it.blocked })
        assertTrue(CameraStatusMessage.CAMERA_UNAVAILABLE_REOPEN in statuses)
    }

    private fun commitReady(
        engine: CameraEngine,
        controller: CameraController,
        sessionGeneration: Long,
    ): Boolean = CameraEngine::class.java.declaredMethods.single {
        it.name == "commitOpticsReady" && it.parameterTypes.size == 6
    }.apply { isAccessible = true }.invoke(
        engine,
        0L,
        controller,
        PhotoSessionOutputs(),
        sessionGeneration,
        {},
        null,
    ) as Boolean

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
