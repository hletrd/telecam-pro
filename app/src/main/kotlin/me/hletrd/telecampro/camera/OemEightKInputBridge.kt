package me.hletrd.telecampro.camera

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hands the OEM 8K stream ([OEM_8K_VIDEO_SIZE]) to the GL chain: camera → PRIVATE [ImageReader] with
 * GPU_SAMPLED_IMAGE | VIDEO_ENCODE usage → [ImageWriter] attach → the GL input SurfaceTexture.
 *
 * Why a bridge: no camera advertises 7680×4320, and cameraserver rounds every "flexible" consumer —
 * a SurfaceTexture, or a reader whose usage lacks the encoder bit — to the nearest ADVERTISED size
 * before the HAL sees it. The VIDEO_ENCODE bit makes this reader non-flexible, so the HAL receives the
 * real raster; the writer then attaches each buffer to the existing GL input queue, which samples it
 * through samplerExternalOES unchanged (device-measured 2026-10-31, the shape of [P010InputBridge]).
 *
 * Measured costs, accepted at 30 fps: the attach DETACHES each image from the reader, whose queue then
 * allocates a fresh 8K buffer, so every frame is a new buffer to GL (~2.6 ms in updateTexImage). A
 * recycling cache needs an NDK EGLImage per HardwareBuffer, which this app does not build. The camera's
 * buffers arrive complete (fence wait 0 ms); the first one ~420 ms after the first capture result.
 *
 * Threading and ownership are [P010InputBridge]'s: forwarding runs on the bridge's own thread, and the
 * writer is a PRODUCER on the GL input Surface, so [close] runs after the camera device closed and
 * before any next session connects there.
 */
internal class OemEightKInputBridge private constructor(
    private val reader: ImageReader,
    private val writer: ImageWriter,
    private val thread: HandlerThread,
    private val handler: Handler,
) {
    /** The camera output to configure in place of the GL input Surface. */
    val cameraSurface: Surface get() = reader.surface

    // Bridge-thread confined after construction; [close] flips it on that thread too.
    private var closed = false

    private fun forward(r: ImageReader) {
        val image = runCatching { r.acquireNextImage() }.getOrNull() ?: return
        if (closed) {
            image.close()
            return
        }
        // queueInputImage takes ownership of an attached Image; a refused one is still ours.
        runCatching { writer.queueInputImage(image) }.onFailure { image.close() }
    }

    /** Idempotent; same order and bounded wait as [P010InputBridge.close]. */
    fun close() {
        val done = CountDownLatch(1)
        val posted = runCatching {
            handler.post {
                if (!closed) {
                    closed = true
                    runCatching { reader.setOnImageAvailableListener(null, null) }
                    runCatching { reader.close() }
                    runCatching { writer.close() }
                }
                done.countDown()
            }
        }.getOrDefault(false)
        if (posted) done.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    companion object {
        // The measured probe's depths: a few 8K buffers in flight on the camera side, and the
        // SurfaceTexture latching the newest frame on the GL side.
        private const val READER_MAX_IMAGES = 6
        private const val WRITER_MAX_IMAGES = 4
        private const val CLOSE_WAIT_MS = 1_000L

        /**
         * GPU sampling for GL; VIDEO_ENCODE is what keeps cameraserver from rounding the raster (it
         * is never fed to an encoder directly — GL draws the encoder's input).
         */
        private const val READER_USAGE = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_VIDEO_ENCODE

        /** Builds the bridge or throws; the caller reports it as a refused 8K raster. */
        fun create(glInput: Surface, width: Int, height: Int): OemEightKInputBridge {
            val thread = HandlerThread("OemEightKInputBridge").apply { start() }
            val handler = Handler(thread.looper)
            val writer = try {
                ImageWriter.newInstance(glInput, WRITER_MAX_IMAGES, ImageFormat.PRIVATE)
            } catch (t: Throwable) {
                thread.quitSafely()
                throw t
            }
            val reader = try {
                ImageReader.newInstance(width, height, ImageFormat.PRIVATE, READER_MAX_IMAGES, READER_USAGE)
            } catch (t: Throwable) {
                runCatching { writer.close() }
                thread.quitSafely()
                throw t
            }
            val bridge = OemEightKInputBridge(reader, writer, thread, handler)
            reader.setOnImageAvailableListener(bridge::forward, handler)
            return bridge
        }
    }
}
