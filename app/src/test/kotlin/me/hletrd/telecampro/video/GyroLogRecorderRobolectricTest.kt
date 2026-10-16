package me.hletrd.telecampro.video

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowContentResolver

/**
 * [GyroLogRecorder]'s lifecycle against Robolectric's SensorManager and a fake `media` provider:
 * the temp-file stream, the first-frame/pre-roll rules, the storage verdict, and every discard
 * door. The sensor thread is injected so each test idles it deterministically, and the finalizer
 * runs inline on that thread, so a verdict is fully settled once the sensor looper is idle.
 * [GyroflowLogTest] owns the pure row/header formatting; this file only proves the recorder feeds
 * those rules the right numbers and lands the result where Gyroflow looks for it.
 */
@RunWith(RobolectricTestRunner::class)
class GyroLogRecorderRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sensorManager: SensorManager = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor = SensorBuilder.newBuilder().setType(Sensor.TYPE_GYROSCOPE).build()
    private val accel: Sensor = SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build()
    private lateinit var media: FakeMediaProvider

    @Before
    fun setUp() {
        media = FakeMediaProvider(File(context.cacheDir, "media-rows").apply { mkdirs() })
        media.attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, media)
    }

    @Test
    fun `a published take writes the header and pre-roll-bounded rows into Documents`() {
        val h = Harness()
        val sink = h.begin(spec(captureId = 7, readoutDirection = 1))
        assertNotNull(sink)
        assertTrue("an unanswered take wants the skew", h.recorder.wantsRollingShutterSkew())
        h.recorder.offerRollingShutterSkew(0L) // ignored: not a real readout time
        assertTrue(h.recorder.wantsRollingShutterSkew())
        h.recorder.offerRollingShutterSkew(SKEW_NS)
        h.recorder.offerRollingShutterSkew(SKEW_NS * 2) // first offer wins
        assertFalse("one skew per take is enough", h.recorder.wantsRollingShutterSkew())
        assertTrue("both sensors registered on the injected thread", h.listening())

        // Gyro before the first accelerometer sample: dropped, never paired with a zero gravity.
        h.gyro(FIRST_FRAME_NS - 450_000_000L, 9f, 9f, 9f)
        h.accel(FIRST_FRAME_NS - 440_000_000L, 0.1f, 9.8f, 0.2f)
        // Before the -0.5 s pre-roll window: recorded in the temp file but not written as a row.
        h.gyro(FIRST_FRAME_NS - 600_000_000L, 1f, 1f, 1f)
        h.gyro(FIRST_FRAME_NS - 400_000_000L, 0.5f, -0.25f, 0.125f)
        sink!!.onFirstEncodedFrame(0L) // not a frame time
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        sink.onFirstEncodedFrame(FIRST_FRAME_NS + 33_000_000L) // only the first frame rebases PTS
        h.accel(FIRST_FRAME_NS, 0f, 9.81f, 0f)
        h.gyro(FIRST_FRAME_NS + 1_000_500L, -1f, 2f, -3f)
        (sink as SensorEventListener).onAccuracyChanged(gyro, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)

        h.recorder.endSampling(7)
        h.recorder.endSampling(7) // idempotent
        h.idle()
        assertFalse("ending unregisters both sensors", h.listening())
        assertFalse("no take is sampling after the end", h.recorder.wantsRollingShutterSkew())
        h.recorder.complete(7, published = true)
        h.idle()

        val row = media.rows.single()
        assertEquals("VID_7.gcsv", row.inserted.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals(GyroLogRecorder.SIDECAR_MIME, row.inserted.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Documents/TeleCamPro/", GyroLogRecorder.SIDECAR_RELATIVE_PATH)
        assertEquals(GyroLogRecorder.SIDECAR_RELATIVE_PATH, row.inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1, row.inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(0, row.updates.single().getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        val expectedHeader = GyroflowLog.header(
            GyroflowLog.Header(
                orientation = "XyZ",
                note = "note",
                vendor = "vendor",
                videoFileName = "VID_7.mp4",
                unixStartSeconds = 1_700_000_000L,
                frameReadoutTimeNs = SKEW_NS,
                frameReadoutDirection = 1,
            ),
        )
        val expectedRows = StringBuilder()
        GyroflowLog.appendRow(expectedRows, -400_000L, 0.5f, -0.25f, 0.125f, 0.1f, 9.8f, 0.2f)
        GyroflowLog.appendRow(expectedRows, 1_000L, -1f, 2f, -3f, 0f, 9.81f, 0f)
        assertEquals(expectedHeader + expectedRows, row.file.readText())
        assertTrue("frame_readout_time rides the header", expectedHeader.contains("frame_readout_time,10.000"))
        assertEquals("the temp file is gone once the row is public", 0, h.tempFiles().size)
        assertEquals(0, h.failures.get())
    }

    @Test
    fun `an unknown-clock camera rebases sensor time and a long log flushes in chunks`() {
        val h = Harness()
        val offset = 5_000_000_000L
        val sink = h.begin(spec(captureId = 3, cameraTimestampRealtime = false, realtimeMinusMonotonicNs = offset))!!
        h.recorder.offerRollingShutterSkew(SKEW_NS) // no readout direction: header omits the pair
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        h.post {
            h.send(accel, offset + FIRST_FRAME_NS, 0f, 9.81f, 0f)
            repeat(ROWS_PAST_FLUSH) { i -> h.send(gyro, offset + FIRST_FRAME_NS + i * 5_000_000L, 0.001f * i, 0f, 0f) }
        }
        h.recorder.complete(3, published = true)
        h.idle()

        val text = media.rows.single().file.readText()
        assertFalse(text.contains("frame_readout_time"))
        val rows = text.substringAfter("t,gx,gy,gz,ax,ay,az\n").trimEnd('\n').split('\n')
        assertEquals(ROWS_PAST_FLUSH, rows.size)
        assertTrue(rows.first().startsWith("0,"))
        assertTrue(rows.last().startsWith("${(ROWS_PAST_FLUSH - 1) * 5_000L},"))
        assertEquals(0, h.failures.get())
    }

    @Test
    fun `an unpublished verdict deletes the temp file and never touches MediaStore`() {
        val h = Harness()
        val sink = h.begin(spec(captureId = 1))!!
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        h.accel(FIRST_FRAME_NS, 0f, 1f, 0f)
        h.gyro(FIRST_FRAME_NS, 1f, 0f, 0f)
        assertEquals(1, h.tempFiles().size)
        h.recorder.complete(1, published = false)
        h.idle()
        assertEquals(0, h.tempFiles().size)
        assertTrue(media.rows.isEmpty())
        assertEquals("a dropped clip owes no sidecar failure", 0, h.failures.get())
        h.recorder.complete(1, published = true) // the verdict was already consumed
        h.recorder.complete(99, published = true) // never begun
        h.idle()
        assertTrue(media.rows.isEmpty())
        assertEquals(0, h.failures.get())
    }

    @Test
    fun `a published take with nothing honest to write reports the sidecar failure`() {
        // No first frame: the rows have no PTS origin.
        Harness().apply {
            begin(spec(captureId = 1))
            accel(FIRST_FRAME_NS, 0f, 1f, 0f)
            gyro(FIRST_FRAME_NS, 1f, 0f, 0f)
            recorder.complete(1, published = true)
            idle()
            assertEquals(1, failures.get())
            assertEquals(0, tempFiles().size)
        }
        // A first frame but no gyro row (only accelerometer samples arrived).
        Harness().apply {
            begin(spec(captureId = 2))!!.onFirstEncodedFrame(FIRST_FRAME_NS)
            accel(FIRST_FRAME_NS, 0f, 1f, 0f)
            recorder.complete(2, published = true)
            idle()
            assertEquals(1, failures.get())
        }
        assertTrue(media.rows.isEmpty())
    }

    @Test
    fun `a device without both IMU sensors still gets a failed session`() {
        val h = Harness(sensors = false)
        val sink = h.begin(spec(captureId = 4))!!
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        assertFalse(h.listening())
        h.recorder.complete(4, published = true)
        h.idle()
        assertEquals(1, h.failures.get())
        assertTrue(media.rows.isEmpty())
    }

    @Test
    fun `a temp file that cannot be opened marks the take failed without registering sensors`() {
        val h = Harness()
        // A regular file where the temp DIRECTORY belongs: mkdirs fails and the open throws.
        File(context.cacheDir, GyroLogRecorder.TEMP_DIR).apply { deleteRecursively(); writeText("x") }
        val sink = h.begin(spec(captureId = 5))!!
        h.idle()
        assertFalse(h.listening())
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        h.recorder.complete(5, published = true)
        h.idle()
        assertEquals(1, h.failures.get())
        assertTrue(media.rows.isEmpty())
    }

    @Test
    fun `refused sensor registration unregisters closes and fails the take`() {
        val h = Harness()
        shadowOf(sensorManager).setForceListenersToFail(true)
        val sink = h.begin(spec(captureId = 6))!!
        h.idle()
        assertFalse(h.listening())
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        h.recorder.complete(6, published = true)
        h.idle()
        assertEquals(1, h.failures.get())
        assertEquals(0, h.tempFiles().size)
    }

    @Test
    fun `a temp write failure stops recording and fails a published take`() {
        val h = Harness(openTempStream = { file -> FailingStream(FileOutputStream(file), failWrite = true) })
        val sink = h.begin(spec(captureId = 8))!!
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        h.post {
            h.send(accel, FIRST_FRAME_NS, 0f, 1f, 0f)
            // Enough 32 B records to overflow the 64 KiB buffer, so the failing write surfaces.
            repeat(RECORDS_PAST_BUFFER) { h.send(gyro, FIRST_FRAME_NS + it, 1f, 0f, 0f) }
        }
        h.idle()
        h.gyro(FIRST_FRAME_NS + RECORDS_PAST_BUFFER, 1f, 0f, 0f) // stream closed: ignored
        h.recorder.complete(8, published = true)
        h.idle()
        assertEquals(1, h.failures.get())
        assertTrue(media.rows.isEmpty())
        assertEquals(0, h.tempFiles().size)
    }

    @Test
    fun `a temp close failure fails the take even though every write landed`() {
        val h = Harness(openTempStream = { file -> FailingStream(FileOutputStream(file), failClose = true) })
        val sink = h.begin(spec(captureId = 9))!!
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        h.accel(FIRST_FRAME_NS, 0f, 1f, 0f)
        h.gyro(FIRST_FRAME_NS, 1f, 0f, 0f)
        h.recorder.complete(9, published = true)
        h.idle()
        assertEquals(1, h.failures.get())
        assertTrue(media.rows.isEmpty())
    }

    @Test
    fun `the temp byte cap truncates the log instead of failing it`() {
        val h = Harness(maxTempBytes = 2 * GyroLogRecorder.RECORD_BYTES)
        val sink = h.begin(spec(captureId = 10))!!
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        h.accel(FIRST_FRAME_NS, 0f, 1f, 0f)
        repeat(5) { h.gyro(FIRST_FRAME_NS + it * 1_000L, it.toFloat(), 0f, 0f) }
        h.recorder.complete(10, published = true)
        h.idle()
        val rows = media.rows.single().file.readText().substringAfter("t,gx,gy,gz,ax,ay,az\n")
        assertEquals(2, rows.trimEnd('\n').split('\n').size)
        assertEquals(0, h.failures.get())
    }

    @Test
    fun `provider failures delete the pending row and report the sidecar failure`() {
        for (failure in FakeMediaProvider.Failure.entries) {
            setUp()
            media.failure = failure
            val h = Harness()
            val sink = h.begin(spec(captureId = 11))!!
            sink.onFirstEncodedFrame(FIRST_FRAME_NS)
            h.accel(FIRST_FRAME_NS, 0f, 1f, 0f)
            h.gyro(FIRST_FRAME_NS, 1f, 0f, 0f)
            h.recorder.complete(11, published = true)
            h.idle()
            assertEquals("$failure reports once", 1, h.failures.get())
            assertEquals("$failure leaves no temp file", 0, h.tempFiles().size)
            val expectDelete = failure != FakeMediaProvider.Failure.INSERT_THROWS &&
                failure != FakeMediaProvider.Failure.INSERT_NULL
            assertEquals("$failure deletes its pending row", expectDelete, media.deleted.isNotEmpty())
        }
    }

    @Test
    fun `a finalizer that refuses the verdict deletes the temp file and still reports`() {
        val h = Harness(finalizer = InlineExecutor().apply { shutdown() })
        val sink = h.begin(spec(captureId = 12))!!
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        h.accel(FIRST_FRAME_NS, 0f, 1f, 0f)
        h.gyro(FIRST_FRAME_NS, 1f, 0f, 0f)
        h.recorder.complete(12, published = true)
        h.idle()
        assertEquals(1, h.failures.get())
        assertEquals(0, h.tempFiles().size)
        assertTrue(media.rows.isEmpty())

        val unpublished = Harness(finalizer = InlineExecutor().apply { shutdown() })
        unpublished.begin(spec(captureId = 13))
        unpublished.recorder.complete(13, published = false)
        unpublished.idle()
        assertEquals("an unpublished refusal owes nothing", 0, unpublished.failures.get())
    }

    @Test
    fun `a failure callback that throws never escapes the sensor thread`() {
        val h = Harness(onFailed = { throw IllegalStateException("listener bug") })
        h.begin(spec(captureId = 14))
        h.recorder.complete(14, published = true)
        h.idle()
        assertEquals(1, h.failures.get())
    }

    @Test
    fun `a new take ends the stale one and evicts takes whose verdict never came`() {
        val h = Harness()
        val first = h.begin(spec(captureId = 1))!!
        h.idle()
        assertTrue(h.listening())
        // A second begin while the first still samples ends it (stale REC never torn down).
        h.begin(spec(captureId = 2))
        h.idle()
        assertFalse("the stale take's listener is gone", shadowOf(sensorManager).hasListener(first as SensorEventListener))
        // Re-beginning the SAME capture id replaces its predecessor and discards its temp file.
        h.begin(spec(captureId = 2))
        h.idle()
        // Fill past MAX_AWAITING_VERDICT: the oldest unanswered take (1) is evicted and discarded.
        for (id in 3..GyroLogRecorder.MAX_AWAITING_VERDICT + 1) h.begin(spec(captureId = id))
        h.idle()
        assertEquals(GyroLogRecorder.MAX_AWAITING_VERDICT, h.tempFiles().size)
        assertFalse(h.tempFiles().any { it.name.endsWith("-c1.bin") })
        h.recorder.complete(1, published = true) // evicted: no verdict owed any more
        h.idle()
        assertEquals(0, h.failures.get())
        assertTrue(media.rows.isEmpty())
    }

    @Test
    fun `release drops every waiting take quits the thread and refuses later takes`() {
        val h = Harness()
        h.begin(spec(captureId = 1))!!.onFirstEncodedFrame(FIRST_FRAME_NS)
        h.begin(spec(captureId = 2))
        h.idle()
        assertEquals(2, h.tempFiles().size)
        h.recorder.release()
        h.thread!!.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("quitSafely drained the delete task and ended the thread", h.thread!!.isAlive)
        assertEquals(0, h.tempFiles().size)
        assertTrue(h.finalizerShutdown())
        assertNull("a released recorder starts no take", h.begin(spec(captureId = 3)))
        assertFalse(h.recorder.wantsRollingShutterSkew())
        h.recorder.release() // idempotent
        h.recorder.endSampling(1) // nothing left to end
        h.recorder.complete(1, published = true)
        assertEquals(0, h.failures.get())

        val neverStarted = Harness()
        neverStarted.recorder.release() // no thread was ever created
        assertNull(neverStarted.thread)
    }

    @Test
    fun `the first recorder sweeps temp files a previous process left behind`() {
        val dir = File(context.cacheDir, GyroLogRecorder.TEMP_DIR).apply { mkdirs() }
        val ownPrefix = "p${android.os.Process.myPid()}-"
        val stale = File(dir, "p${android.os.Process.myPid() + 1}-i1-c1.bin").apply { writeText("old") }
        val own = File(dir, "${ownPrefix}i999-c1.bin").apply { writeText("mine") }
        val latch = AtomicBoolean(false)
        val h = Harness(staleSweepDone = latch)
        h.begin(spec(captureId = 1))
        h.idle()
        assertTrue(latch.get())
        assertFalse("another pid's file can never be claimed", stale.exists())
        assertTrue("this process's files are left to their owners", own.exists())

        // The latch is process-wide: a later recorder does not sweep again.
        val again = File(dir, "p${android.os.Process.myPid() + 2}-i1-c1.bin").apply { writeText("old") }
        val second = Harness(staleSweepDone = latch)
        second.begin(spec(captureId = 2))
        second.idle()
        assertTrue(again.exists())
    }

    @Test
    fun `the production constructor samples on its own thread and publishes off it`() {
        shadowOf(sensorManager).addSensor(gyro)
        shadowOf(sensorManager).addSensor(accel)
        val failures = AtomicInteger()
        val recorder = GyroLogRecorder(context) { failures.incrementAndGet() }
        val sink = recorder.begin(spec(captureId = 21))!!
        sink.onFirstEncodedFrame(FIRST_FRAME_NS)
        awaitTrue { shadowOf(sensorManager).hasListener(sink as SensorEventListener) }
        val sensorThread = Thread.getAllStackTraces().keys.single { it.name == "gyro-log" && it.isAlive } as HandlerThread
        Handler(sensorThread.looper).post {
            shadowOf(sensorManager).sendSensorEventToListeners(event(accel, FIRST_FRAME_NS, 0f, 1f, 0f))
            shadowOf(sensorManager).sendSensorEventToListeners(event(gyro, FIRST_FRAME_NS, 1f, 0f, 0f))
        }
        recorder.complete(21, published = true)
        awaitTrue { media.rows.singleOrNull()?.updates?.isNotEmpty() == true }
        assertTrue(media.rows.single().file.readText().endsWith("\n0,1.000000,0.000000,0.000000,0.000000,1.000000,0.000000\n"))
        recorder.release()
        sensorThread.join(TimeUnit.SECONDS.toMillis(5))
        assertEquals(0, failures.get())
    }

    // ---------------------------------------------------------------------------------------

    private inner class Harness(
        sensors: Boolean = true,
        openTempStream: (File) -> OutputStream = ::FileOutputStream,
        maxTempBytes: Long = GyroLogRecorder.MAX_TEMP_BYTES,
        private val finalizer: ExecutorService = InlineExecutor(),
        staleSweepDone: AtomicBoolean = AtomicBoolean(true),
        onFailed: () -> Unit = {},
    ) {
        val failures = AtomicInteger()
        var thread: HandlerThread? = null
        val recorder: GyroLogRecorder

        init {
            if (sensors) {
                shadowOf(sensorManager).addSensor(gyro)
                shadowOf(sensorManager).addSensor(accel)
            }
            recorder = GyroLogRecorder(
                context = context,
                onSidecarFailed = {
                    failures.incrementAndGet()
                    onFailed()
                },
                sensorThreadFactory = { HandlerThread("gyro-log-test").also { thread = it } },
                finalizerFactory = { finalizer },
                openTempStream = openTempStream,
                maxTempBytes = maxTempBytes,
                staleSweepDone = staleSweepDone,
            )
        }

        fun begin(spec: GyroLogRecorder.Spec): GyroLogFirstFrameSink? = recorder.begin(spec).also { idle() }

        fun idle() {
            val looper = thread?.looper ?: return
            if (thread?.isAlive == true) shadowOf(looper).idle()
        }

        fun post(block: () -> Unit) {
            Handler(requireNotNull(thread).looper).post(block)
            idle()
        }

        fun send(sensor: Sensor, t: Long, x: Float, y: Float, z: Float) =
            shadowOf(sensorManager).sendSensorEventToListeners(event(sensor, t, x, y, z))

        fun gyro(t: Long, x: Float, y: Float, z: Float) = post { send(gyro, t, x, y, z) }

        fun accel(t: Long, x: Float, y: Float, z: Float) = post { send(accel, t, x, y, z) }

        fun listening(): Boolean = shadowOf(sensorManager).listeners.isNotEmpty()

        fun tempFiles(): List<File> =
            File(context.cacheDir, GyroLogRecorder.TEMP_DIR).listFiles()?.filter { it.isFile }.orEmpty()

        fun finalizerShutdown(): Boolean = finalizer.isShutdown
    }

    private fun event(sensor: Sensor, t: Long, x: Float, y: Float, z: Float) =
        SensorEventBuilder.newBuilder(sensor, floatArrayOf(x, y, z)).setTimestamp(t).build()

    private fun spec(
        captureId: Int,
        readoutDirection: Int? = null,
        cameraTimestampRealtime: Boolean = true,
        realtimeMinusMonotonicNs: Long = 0L,
    ) = GyroLogRecorder.Spec(
        captureId = captureId,
        videoDisplayName = "VID_$captureId.mp4",
        orientation = "XyZ",
        readoutDirection = readoutDirection,
        cameraTimestampRealtime = cameraTimestampRealtime,
        realtimeMinusMonotonicNs = realtimeMinusMonotonicNs,
        unixStartSeconds = 1_700_000_000L,
        note = "note",
        vendor = "vendor",
    )

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not reached in 5 s" }
            Thread.sleep(5)
        }
    }

    /** Runs each task on the caller (the sensor thread); refuses once shut down. */
    private class InlineExecutor : AbstractExecutorService() {
        @Volatile private var shut = false
        override fun execute(command: Runnable) {
            if (shut) throw RejectedExecutionException("shut down")
            command.run()
        }
        override fun shutdown() {
            shut = true
        }
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shut = true }
        override fun isShutdown(): Boolean = shut
        override fun isTerminated(): Boolean = shut
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shut
    }

    private class FailingStream(
        private val delegate: OutputStream,
        private val failWrite: Boolean = false,
        private val failClose: Boolean = false,
    ) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failWrite) throw IOException("disk full")
            delegate.write(b, off, len)
        }
        override fun close() {
            delegate.close()
            if (failClose || failWrite) throw IOException("close failed")
        }
    }

    /** The `media` authority: one file per inserted row, with injectable provider failures. */
    private class FakeMediaProvider(private val dir: File) : ContentProvider() {
        enum class Failure { INSERT_THROWS, INSERT_NULL, OPEN_THROWS, UPDATE_MISSES, UPDATE_THROWS }

        class Row(val inserted: ContentValues, val file: File) {
            val updates = mutableListOf<ContentValues>()
        }

        var failure: Failure? = null
        val rows = mutableListOf<Row>()
        val deleted = mutableListOf<Uri>()

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            when (failure) {
                Failure.INSERT_THROWS -> throw IllegalStateException("provider down")
                Failure.INSERT_NULL -> return null
                else -> Unit
            }
            rows += Row(ContentValues(values), File(dir, "row-${rows.size + 1}"))
            return Uri.withAppendedPath(uri, rows.size.toString())
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failure == Failure.OPEN_THROWS) throw java.io.FileNotFoundException("no row file")
            return ParcelFileDescriptor.open(
                row(uri).file,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (failure == Failure.UPDATE_THROWS) throw IllegalStateException("update failed")
            row(uri).updates += ContentValues(values)
            return if (failure == Failure.UPDATE_MISSES) 0 else 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted += uri
            return 1
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        private fun row(uri: Uri): Row = rows[requireNotNull(uri.lastPathSegment).toInt() - 1]
    }

    private companion object {
        const val FIRST_FRAME_NS = 10_000_000_000L
        const val SKEW_NS = 10_000_000L
        const val ROWS_PAST_FLUSH = 200 // > 7 KiB of rows forces at least one mid-file flush
        const val RECORDS_PAST_BUFFER = 64 * 1024 / 32 + 8
    }
}
