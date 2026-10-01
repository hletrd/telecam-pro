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

    private fun recallPhoto(camera: CameraEngine, lens: LensChoice, zoom: Float, rawWanted: Boolean) {
        val declaration = field(camera, "teleconverterDeclaration") as TeleconverterDeclaration
        assertTrue(
            camera.setResolvedOptics(
                enabledVideo = false,
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

    private fun forceOwnedRollback(camera: CameraEngine) {
        val generation = (field(camera, "opticsIntentGeneration") as AtomicLong).get()
        val baseline = checkNotNull(field(camera, "opticsRollbackBaseline"))
        val transactionType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "OpticsTransaction" }
        val transaction = transactionType.declaredConstructors.single()
            .apply { isAccessible = true }
            .newInstance(generation, baseline)
        CameraEngine::class.java.declaredMethods.single { it.name == "rollbackOptics" }
            .apply { isAccessible = true }
            .invoke(camera, transaction, CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED.status())
    }

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
