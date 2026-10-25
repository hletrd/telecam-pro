package me.hletrd.telecampro.camera

import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hands a ten-bit camera stream to the GL chain: camera → YCBCR_P010 [ImageReader] (STANDARD
 * dynamic-range profile) → [ImageWriter] zero-copy attach → the GL input SurfaceTexture.
 *
 * Why a bridge: a camera-fed SurfaceTexture is a PRIVATE stream, and under the STANDARD profile this
 * HAL allocates it as 8-bit NV12 UBWC; ten bits there needed HLG10, which on PMA110 must drop JPEG
 * and RAW. P010 + STANDARD is advertised ((SRGB, P010, STANDARD) in the colour-space profiles) and
 * keeps both still readers, but GL cannot be the camera's P010 consumer directly. The writer attaches
 * each camera buffer to the SurfaceTexture's queue, so the existing renderer samples it through
 * samplerExternalOES unchanged (device-measured 2026-10-25: raw Y in GL at 94 distinct levels against
 * 27 for the same scene through an 8-bit stream; SurfaceTexture.timestamp == Image.timestamp, so
 * encoder PTS and gyro sync keep the sensor clock).
 *
 * Threading: forwarding runs on the bridge's own thread, never the camera thread — if GL falls
 * behind, the writer's queue blocks this thread and the camera drops frames into a full reader,
 * instead of the camera thread stalling behind GL.
 *
 * Ownership: the writer is a PRODUCER on the GL input Surface, exactly like a camera stream, and a
 * BufferQueue takes one producer at a time. [close] therefore must run after the camera has stopped
 * feeding the reader (device closed) and before any next session connects to the GL Surface directly
 * — CameraController's teardown order provides both.
 */
internal class P010InputBridge private constructor(
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

    /**
     * Idempotent. Releases the reader (camera side) and then the writer (disconnecting from the GL
     * input Surface) on the bridge thread, so no forward can interleave, and waits a bounded time.
     */
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
        /**
         * Full-range BT.2020 YCbCr with an SDR-class transfer label — the tag the stock O-Log2 stream
         * itself wears. It names the matrix the HAL encodes with and the driver decodes with when GL
         * samples the buffer, so RGB round-trips; the S-Log3 code values stay full-range, which is
         * what the GL decode (`slog3Inverse`) expects.
         */
        val DATASPACE: Int = DataSpace.pack(
            DataSpace.STANDARD_BT2020,
            DataSpace.TRANSFER_SMPTE_170M,
            DataSpace.RANGE_FULL,
        )

        // Camera side: a few in flight plus one being forwarded. GL side: the SurfaceTexture latches
        // the newest frame and releases older ones, so the writer rarely holds more than two.
        private const val READER_MAX_IMAGES = 6
        private const val WRITER_MAX_IMAGES = 4
        private const val CLOSE_WAIT_MS = 1_000L

        /**
         * Builds the bridge or throws (the writer's producer connect fails while another producer —
         * a still-attached camera stream — owns [glInput]); the caller falls back to the 8-bit route.
         */
        fun create(glInput: Surface, width: Int, height: Int): P010InputBridge {
            val thread = HandlerThread("P010InputBridge").apply { start() }
            val handler = Handler(thread.looper)
            val writer = try {
                ImageWriter.Builder(glInput)
                    .setMaxImages(WRITER_MAX_IMAGES)
                    .setWidthAndHeight(width, height)
                    .setHardwareBufferFormat(HardwareBuffer.YCBCR_P010)
                    .setDataSpace(DATASPACE)
                    .setUsage(HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                    .build()
            } catch (t: Throwable) {
                thread.quitSafely()
                throw t
            }
            val reader = try {
                ImageReader.Builder(width, height)
                    .setMaxImages(READER_MAX_IMAGES)
                    .setDefaultHardwareBufferFormat(HardwareBuffer.YCBCR_P010)
                    .setDefaultDataSpace(DATASPACE)
                    .setUsage(HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                    .build()
            } catch (t: Throwable) {
                runCatching { writer.close() }
                thread.quitSafely()
                throw t
            }
            val bridge = P010InputBridge(reader, writer, thread, handler)
            reader.setOnImageAvailableListener(bridge::forward, handler)
            return bridge
        }
    }
}
