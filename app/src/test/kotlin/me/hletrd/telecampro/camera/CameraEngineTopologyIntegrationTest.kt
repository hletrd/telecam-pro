package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicLong
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Engine-composition coverage for topology truth and REC lease ownership. */
@RunWith(RobolectricTestRunner::class)
class CameraEngineTopologyIntegrationTest {
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
    fun `discovery invalidation preserves accepted optical truth until convergence`() {
        val engine = engine()
        engine.setPrivateField("acceptedOpticalPresets", setOf(LensChoice.TELE3X, LensChoice.TELE10X))
        engine.setPrivateField("lensInventoryPublished", true)

        engine.invokePrivate("invalidateCameraTopologyCaches")

        assertEquals(
            setOf(LensChoice.TELE3X, LensChoice.TELE10X),
            engine.privateField<Set<LensChoice>>("acceptedOpticalPresets"),
        )
        assertFalse(engine.privateField("lensInventoryPublished"))
    }

    @Test
    fun `post-publication failure cannot release Engine topology isolation`() {
        val engine = engine()
        val convergence = engine.privateField<CameraRouteTopologyConvergence>("routeTopologyConvergence")
        val active = engine.privateField<AtomicLong>("activeRecordingTopologyLease")
        val lease = convergence.beginRecording()
        assertTrue(active.compareAndSet(0L, lease))
        convergence.offer(41L)
        assertTrue(convergence.transferToRecorder(lease))

        engine.invokePrivate("releaseRecordingAdmissionTopologyLease", lease)

        assertEquals(lease, active.get())
        assertEquals(RecordingTopologyLeaseStage.RECORDER, convergence.leaseStage(lease))
        assertNull(convergence.claim())

        // Checked native finalization/quarantine owns the terminal release after publication.
        engine.invokePrivate("releaseRecordingTopologyLease", lease as Long?)
        assertEquals(0L, active.get())
        assertNull(convergence.leaseStage(lease))
        assertEquals(41L, convergence.claim())
    }

    @Test
    fun `pre-publication failure releases Engine topology admission`() {
        val engine = engine()
        val convergence = engine.privateField<CameraRouteTopologyConvergence>("routeTopologyConvergence")
        val active = engine.privateField<AtomicLong>("activeRecordingTopologyLease")
        val lease = convergence.beginRecording()
        assertTrue(active.compareAndSet(0L, lease))
        convergence.offer(52L)

        engine.invokePrivate("releaseRecordingAdmissionTopologyLease", lease)

        assertEquals(0L, active.get())
        assertNull(convergence.leaseStage(lease))
        assertEquals(52L, convergence.claim())
    }

    @Test
    fun `same-id availability retries a latest incomplete inventory after timer exhaustion`() {
        assertTrue(
            routeAvailabilityRefreshRequired(
                inventoryResolved = false,
                idsUnchanged = true,
                identitiesUnchanged = true,
            ),
        )
        assertFalse(
            routeAvailabilityRefreshRequired(
                inventoryResolved = true,
                idsUnchanged = true,
                identitiesUnchanged = true,
            ),
        )
        assertTrue(
            routeAvailabilityRefreshRequired(
                inventoryResolved = true,
                idsUnchanged = true,
                identitiesUnchanged = false,
            ),
        )
    }

    private fun engine(): CameraEngine = CameraEngine(app).also(engines::add)

    private fun Any.setPrivateField(name: String, value: Any?) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.privateField(name: String): T =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun Any.invokePrivate(name: String, vararg arguments: Any?) {
        val method = javaClass.declaredMethods.single { candidate ->
            candidate.name == name && candidate.parameterCount == arguments.size
        }
        method.isAccessible = true
        method.invoke(this, *arguments)
    }
}
