package me.hletrd.telecampro.video

import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.RotationMath
import me.hletrd.telecampro.gl.FrontMirrorConvention

/**
 * Pure half of the Gyroflow gyro-log sidecar (`.gcsv` v1.3,
 * https://docs.gyroflow.xyz/app/technical-details/gcsv-format): axis mapping, rolling-shutter
 * readout direction, clock-domain conversion, and text formatting. No Android framework calls, so
 * every rule here is JVM-unit-tested ([GyroLogRecorder] owns sensors, files, and MediaStore).
 *
 * The log describes the ENCODED FILE, not the preview: every geometric answer below is derived from
 * what the encoder draw writes (device-natural upright frame, afocal 180° baked in on TELE, front
 * route un-mirrored to the true scene), because that is the image Gyroflow will stabilize.
 */
object GyroflowLog {
    const val LOGGER_ID = "telecampro"
    const val SIDECAR_EXTENSION = ".gcsv"

    /**
     * Samples kept from BEFORE the first encoded frame. Gyroflow integrates angular rate, so a short
     * pre-roll lets its smoothing and sync search start from settled data instead of the clip's first
     * row. Anything earlier (sensor registration happens at REC admission, which can precede the
     * first real encoder swap by the whole native setup) is noise for the clip and is dropped.
     */
    const val PRE_ROLL_NS = 500_000_000L

    /** g per (m/s²): Android reports TYPE_ACCELEROMETER in m/s², Gyroflow's `ascale` yields g. */
    const val ACCEL_SCALE_TO_G = 1.0 / 9.80665

    /** Fixed-point decimals for every sample value (µrad/s and µm/s² resolution). */
    const val SAMPLE_DECIMALS = 6

    /**
     * telemetry-parser `orient()` string mapping Android SENSOR axes onto Gyroflow CAMERA axes, or
     * null when no fixed relationship exists (EXTERNAL: a USB camera has no attitude relative to the
     * handset's IMU, so a log would be confidently wrong).
     *
     * Convention: output component n is the input axis named by character n; lowercase negates it.
     * Gyroflow's camera frame is +X right in the image, +Y DOWN in the image, +Z forward along the
     * lens (right-handed). Android's sensor frame is the device NATURAL frame: +X right, +Y up, +Z
     * out of the screen toward the user.
     *
     * Derivation. The encoder buffer is device-fixed in the NATURAL orientation
     * ([RotationMath.encoderSurfaceSize]; holding the phone in landscape changes only the muxer
     * rotation hint, never the pixels), so the image axes are the natural axes:
     *  - BACK: image right = +X_dev, image down = −Y_dev, lens forward (away from the user) = −Z_dev
     *    → "Xyz".
     *  - FRONT, file un-mirrored (true scene, [FrontMirrorConvention.encoderDrawMirrorX]): the lens
     *    looks along +Z_dev and image down is still −Y_dev, so right-handedness forces image right =
     *    (−Y_dev) × (+Z_dev) = −X_dev → "xyZ".
     *  - A horizontally MIRRORED file flips image X only (a reflection, which is why the front route
     *    must know whether its file is the true scene): BACK "xyz", FRONT "XyZ". No shipping route
     *    produces one ([encodedFileMirroredX] is false on both conventions); the arm exists so a
     *    future convention cannot silently reuse the un-mirrored answer.
     *
     * The TELE afocal 180° does NOT change the mapping: the converter inverts the optical image and
     * the encoder draw rotates it back, so the corrected file is an ordinary upright view whose axes
     * move with the body exactly like the bare rear lens. Only the magnification differs, which
     * Gyroflow takes from the lens profile, not from the IMU orientation.
     */
    fun orientation(route: CameraRoute, fileMirroredX: Boolean): String? = when (route) {
        CameraRoute.BACK -> if (fileMirroredX) "xyz" else "Xyz"
        CameraRoute.FRONT -> if (fileMirroredX) "XyZ" else "xyZ"
        CameraRoute.EXTERNAL -> null
    }

    /**
     * Whether the ENCODED file is horizontally mirrored relative to the true scene: the stream's own
     * mirror (front + pre-mirrored HAL) XOR the encoder draw's un-mirror. Both conventions resolve
     * to false by construction (files always keep the true scene); pinned by a test.
     */
    fun encodedFileMirroredX(frontRoute: Boolean, streamPreMirrored: Boolean): Boolean {
        val streamMirrored = frontRoute && streamPreMirrored
        return streamMirrored != FrontMirrorConvention.encoderDrawMirrorX(frontRoute, streamPreMirrored)
    }

    /** gcsv `frame_readout_direction` codes. */
    const val READOUT_TOP_TO_BOTTOM = 0
    const val READOUT_BOTTOM_TO_TOP = 1
    const val READOUT_LEFT_TO_RIGHT = 2
    const val READOUT_RIGHT_TO_LEFT = 3

    /**
     * Direction in which sensor ROWS are read out, as seen in the FINAL ENCODED image.
     *
     * Camera2 defines SENSOR_ORIENTATION as the clockwise rotation that makes the sensor image
     * upright in the device's natural orientation, and states that rolling-shutter readout runs top
     * to bottom in the SENSOR's coordinate system. The encoded file is that sensor image rotated CW
     * by `sensorOrientation` (the SurfaceTexture transform; on a pre-mirrored front stream the
     * transform is flip∘rotate and the encoder's un-mirror cancels the flip, leaving the same pure
     * rotation) plus [contentRotationDeg] (the afocal 180° on TELE). Rotating an image CW carries its
     * top edge:
     *  -   0° → top     : rows read top→bottom (0)
     *  -  90° → right   : rows read right→left (3)   — PMA110 rear, bare lens
     *  - 180° → bottom  : rows read bottom→top (1)
     *  - 270° → left    : rows read left→right (2)   — PMA110 TELE (90 + 180), PMA110 front (270)
     * A horizontally mirrored file would swap left/right.
     *
     * NOTE (gcsv spec): Gyroflow documents support for directions 0 and 1 only. The value written is
     * the true geometry; whether Gyroflow honours 2/3 is its decision, not something to fake here.
     */
    fun readoutDirection(sensorOrientation: Int, contentRotationDeg: Int, fileMirroredX: Boolean): Int {
        val direction = when (RotationMath.normalize(sensorOrientation + contentRotationDeg)) {
            90 -> READOUT_RIGHT_TO_LEFT
            180 -> READOUT_BOTTOM_TO_TOP
            270 -> READOUT_LEFT_TO_RIGHT
            else -> READOUT_TOP_TO_BOTTOM
        }
        if (!fileMirroredX) return direction
        return when (direction) {
            READOUT_LEFT_TO_RIGHT -> READOUT_RIGHT_TO_LEFT
            READOUT_RIGHT_TO_LEFT -> READOUT_LEFT_TO_RIGHT
            else -> direction
        }
    }

    /**
     * A SensorEvent timestamp (always `SystemClock.elapsedRealtimeNanos`) expressed in the camera's
     * SENSOR_TIMESTAMP domain, which is the domain of the SurfaceTexture timestamps the encoder PTS
     * is rebased from. REALTIME sources share the sensor clock; UNKNOWN sources tick on
     * `System.nanoTime` (the house rule in `CameraController.sensorClockNowNs`), which differs from
     * elapsedRealtime by the accumulated deep-sleep time — [realtimeMinusMonotonicNs], sampled once
     * at REC start. It cannot drift mid-take: the screen is held on while recording, so the device
     * does not suspend.
     */
    fun sensorEventToCameraClockNs(
        sensorEventNs: Long,
        cameraTimestampRealtime: Boolean,
        realtimeMinusMonotonicNs: Long,
    ): Long = if (cameraTimestampRealtime) sensorEventNs else sensorEventNs - realtimeMinusMonotonicNs

    /**
     * Row time in whole microseconds relative to the first encoded frame (video PTS 0), or null when
     * the sample falls before the [PRE_ROLL_NS] window. Truncates toward zero.
     */
    fun relativeMicros(cameraClockNs: Long, firstFrameNs: Long): Long? {
        val deltaNs = cameraClockNs - firstFrameNs
        if (deltaNs < -PRE_ROLL_NS) return null
        return deltaNs / 1_000L
    }

    /** `VID_X.mp4` → `VID_X.gcsv`; a name without an extension just gains one. */
    fun sidecarDisplayName(videoDisplayName: String): String {
        val dot = videoDisplayName.lastIndexOf('.')
        val stem = if (dot > 0) videoDisplayName.substring(0, dot) else videoDisplayName
        return stem + SIDECAR_EXTENSION
    }

    /** Everything above the column-name line. Optional readout lines are written only as a PAIR. */
    data class Header(
        val orientation: String,
        val note: String,
        val vendor: String,
        val videoFileName: String,
        val unixStartSeconds: Long,
        val frameReadoutTimeNs: Long?,
        val frameReadoutDirection: Int?,
    )

    /** The complete header including the `t,gx,gy,gz,ax,ay,az` column line, '\n'-terminated. */
    fun header(h: Header): String = buildString {
        append("GYROFLOW IMU LOG\n")
        append("version,1.3\n")
        append("id,").append(LOGGER_ID).append('\n')
        append("orientation,").append(h.orientation).append('\n')
        append("note,").append(csvField(h.note)).append('\n')
        append("vendor,").append(csvField(h.vendor)).append('\n')
        append("videofilename,").append(csvField(h.videoFileName)).append('\n')
        append("timestamp,").append(h.unixStartSeconds).append('\n')
        if (h.frameReadoutTimeNs != null && h.frameReadoutTimeNs > 0L && h.frameReadoutDirection != null) {
            append("frame_readout_time,")
            appendFixed(this, h.frameReadoutTimeNs / 1_000_000.0, 3)
            append('\n')
            append("frame_readout_direction,").append(h.frameReadoutDirection).append('\n')
        }
        append("tscale,0.000001\n")
        append("gscale,1.0\n")
        append("ascale,")
        appendFixed(this, ACCEL_SCALE_TO_G, 9)
        append('\n')
        append("t,gx,gy,gz,ax,ay,az\n")
    }

    /** One data row: integer µs time, raw rad/s gyro, raw m/s² accel (scaled by `ascale`). */
    fun appendRow(
        out: StringBuilder,
        tUs: Long,
        gx: Float, gy: Float, gz: Float,
        ax: Float, ay: Float, az: Float,
    ) {
        out.append(tUs)
        appendCell(out, gx)
        appendCell(out, gy)
        appendCell(out, gz)
        appendCell(out, ax)
        appendCell(out, ay)
        appendCell(out, az)
        out.append('\n')
    }

    private fun appendCell(out: StringBuilder, value: Float) {
        out.append(',')
        appendFixed(out, value.toDouble(), SAMPLE_DECIMALS)
    }

    /**
     * Locale-independent fixed-point decimal ('.' separator always; `String.format` would follow the
     * default locale and write ',' on a Korean-or-European device, splitting every CSV cell). A
     * non-finite value writes zero rather than a token no parser accepts.
     */
    fun appendFixed(out: StringBuilder, value: Double, decimals: Int) {
        require(decimals in 0..9) { "decimals out of range: $decimals" }
        var scale = 1L
        repeat(decimals) { scale *= 10L }
        val magnitude = if (value.isFinite()) Math.round(kotlin.math.abs(value) * scale) else 0L
        if (value < 0.0 && magnitude != 0L) out.append('-')
        out.append(magnitude / scale)
        if (decimals == 0) return
        out.append('.')
        val fraction = (magnitude % scale).toString()
        repeat(decimals - fraction.length) { out.append('0') }
        out.append(fraction)
    }

    /** Header values are free text from the build/device; keep each one a single CSV cell. */
    private fun csvField(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').replace(',', ' ').trim()
}
