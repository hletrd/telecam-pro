package me.hletrd.telecampro.camera

import android.Manifest
import android.app.Application
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import me.hletrd.telecampro.ui.ViewModelTestAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowCameraCharacteristics

/**
 * AGG6-30 (TE6-4/5/6): the still terminals and the frozen AF packet driven through the REAL
 * [CameraController.capturePhoto] request build and Camera2 callback wiring, not their pure seams.
 * A capturing [CameraCaptureSession] hands the test the exact callback and request the controller
 * submitted.
 */
@RunWith(RobolectricTestRunner::class)
class CameraControllerStillTerminalTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<CameraController>()
    private val readers = mutableListOf<ImageReader>()

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.close() } }
        readers.forEach { runCatching { it.close() } }
    }

    private class Outcome {
        val errors = CopyOnWriteArrayList<String>()
        val photos = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
    }

    private fun callback(outcome: Outcome) = object : CameraController.PhotoCallback {
        override fun onPhoto(
            jpeg: Image?,
            raw: Image?,
            result: TotalCaptureResult,
            rawChars: CameraCharacteristics?,
            takenAtMs: Long,
        ) {
            outcome.photos += (jpeg != null) to (rawChars != null)
        }

        override fun onError(t: Throwable) {
            outcome.errors += t.message.orEmpty()
        }
    }

    private class CapturingSession(private val device: CameraDevice) : CameraCaptureSession() {
        val requests = CopyOnWriteArrayList<CaptureRequest>()
        val callbacks = CopyOnWriteArrayList<CaptureCallback>()

        override fun getDevice(): CameraDevice = device
        override fun prepare(surface: Surface) = Unit
        override fun finalizeOutputConfigurations(
            outputConfigs: MutableList<android.hardware.camera2.params.OutputConfiguration>?,
        ) = Unit
        override fun capture(request: CaptureRequest, listener: CaptureCallback?, handler: Handler?): Int {
            requests += request
            listener?.let(callbacks::add)
            return requests.size
        }
        override fun captureBurst(
            requests: MutableList<CaptureRequest>,
            listener: CaptureCallback?,
            handler: Handler?,
        ): Int = 0
        override fun setRepeatingRequest(request: CaptureRequest, listener: CaptureCallback?, handler: Handler?) = 0
        override fun setRepeatingBurst(
            requests: MutableList<CaptureRequest>,
            listener: CaptureCallback?,
            handler: Handler?,
        ): Int = 0
        override fun stopRepeating() = Unit
        override fun abortCaptures() = Unit
        override fun isReprocessable(): Boolean = false
        override fun getInputSurface(): Surface? = null
        override fun close() = Unit
    }

    private class Harness(
        val controller: CameraController,
        val session: CapturingSession,
        val jpegSurface: Surface,
    )

    private fun harness(): Harness {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val manager = app.getSystemService(CameraManager::class.java)
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(chars).set(CameraCharacteristics.LENS_FACING, CameraMetadata.LENS_FACING_BACK)
        shadowOf(manager).addCamera("0", chars)
        var device: CameraDevice? = null
        manager.openCamera(
            "0",
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                }
                override fun onDisconnected(camera: CameraDevice) = Unit
                override fun onError(camera: CameraDevice, error: Int) = Unit
            },
            Handler(Looper.getMainLooper()),
        )
        shadowOf(Looper.getMainLooper()).idle()
        val opened = checkNotNull(device) { "Robolectric did not open the fake camera" }
        val reader = ImageReader.newInstance(64, 48, ImageFormat.JPEG, 2).also(readers::add)
        val session = CapturingSession(opened)
        val controller = CameraController(app).also(controllers::add)
        setField(controller, "device", opened)
        setField(controller, "session", session)
        setField(controller, "jpegReader", reader)
        setField(controller, "caps", ViewModelTestAccess.caps())
        return Harness(controller, session, reader.surface)
    }

    private fun press(h: Harness, outcome: Outcome, af: StillAfInputs = StillAfInputs(false, 0f), controls: ManualControls = ManualControls()) {
        h.controller.capturePhoto(
            wantJpeg = true,
            wantRaw = false,
            cb = callback(outcome),
            chainHead = true,
            frozen = StillShotFreeze(controls, af),
        )
        drainCamera(h.controller)
    }

    private fun drainCamera(controller: CameraController) {
        val handler = getField(controller, "handler") as Handler
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun onCamera(controller: CameraController, block: () -> Unit) {
        val handler = getField(controller, "handler") as Handler
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a lost jpeg buffer fails the shot once and frees the shutter`() {
        val h = harness()
        val outcome = Outcome()
        press(h, outcome)
        val shot = h.session.callbacks.single()

        onCamera(h.controller) {
            shot.onCaptureBufferLost(h.session, h.session.requests.single(), h.jpegSurface, 1L)
            shot.onCaptureBufferLost(h.session, h.session.requests.single(), h.jpegSurface, 1L)
        }

        assertEquals(listOf("Capture buffer lost"), outcome.errors.toList())
        assertNull(getField(h.controller, "pending"))
        val next = Outcome()
        press(h, next)
        assertEquals("the next press is admitted, not 'already in progress'", 2, h.session.requests.size)
        assertTrue(next.errors.isEmpty())
    }

    @Test
    fun `a lost buffer on a surface the still never carried leaves the shot pending`() {
        val h = harness()
        val outcome = Outcome()
        press(h, outcome)
        val foreign = ImageReader.newInstance(64, 48, ImageFormat.YUV_420_888, 2).also(readers::add).surface

        onCamera(h.controller) {
            h.session.callbacks.single().onCaptureBufferLost(h.session, h.session.requests.single(), foreign, 1L)
        }

        assertTrue(outcome.errors.isEmpty())
        assertNotNull(getField(h.controller, "pending"))
    }

    @Test
    fun `an aborted sequence fails the shot once and frees the shutter`() {
        val h = harness()
        val outcome = Outcome()
        press(h, outcome)
        val shot = h.session.callbacks.single()

        onCamera(h.controller) {
            shot.onCaptureSequenceAborted(h.session, 1)
            shot.onCaptureSequenceAborted(h.session, 1)
        }

        assertEquals(listOf("Capture sequence aborted"), outcome.errors.toList())
        assertNull(getField(h.controller, "pending"))
    }

    // AGG5-29 / TE6-4: the live touch-AF hold flipped after the press must not reach the still.
    @Test
    fun `the still request carries the frozen AF inputs, not the live ones`() {
        val h = harness()
        setField(
            h.controller,
            "caps",
            ViewModelTestAccess.caps().copy(
                maxAfRegions = 1,
                afModes = intArrayOf(
                    CameraMetadata.CONTROL_AF_MODE_OFF,
                    CameraMetadata.CONTROL_AF_MODE_AUTO,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                ),
            ),
        )
        val continuous = ManualControls(focusMode = FocusMode.CONTINUOUS)

        // Press taken with no tap hold; a tap then lands while the DNG pre-allocation runs.
        setField(h.controller, "touchAfActive", true)
        press(h, Outcome(), af = StillAfInputs(touchAfActive = false, lastFocusDistance = 0f), controls = continuous)
        // The control: a press taken WITH the hold does fire in AF_MODE_AUTO.
        onCamera(h.controller) { setField(h.controller, "pending", null) }
        press(h, Outcome(), af = StillAfInputs(touchAfActive = true, lastFocusDistance = 0f), controls = continuous)

        val (frozenOff, frozenOn) = h.session.requests.map { it.get(CaptureRequest.CONTROL_AF_MODE) }
        assertEquals(CaptureRequest.CONTROL_AF_MODE_AUTO, frozenOn)
        assertTrue(
            "a press taken without the hold must not fire in its AF_MODE_AUTO",
            frozenOff != CaptureRequest.CONTROL_AF_MODE_AUTO,
        )
    }

    // AGG5-41 / TE6-6: only DngCreator needs the characteristics. Driven through tryComplete's call
    // site, a processed-only shot whose read failed reaches onPhoto; a RAW shot fails.
    @Test
    fun `a failed characteristics read fails only the shot that wants RAW`() {
        val h = harness()
        val rawReader = ImageReader.newInstance(64, 48, ImageFormat.JPEG, 2).also(readers::add)
        setField(h.controller, "rawReader", rawReader)

        val processed = Outcome()
        press(h, processed)
        completeWith(h, image = testImage(), raw = false)
        assertEquals(listOf(true to false), processed.photos.toList())
        assertTrue(processed.errors.isEmpty())

        val rawShot = Outcome()
        h.controller.capturePhoto(
            wantJpeg = false,
            wantRaw = true,
            cb = callback(rawShot),
            chainHead = true,
            frozen = StillShotFreeze(ManualControls(), StillAfInputs(false, 0f)),
        )
        drainCamera(h.controller)
        completeWith(h, image = testImage(), raw = true)
        assertTrue(rawShot.photos.isEmpty())
        assertEquals(listOf("Missing camera characteristics"), rawShot.errors.toList())
    }

    private fun testImage(): Image {
        val reader = ImageReader.newInstance(64, 48, ImageFormat.JPEG, 2).also(readers::add)
        val canvas = reader.surface.lockCanvas(null)
        reader.surface.unlockCanvasAndPost(canvas)
        return checkNotNull(reader.acquireNextImage())
    }

    private fun completeWith(h: Harness, image: Image, raw: Boolean) {
        val pending = checkNotNull(getField(h.controller, "pending"))
        setField(pending, if (raw) "raw" else "jpeg", image)
        val metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val result = TotalCaptureResult::class.java.declaredConstructors
            .single { it.parameterCount == 2 }
            .apply { isAccessible = true }
            .newInstance(metadata, 0) as TotalCaptureResult
        onCamera(h.controller) {
            h.session.callbacks.last().onCaptureCompleted(h.session, h.session.requests.last(), result)
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
