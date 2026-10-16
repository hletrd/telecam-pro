package me.hletrd.telecampro.camera

import android.app.Application
import android.graphics.ImageFormat
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MRG6-5: the AGG6-22 lane ownership proven through the ENGINE's own photo callback, not only
 * [StillSaveLanes]. A processed save is queued on a blocked `ioExecutor`; a later onError (the
 * controller's report of a throw out of onPhoto) must not settle the shot under that live save.
 * Deleting `lanes.enterPhoto()`, the onError `claimErrorTerminal()` guard, or onPhoto's own catch
 * fails here.
 */
@RunWith(RobolectricTestRunner::class)
class CameraEngineStillSaveLanesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var engine: CameraEngine? = null
    private val readers = mutableListOf<ImageReader>()

    init {
        RobolectricEglSentinels.ensure()
    }

    @After
    fun tearDown() {
        engine?.release()
        readers.forEach { runCatching { it.close() } }
    }

    @Test
    fun `onError after a delivered processed shot waits for the queued save`() {
        val camera = CameraEngine(app).also { engine = it }
        val done = AtomicInteger()
        val callback = photoCallback(camera, PhotoFormats(heif = false, jpeg = true, dngRaw = false)) {
            done.incrementAndGet()
        }
        val io = blockIo(camera)

        callback.onPhoto(testImage(), null, captureResult(), null, System.currentTimeMillis())
        callback.onError(IllegalStateException("thrown out of onPhoto"))
        assertEquals("the shot must not settle under its queued processed save", 0, done.get())

        io.release()
        assertEquals("the save's own terminal settles the shot exactly once", 1, done.get())
    }

    @Test
    fun `a throw inside onPhoto is contained and still waits for the queued save`() {
        val camera = CameraEngine(app).also { engine = it }
        val done = AtomicInteger()
        val callback = photoCallback(camera, PhotoFormats(heif = false, jpeg = true, dngRaw = true)) {
            done.incrementAndGet()
        }
        val io = blockIo(camera)

        // A RAW Image without characteristics throws after the processed save was queued.
        callback.onPhoto(testImage(), testImage(), captureResult(), null, System.currentTimeMillis())
        assertEquals("the undecided DNG lane is finished; the processed lane is not", 0, done.get())

        io.release()
        assertEquals(1, done.get())
    }

    private class BlockedIo(private val camera: CameraEngine, private val gate: CountDownLatch) {
        fun release() {
            gate.countDown()
            val io = camera.javaClass.getDeclaredField("ioExecutor").apply { isAccessible = true }
                .get(camera) as ExecutorService
            // Two passes: the save task, then anything it queued behind itself.
            repeat(2) { io.submit {}.get(10, TimeUnit.SECONDS) }
        }
    }

    private fun blockIo(camera: CameraEngine): BlockedIo {
        val io = CameraEngine::class.java.getDeclaredField("ioExecutor").apply { isAccessible = true }
            .get(camera) as ExecutorService
        val gate = CountDownLatch(1)
        val parked = CountDownLatch(1)
        io.execute {
            parked.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        check(parked.await(5, TimeUnit.SECONDS))
        return BlockedIo(camera, gate)
    }

    private fun photoCallback(
        camera: CameraEngine,
        formats: PhotoFormats,
        onDone: () -> Unit,
    ): CameraController.PhotoCallback {
        val optics = CameraEngine::class.java.getDeclaredMethod("snapshotShotOptics")
            .apply { isAccessible = true }
            .invoke(camera)
        val method = CameraEngine::class.java.declaredMethods
            .single { it.name == "photoCallback" && it.parameterCount == 12 }
            .apply { isAccessible = true }
        return checkNotNull(
            method.invoke(
                camera,
                formats,
                ManualControls(),
                false,
                optics,
                null,
                CaptureFamilyTraceAdmission(),
                onDone,
                null,
                null,
                null,
                null,
                false,
            ) as CameraController.PhotoCallback?,
        )
    }

    /**
     * A Robolectric JPEG [Image] with one readable plane. Robolectric's SurfaceImage carries no
     * planes, so StillSnapshot would fail and the processed save would never be queued — the very
     * hand-off these tests need.
     */
    private fun testImage(): Image {
        val reader = ImageReader.newInstance(64, 48, ImageFormat.JPEG, 2).also(readers::add)
        val canvas = reader.surface.lockCanvas(null)
        reader.surface.unlockCanvasAndPost(canvas)
        val image = checkNotNull(reader.acquireNextImage())
        val planeType = Class.forName("android.media.ImageReader\$SurfaceImage\$SurfacePlane")
        val constructor = planeType.declaredConstructors
            .single { c -> c.parameterTypes.contentEquals(arrayOf(image.javaClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, java.nio.ByteBuffer::class.java)) }
            .apply { isAccessible = true }
        val buffer = java.nio.ByteBuffer.wrap(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val args = constructor.parameterTypes.map { type ->
            when {
                type.isInstance(image) -> image
                type == java.nio.ByteBuffer::class.java -> buffer
                type == Int::class.javaPrimitiveType -> 1
                else -> error("unexpected SurfacePlane parameter $type")
            }
        }
        val plane = constructor.newInstance(*args.toTypedArray())
        val planes = java.lang.reflect.Array.newInstance(planeType, 1).also {
            java.lang.reflect.Array.set(it, 0, plane)
        }
        image.javaClass.getDeclaredField("mPlanes").apply { isAccessible = true }.set(image, planes)
        return image
    }

    private fun captureResult(): TotalCaptureResult {
        val metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        return TotalCaptureResult::class.java.declaredConstructors
            .single { it.parameterCount == 2 }
            .apply { isAccessible = true }
            .newInstance(metadata, 0) as TotalCaptureResult
    }
}
