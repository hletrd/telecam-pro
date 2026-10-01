package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.gl.AtomicOwnerSlot
import me.hletrd.telecampro.gl.GlPipeline
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
class FacingRollbackPunchInRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var engine: CameraEngine? = null

    @Test
    fun `failed front entry restores rear punch-in and replays it to replacement GL`() {
        val camera = acceptedRoute(CameraRoute.BACK)
        val assists = rendererAssists(camera)
        assertTrue(assists.isPunchInEnabled())

        camera.setFrontCamera(true)
        assertFalse("front candidate suppresses the independent intent", assists.isPunchInEnabled())

        forceOwnedRollback(camera)
        assertTrue("restored rear route resolves the retained intent", assists.isPunchInEnabled())
        replayIntoReplacement(camera, assists)
        assertTrue("replacement generation retains rear loupe truth", assists.isPunchInEnabled())
    }

    @Test
    fun `failed front exit restores suppression and replays it to replacement GL`() {
        val camera = acceptedRoute(CameraRoute.FRONT)
        val assists = rendererAssists(camera)
        assertFalse(assists.isPunchInEnabled())

        camera.setFrontCamera(false)
        assertTrue("rear candidate resolves the retained intent", assists.isPunchInEnabled())

        forceOwnedRollback(camera)
        assertFalse("restored front route suppresses the loupe", assists.isPunchInEnabled())
        replayIntoReplacement(camera, assists)
        assertFalse("replacement generation retains front suppression", assists.isPunchInEnabled())
    }

    /**
     * AGG3-1, engine half: a Video TELE3X at lens-local 4.0 enters FRONT with the lens-based
     * snapshot (unified 12) and must come back at 4.0 on the 3× camera — the ratio-based
     * `localZoomOf` divided by the 10× lens's base and landed 1.2 (3.6× framing).
     */
    @Test
    fun `front round trip returns a standalone TELE3X to its lens-local zoom`() {
        val camera = acceptedRoute(CameraRoute.BACK)
        setBoolean(camera, "videoMode", true)
        setField(camera, "lensChoice", LensChoice.TELE3X)
        setField(camera, "acceptedOpticalPresets", LensChoice.entries.toSet())
        setField(camera, "controls", ManualControls(zoomRatio = 4f))

        camera.setFrontCamera(true)
        assertEquals(1f, (field(camera, "controls") as ManualControls).zoomRatio, 0f)
        camera.setFrontCamera(false)

        assertEquals(4f, (field(camera, "controls") as ManualControls).zoomRatio, 1e-4f)
    }

    /**
     * AGG3-7, wiring half: a bare-reopen token ([currentOpticsReconfiguration], snapshotted after
     * the door's own write) whose preflight fails must publish Not-Ready, never re-accept the
     * outgoing controller under its own preflight invalidation. Since AGG4-2 such a token is routed
     * to the bounded retry before it reaches this rollback; this pins the guard kept as defence in
     * depth.
     */
    @Test
    fun `bare reopen preflight failure stays Not-Ready`() {
        val camera = acceptedRoute(CameraRoute.BACK)
        setBoolean(camera, "paused", false)
        val sessions = field(camera, "cameraSessionGeneration") as AtomicLong
        val desired = CameraEngine::class.java.declaredMethods
            .single { it.name == "currentOpticsReconfiguration" }
            .apply { isAccessible = true }
            .invoke(camera)!!
        val transaction = field(desired, "transaction")!!
        assertFalse(field(transaction, "baselinePrecedesMutation") as Boolean)
        // The reopen's own pre-close invalidation.
        val preflight = sessions.incrementAndGet()

        CameraEngine::class.java.declaredMethods.single { it.name == "rollbackOpticsAfterPreflight" }
            .apply { isAccessible = true }
            .invoke(
                camera,
                transaction,
                CameraStatusMessage.CAMERA_UNAVAILABLE_CAMERA_UNCHANGED.status(),
                preflight,
            )

        assertFalse(field(camera, "cameraReady") as Boolean)
        assertEquals("Not-Ready rollback retires the session", preflight + 1, sessions.get())
    }

    private fun acceptedRoute(route: CameraRoute): CameraEngine {
        RobolectricEglSentinels.ensure()
        val camera = CameraEngine(app)
        engine = camera
        setBoolean(camera, "paused", true) // keeps the queued Camera2 preflight inert
        setBoolean(camera, "cameraReady", true)
        setField(camera, "activeCameraRoute", route)
        setField(camera, "facing", route.facing)
        setField(camera, "cameraRouteInventory", CameraRouteInventory(back = true, front = true, external = false))
        camera.setPunchIn(true)
        return camera
    }

    private fun forceOwnedRollback(camera: CameraEngine) {
        val generation = (field(camera, "opticsIntentGeneration") as AtomicLong).get()
        val baseline = checkNotNull(field(camera, "opticsRollbackBaseline"))
        val transactionType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "OpticsTransaction" }
        val transaction = transactionType.declaredConstructors.single()
            .apply { isAccessible = true }
            .newInstance(generation, baseline, true)
        CameraEngine::class.java.declaredMethods.single { it.name == "rollbackOptics" }
            .apply { isAccessible = true }
            .invoke(camera, transaction, CameraStatusMessage.CAMERA_UNAVAILABLE_FACING_UNCHANGED.status())
    }

    @Suppress("UNCHECKED_CAST")
    private fun replayIntoReplacement(camera: CameraEngine, assists: RendererAssists) {
        val owners = field(camera, "glOwners") as AtomicOwnerSlot<GlPipeline>
        val old = owners.current()
        val replacement = checkNotNull(owners.replaceIfOwned(old))
        assists.replayAll(replacement)
        assertTrue(owners.owns(replacement))
    }

    private fun rendererAssists(camera: CameraEngine) = field(camera, "rendererAssists") as RendererAssists

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
        engine?.release()
    }
}
