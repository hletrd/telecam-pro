package me.hletrd.telecampro.video

import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import me.hletrd.telecampro.camera.DiagnosticLog

/** Camera result seam: the controller reads SENSOR_ROLLING_SHUTTER_SKEW only while this wants it. */
interface RollingShutterSkewSink {
    /** Cheap volatile read; false keeps the per-frame result callback from touching the key. */
    fun wantsRollingShutterSkew(): Boolean
    fun offerRollingShutterSkew(skewNs: Long)
}

/** GL-thread seam: the timestamp the encoder PTS is rebased from (the first recorded frame). */
fun interface GyroLogFirstFrameSink {
    fun onFirstEncodedFrame(timestampNs: Long)
}

/**
 * Optional Gyroflow `.gcsv` sidecar for each video take ([GyroflowLog] holds the pure rules).
 *
 * Lifecycle, keyed by the take's capture id:
 *  1. [begin] at REC admission registers TYPE_GYROSCOPE + TYPE_ACCELEROMETER on a DEDICATED
 *     HandlerThread (never main, never GL/encoder/recorder threads) and streams fixed-size binary
 *     records (camera-clock ns + 6 floats = 32 B) to a private temp file in cacheDir — bounded
 *     memory however long the take (30 min at 200 Hz ≈ 11.5 MB on disk).
 *  2. The GL thread reports the first encoded frame's SurfaceTexture timestamp through the returned
 *     [GyroLogFirstFrameSink]; times are kept absolute until then, so it may arrive at any point.
 *  3. [endSampling] on EVERY recorder teardown path unregisters the sensors and closes the file.
 *  4. [complete] receives the storage verdict. Only a PUBLISHED clip converts the temp file into a
 *     MediaStore row (Documents/TeleCamPro/<video stem>.gcsv); every other outcome deletes it. A
 *     sidecar failure never touches the published video: it surfaces once via [onSidecarFailed].
 *
 * Sampling period is a fixed 5 000 µs (200 Hz). Apps targeting API 31+ WITHOUT
 * HIGH_SAMPLING_RATE_SENSORS (not declared, deliberately) are capped at 200 Hz for registerListener,
 * so asking for SENSOR_DELAY_FASTEST would only buy an OEM-dependent rate; 200 Hz is the honest
 * ceiling and is what Gyroflow's own phone loggers use.
 *
 * Rows are driven by gyro events and pair each with the most recent accelerometer sample; gyro
 * events before the first accelerometer event (a few ms after registration) are dropped rather than
 * written with an invented zero gravity vector.
 *
 * No per-sample logging (ColorOS 300-row quota): only terminal failures reach the reserved
 * [DiagnosticLog] door.
 */
class GyroLogRecorder(
    context: Context,
    private val onSidecarFailed: () -> Unit,
) : RollingShutterSkewSink {

    /** Everything fixed at REC admission. */
    data class Spec(
        val captureId: Int,
        val videoDisplayName: String,
        val orientation: String,
        val readoutDirection: Int?,
        val cameraTimestampRealtime: Boolean,
        val realtimeMinusMonotonicNs: Long,
        val unixStartSeconds: Long,
        val note: String,
        val vendor: String,
    )

    private val appContext = context.applicationContext
    private val sensorManager: SensorManager? = appContext.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val tempDir = File(appContext.cacheDir, TEMP_DIR)
    private val instance = INSTANCE_SEQUENCE.incrementAndGet()

    private val lock = Any()
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var finalizer: ExecutorService? = null
    // Takes awaiting (or holding) a storage verdict, oldest first. Bounded: a take whose verdict
    // never arrives (recorder quarantine, engine teardown mid-tail) is evicted by later takes.
    private val sessions = LinkedHashMap<Int, Session>()
    private var released = false

    @Volatile private var sampling: Session? = null

    private inner class Session(val spec: Spec, val file: File?) : SensorEventListener, GyroLogFirstFrameSink {
        val firstFrameNs = AtomicLong(0L)
        val skewNs = AtomicLong(0L)
        // Sensor-thread state.
        var out: DataOutputStream? = null
        var hasAccel = false
        var ax = 0f
        var ay = 0f
        var az = 0f
        var bytes = 0L
        var rows = 0L
        // Written on the sensor thread, read by the finalizer after the end task (happens-before
        // through the executor hand-off).
        @Volatile var writeFailed = file == null
        val ended = AtomicBoolean(false)

        override fun onFirstEncodedFrame(timestampNs: Long) {
            if (timestampNs > 0L) firstFrameNs.compareAndSet(0L, timestampNs)
        }

        override fun onSensorChanged(event: SensorEvent) {
            val stream = out ?: return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    ax = event.values[0]
                    ay = event.values[1]
                    az = event.values[2]
                    hasAccel = true
                }
                Sensor.TYPE_GYROSCOPE -> {
                    if (!hasAccel) return
                    if (bytes + RECORD_BYTES > MAX_TEMP_BYTES) return // truncated, still valid
                    val t = GyroflowLog.sensorEventToCameraClockNs(
                        event.timestamp,
                        spec.cameraTimestampRealtime,
                        spec.realtimeMinusMonotonicNs,
                    )
                    try {
                        stream.writeLong(t)
                        stream.writeFloat(event.values[0])
                        stream.writeFloat(event.values[1])
                        stream.writeFloat(event.values[2])
                        stream.writeFloat(ax)
                        stream.writeFloat(ay)
                        stream.writeFloat(az)
                        bytes += RECORD_BYTES
                        rows++
                    } catch (failure: java.io.IOException) {
                        writeFailed = true
                        closeQuietly()
                        DiagnosticLog.w(TAG, "gyro temp write failed", failure)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        /** Sensor thread only. */
        fun closeQuietly() {
            val stream = out ?: return
            out = null
            try {
                stream.close()
            } catch (failure: java.io.IOException) {
                writeFailed = true
            }
        }
    }

    override fun wantsRollingShutterSkew(): Boolean = sampling?.skewNs?.get() == 0L

    override fun offerRollingShutterSkew(skewNs: Long) {
        if (skewNs > 0L) sampling?.skewNs?.compareAndSet(0L, skewNs)
    }

    /**
     * Starts sampling for [spec]'s take. Returns the first-frame sink the GL encoder output must
     * feed, or null when the recorder is released. A device without a gyroscope still gets a session
     * (marked failed) so a published clip truthfully reports "Gyro log not saved".
     */
    fun begin(spec: Spec): GyroLogFirstFrameSink? {
        val evicted = ArrayList<Session>()
        val session: Session
        val handler: Handler
        synchronized(lock) {
            if (released) return null
            sampling?.let { stale -> endLocked(stale) }
            sessions.remove(spec.captureId)?.let(evicted::add)
            while (sessions.size >= MAX_AWAITING_VERDICT) {
                val oldest = sessions.keys.first()
                sessions.remove(oldest)?.let(evicted::add)
            }
            handler = ensureThreadsLocked()
            val file = if (gyroscope != null && accelerometer != null) {
                File(tempDir, "p${android.os.Process.myPid()}-i$instance-c${spec.captureId}.bin")
            } else {
                null
            }
            session = Session(spec, file)
            sessions[spec.captureId] = session
            sampling = session
        }
        evicted.forEach { discard(it, handler) }
        handler.post {
            val file = session.file ?: return@post
            if (session.ended.get()) return@post
            try {
                tempDir.mkdirs()
                session.out = DataOutputStream(BufferedOutputStream(FileOutputStream(file), 64 * 1024))
            } catch (failure: java.io.IOException) {
                session.writeFailed = true
                DiagnosticLog.w(TAG, "gyro temp open failed", failure)
                return@post
            }
            val manager = sensorManager ?: return@post
            val gyroOk = manager.registerListener(session, gyroscope, SAMPLING_PERIOD_US, 0, handler)
            val accelOk = manager.registerListener(session, accelerometer, SAMPLING_PERIOD_US, 0, handler)
            if (!gyroOk || !accelOk) {
                session.writeFailed = true
                manager.unregisterListener(session)
                session.closeQuietly()
                DiagnosticLog.w(TAG, "gyro sensor registration refused (gyro=$gyroOk accel=$accelOk)")
            }
        }
        return session
    }

    /** Stops sampling for [captureId] (idempotent). Called on every recorder teardown path. */
    fun endSampling(captureId: Int) {
        synchronized(lock) {
            val session = sessions[captureId] ?: return
            endLocked(session)
        }
    }

    private fun endLocked(session: Session) {
        if (sampling === session) sampling = null
        if (!session.ended.compareAndSet(false, true)) return
        val handler = sensorHandler ?: return
        handler.post {
            sensorManager?.unregisterListener(session)
            session.closeQuietly()
        }
    }

    /**
     * The storage verdict for [captureId]. [published] = the clip reached its public MediaStore
     * state; anything else (failed, retained for launch recovery, unvalidated) drops the log, since
     * the sidecar must never outlive or precede the clip it describes.
     */
    fun complete(captureId: Int, published: Boolean) {
        val session: Session
        val handler: Handler
        val executor: ExecutorService
        synchronized(lock) {
            session = sessions.remove(captureId) ?: return
            endLocked(session)
            handler = sensorHandler ?: return
            executor = finalizer ?: return
        }
        // Queue behind the end task on the sensor thread so the temp file is closed first, then
        // move the provider work to the finalizer so a long take never stalls the next take's
        // sensor stream.
        handler.post {
            val accepted = runCatching {
                executor.execute {
                    val saved = published && publish(session)
                    session.file?.delete()
                    if (published && !saved) runCatching(onSidecarFailed)
                }
            }.isSuccess
            if (!accepted) {
                session.file?.delete()
                if (published) runCatching(onSidecarFailed)
            }
        }
    }

    /** Engine teardown: stops sampling and drops every take still awaiting a verdict. */
    fun release() {
        val dropped: List<Session>
        val thread: HandlerThread?
        val handler: Handler?
        val executor: ExecutorService?
        synchronized(lock) {
            if (released) return
            released = true
            dropped = sessions.values.toList()
            sessions.clear()
            dropped.forEach(::endLocked)
            sampling = null
            thread = sensorThread
            handler = sensorHandler
            executor = finalizer
        }
        handler?.post { dropped.forEach { it.file?.delete() } }
        thread?.quitSafely()
        // In-flight publications finish (they only touch their own row and temp file).
        executor?.shutdown()
    }

    private fun discard(session: Session, handler: Handler) {
        handler.post {
            sensorManager?.unregisterListener(session)
            session.closeQuietly()
            session.file?.delete()
        }
    }

    private fun ensureThreadsLocked(): Handler {
        sensorHandler?.let { return it }
        val thread = HandlerThread("gyro-log").also { it.start() }
        val handler = Handler(thread.looper)
        sensorThread = thread
        sensorHandler = handler
        finalizer = Executors.newSingleThreadExecutor { task ->
            Thread(task, "gyro-log-publish").apply { isDaemon = true }
        }
        if (STALE_SWEEP_DONE.compareAndSet(false, true)) {
            // A previous PROCESS's temp files (killed mid-take or mid-verdict) can never be claimed:
            // sweep anything not carrying this pid's prefix. Off the caller's thread.
            val ownPrefix = "p${android.os.Process.myPid()}-"
            handler.post {
                tempDir.listFiles()?.forEach { if (!it.name.startsWith(ownPrefix)) it.delete() }
            }
        }
        return handler
    }

    /** Finalizer thread. True only when a complete, non-pending row exists. */
    private fun publish(session: Session): Boolean {
        val file = session.file
        val firstFrame = session.firstFrameNs.get()
        if (file == null || session.writeFailed || firstFrame == 0L || session.rows == 0L) {
            DiagnosticLog.w(
                TAG,
                "gyro log not written: file=${file != null} writeFailed=${session.writeFailed} " +
                    "firstFrame=${firstFrame != 0L} rows=${session.rows}",
            )
            return false
        }
        val spec = session.spec
        val header = GyroflowLog.header(
            GyroflowLog.Header(
                orientation = spec.orientation,
                note = spec.note,
                vendor = spec.vendor,
                videoFileName = spec.videoDisplayName,
                unixStartSeconds = spec.unixStartSeconds,
                frameReadoutTimeNs = session.skewNs.get().takeIf { it > 0L && spec.readoutDirection != null },
                frameReadoutDirection = spec.readoutDirection,
            ),
        )
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, GyroflowLog.sidecarDisplayName(spec.videoDisplayName))
            // NOT text/csv: MediaProvider forces the display-name extension to match a typed MIME
            // (FileUtils.splitFileName), so text/csv lands as "X.gcsv.csv" and Gyroflow's
            // same-stem lookup misses it. application/octet-stream keeps an unknown extension
            // verbatim, and Documents/ accepts any non-media type.
            put(MediaStore.MediaColumns.MIME_TYPE, SIDECAR_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, SIDECAR_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (failure: RuntimeException) {
            DiagnosticLog.w(TAG, "gyro log insert failed", failure)
            null
        } ?: return false
        val written = try {
            val stream = checkNotNull(resolver.openOutputStream(uri, "w")) { "no output stream" }
            OutputStreamWriter(BufferedOutputStream(stream, 64 * 1024), Charsets.UTF_8).use { writer ->
                writer.write(header)
                writeRows(file, firstFrame, writer)
            }
            val cleared = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, cleared, null, null) == 1
        } catch (failure: Exception) {
            DiagnosticLog.w(TAG, "gyro log write failed", failure)
            false
        }
        if (!written) runCatching { resolver.delete(uri, null, null) }
        return written
    }

    private fun writeRows(file: File, firstFrameNs: Long, writer: OutputStreamWriter) {
        val line = StringBuilder(8 * 1024)
        DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { input ->
            while (true) {
                val t = try {
                    input.readLong()
                } catch (endOfRecords: EOFException) {
                    break
                }
                val gx = input.readFloat()
                val gy = input.readFloat()
                val gz = input.readFloat()
                val ax = input.readFloat()
                val ay = input.readFloat()
                val az = input.readFloat()
                val tUs = GyroflowLog.relativeMicros(t, firstFrameNs) ?: continue
                GyroflowLog.appendRow(line, tUs, gx, gy, gz, ax, ay, az)
                if (line.length >= 7 * 1024) {
                    writer.append(line)
                    line.setLength(0)
                }
            }
        }
        writer.append(line)
    }

    companion object {
        private const val TAG = "GyroLog"
        private const val TEMP_DIR = "gyrolog"
        private const val SAMPLING_PERIOD_US = 5_000
        private const val RECORD_BYTES = 8L + 6L * 4L
        // ~3 h at 200 Hz; past it sampling keeps the file it has (a truncated log is still valid).
        private const val MAX_TEMP_BYTES = 72L * 1024L * 1024L
        private const val MAX_AWAITING_VERDICT = 4
        internal const val SIDECAR_MIME = "application/octet-stream"
        internal val SIDECAR_RELATIVE_PATH = "${Environment.DIRECTORY_DOCUMENTS}/TeleCamPro/"
        private val INSTANCE_SEQUENCE = AtomicLong(0L)
        private val STALE_SWEEP_DONE = AtomicBoolean(false)
    }
}
