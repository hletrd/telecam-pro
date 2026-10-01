package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBuild
import java.util.concurrent.atomic.AtomicLong

/**
 * Engine-level route-input transactions (RPL cycle 2, lane A1). The engine is never started, so no
 * Camera2 work is queued; each test drives the real transaction/rollback bodies and inspects the
 * resulting engine fields, exactly like the facing/recall rollback suites.
 */
@RunWith(RobolectricTestRunner::class)
class OpticsRouteInputTransactionRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var engine: CameraEngine? = null

    @Test
    fun `recall publishes the DNG intent inside its own optics transaction`() {
        val camera = acceptedPma110Engine()
        val directWritesBefore = getLong(camera, "rawWantedDirectWrites")

        recallPhoto(camera, LensChoice.TELE3X, zoom = 1f, rawWanted = true)

        // The setup task of THIS transaction must already resolve the standalone route: the band
        // predicate the fast-path terminal asks must not read the lens-local 1.0 as unified (MAIN).
        assertTrue(getBoolean(camera, "rawWanted"))
        assertFalse(lensBandFollowsZoom(camera))
        assertEquals(LensChoice.TELE3X, field(camera, "lensChoice"))
        assertEquals("recall is not a direct DNG write", directWritesBefore, getLong(camera, "rawWantedDirectWrites"))
        // The rollback baseline holds the pre-recall intent, so a failed recall restores both.
        forceOwnedRollback(camera)
        assertFalse(getBoolean(camera, "rawWanted"))
        assertEquals(LensChoice.MAIN, field(camera, "lensChoice"))
    }

    @Test
    fun `rollback drops a direct DNG write that would move the restored Photo route`() {
        val camera = acceptedPma110Engine()
        // In-flight Video recall: while it is pending, DNG on is a DIRECT write (Video keeps its
        // standalone lens whatever DNG says), so the counter moves.
        recallPhoto(camera, LensChoice.MAIN, zoom = 1f, rawWanted = false, video = true)
        camera.setRawWanted(true)
        assertTrue(getBoolean(camera, "rawWanted"))

        forceOwnedRollback(camera)

        // Restored Photo is logical: keeping DNG on would name the standalone route over it.
        assertFalse(getBoolean(camera, "videoMode"))
        assertFalse(getBoolean(camera, "rawWanted"))
        assertTrue(lensBandFollowsZoom(camera))
    }

    @Test
    fun `rollback keeps a direct DNG write that leaves the restored Video route unchanged`() {
        val camera = acceptedPma110Engine()
        setBoolean(camera, "videoMode", true)
        recallPhoto(camera, LensChoice.MAIN, zoom = 1f, rawWanted = false, video = true)
        camera.setRawWanted(true)

        forceOwnedRollback(camera)

        assertTrue(getBoolean(camera, "videoMode"))
        assertTrue("a newer operator choice survives an older failed door", getBoolean(camera, "rawWanted"))
    }

    @Test
    fun `preflight failure after the door's invalidation restores the still-streaming session`() {
        val camera = acceptedPma110Engine()
        setBoolean(camera, "previewReady", true)
        val controller = field(camera, "controller")
        // A lens/override door: not started, so reconfigureCamera stops before its own
        // invalidation and the test plays the setupExecutor half explicitly.
        camera.setCameraOverride("2")
        val transaction = currentTransaction(camera)
        val preflight = invoke(camera, "invalidateCameraReady") as Long

        invoke(
            camera,
            "rollbackOpticsAfterPreflight",
            transaction,
            CameraStatusMessage.CAMERA_UNAVAILABLE_CAMERA_UNCHANGED.status(),
            preflight,
        )

        assertTrue("shutter/REC must come back over the unchanged camera", getBoolean(camera, "cameraReady"))
        assertTrue(field(camera, "readyController") === controller)
        assertEquals(
            preflight,
            (field(camera, "cameraSessionGeneration") as AtomicLong).get(),
        )
    }

    @Test
    fun `a session bump after the preflight invalidation keeps the rollback Not-Ready`() {
        val camera = acceptedPma110Engine()
        setBoolean(camera, "previewReady", true)
        camera.setCameraOverride("2")
        val transaction = currentTransaction(camera)
        val preflight = invoke(camera, "invalidateCameraReady") as Long
        // e.g. the outgoing controller's own camera error advanced the session again.
        invoke(camera, "invalidateCameraReady")

        invoke(
            camera,
            "rollbackOpticsAfterPreflight",
            transaction,
            CameraStatusMessage.CAMERA_UNAVAILABLE_CAMERA_UNCHANGED.status(),
            preflight,
        )

        assertFalse(getBoolean(camera, "cameraReady"))
        assertEquals(null, field(camera, "acceptedCameraSession"))
    }

    // ---- fixtures ----

    private fun acceptedPma110Engine(): CameraEngine {
        RobolectricEglSentinels.ensure()
        // DeviceProfile resolves once from Build.MODEL at construction: only PMA110 carries the
        // standalone-only RAW law that makes DNG a route input.
        ShadowBuild.setModel("PMA110")
        val camera = CameraEngine(app)
        engine = camera
        setField(camera, "cameraRouteInventory", CameraRouteInventory(back = true, front = true, external = false))
        // An installed, Ready controller: the restorable baseline a failed door must return to.
        val controller = CameraController(app)
        setField(camera, "controller", controller)
        setField(camera, "readyController", controller)
        setBoolean(camera, "cameraReady", true)
        return camera
    }

    private fun recallPhoto(
        camera: CameraEngine,
        lens: LensChoice,
        zoom: Float,
        rawWanted: Boolean,
        video: Boolean = false,
    ) {
        val declaration = field(camera, "teleconverterDeclaration") as TeleconverterDeclaration
        assertTrue(
            camera.setResolvedOptics(
                enabledVideo = video,
                resolvedLens = lens,
                resolvedTeleconverter = false,
                resolvedDeclaration = declaration,
                resolvedControls = ManualControls(zoomRatio = zoom),
                resolvedPhotoExposureTimeNs = ManualControls().exposureTimeNs,
                recalledVideoSize = null,
                resolvedTransfer = ColorTransfer.SDR,
                resolvedVideoCodec = VideoCodec.HEVC,
                resolvedVideoEncoderCandidates = emptyList(),
                resolvedRawWanted = rawWanted,
            ),
        )
    }

    private fun lensBandFollowsZoom(camera: CameraEngine): Boolean =
        CameraEngine::class.java.declaredMethods.single { it.name == "lensBandFollowsZoom" }
            .apply { isAccessible = true }
            .invoke(camera, false, false, CameraRoute.BACK) as Boolean

    private fun currentTransaction(camera: CameraEngine): Any {
        val generation = (field(camera, "opticsIntentGeneration") as AtomicLong).get()
        val baseline = checkNotNull(field(camera, "opticsRollbackBaseline"))
        val transactionType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "OpticsTransaction" }
        return transactionType.declaredConstructors.single()
            .apply { isAccessible = true }
            .newInstance(generation, baseline)
    }

    private fun forceOwnedRollback(camera: CameraEngine) {
        invoke(
            camera,
            "rollbackOptics",
            currentTransaction(camera),
            CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED.status(),
        )
    }

    private fun invoke(camera: CameraEngine, name: String, vararg args: Any?): Any? =
        CameraEngine::class.java.declaredMethods.single { it.name == name }
            .apply { isAccessible = true }
            .invoke(camera, *args)

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(owner)

    private fun getBoolean(owner: Any, name: String): Boolean = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .getBoolean(owner)

    private fun getLong(owner: Any, name: String): Long = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .getLong(owner)

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
