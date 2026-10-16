package me.hletrd.telecampro.video

import java.util.Locale
import me.hletrd.telecampro.camera.CameraRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the pure Gyroflow .gcsv rules: axes, readout geometry, clock domain, and text format. */
class GyroflowLogTest {

    // --- orientation ------------------------------------------------------------------------

    @Test
    fun `rear route maps device axes to camera X-right Y-down Z-forward`() {
        assertEquals("Xyz", GyroflowLog.orientation(CameraRoute.BACK, fileMirroredX = false))
    }

    @Test
    fun `front true-scene file maps to xyZ`() {
        assertEquals("xyZ", GyroflowLog.orientation(CameraRoute.FRONT, fileMirroredX = false))
    }

    @Test
    fun `a mirrored file would flip image X only`() {
        assertEquals("xyz", GyroflowLog.orientation(CameraRoute.BACK, fileMirroredX = true))
        assertEquals("XyZ", GyroflowLog.orientation(CameraRoute.FRONT, fileMirroredX = true))
    }

    @Test
    fun `external camera has no IMU relationship and gets no log`() {
        assertNull(GyroflowLog.orientation(CameraRoute.EXTERNAL, fileMirroredX = false))
    }

    /** Applies telemetry-parser's orient(): component n = axis named by char n, lowercase negates. */
    private fun orient(spec: String, x: Double, y: Double, z: Double): DoubleArray =
        DoubleArray(3) { n ->
            val c = spec[n]
            val v = when (c.lowercaseChar()) { 'x' -> x; 'y' -> y; else -> z }
            if (c.isLowerCase()) -v else v
        }

    @Test
    fun `rear mapping is a proper rotation that sends device up to image up and back to forward`() {
        // Device +Y (top of phone) is image UP, i.e. camera -Y.
        assertTrue(orient("Xyz", 0.0, 1.0, 0.0).contentEquals(doubleArrayOf(0.0, -1.0, -0.0)))
        // Device -Z (rear lens direction) is camera +Z (forward).
        assertTrue(orient("Xyz", 0.0, 0.0, -1.0).contentEquals(doubleArrayOf(0.0, -0.0, 1.0)))
        // Right-handed: determinant of the axis map is +1 for both shipping routes.
        for (spec in listOf("Xyz", "xyZ")) {
            val sign = spec.count { it.isLowerCase() } % 2
            assertEquals("$spec must be a rotation, not a reflection", 0, sign)
        }
    }

    @Test
    fun `encoded files keep the true scene under both front conventions`() {
        assertFalse(GyroflowLog.encodedFileMirroredX(frontRoute = true, streamPreMirrored = true))
        assertFalse(GyroflowLog.encodedFileMirroredX(frontRoute = true, streamPreMirrored = false))
        assertFalse(GyroflowLog.encodedFileMirroredX(frontRoute = false, streamPreMirrored = true))
        assertFalse(GyroflowLog.encodedFileMirroredX(frontRoute = false, streamPreMirrored = false))
    }

    // --- readout direction --------------------------------------------------------------------

    @Test
    fun `readout direction follows the CW rotation of the sensor top edge`() {
        assertEquals(GyroflowLog.READOUT_TOP_TO_BOTTOM, GyroflowLog.readoutDirection(0, 0, false))
        assertEquals(GyroflowLog.READOUT_RIGHT_TO_LEFT, GyroflowLog.readoutDirection(90, 0, false))
        assertEquals(GyroflowLog.READOUT_BOTTOM_TO_TOP, GyroflowLog.readoutDirection(180, 0, false))
        assertEquals(GyroflowLog.READOUT_LEFT_TO_RIGHT, GyroflowLog.readoutDirection(270, 0, false))
    }

    @Test
    fun `PMA110 routes - rear bare, TELE afocal, front`() {
        assertEquals(GyroflowLog.READOUT_RIGHT_TO_LEFT, GyroflowLog.readoutDirection(90, 0, false))
        assertEquals(GyroflowLog.READOUT_LEFT_TO_RIGHT, GyroflowLog.readoutDirection(90, 180, false))
        assertEquals(GyroflowLog.READOUT_LEFT_TO_RIGHT, GyroflowLog.readoutDirection(270, 0, false))
    }

    @Test
    fun `mirroring swaps only the horizontal readouts`() {
        assertEquals(GyroflowLog.READOUT_LEFT_TO_RIGHT, GyroflowLog.readoutDirection(90, 0, true))
        assertEquals(GyroflowLog.READOUT_RIGHT_TO_LEFT, GyroflowLog.readoutDirection(270, 0, true))
        assertEquals(GyroflowLog.READOUT_TOP_TO_BOTTOM, GyroflowLog.readoutDirection(0, 0, true))
        assertEquals(GyroflowLog.READOUT_BOTTOM_TO_TOP, GyroflowLog.readoutDirection(0, 180, true))
    }

    // --- clock domain -------------------------------------------------------------------------

    @Test
    fun `REALTIME camera clock takes sensor events unchanged`() {
        assertEquals(5_000_000_000L, GyroflowLog.sensorEventToCameraClockNs(5_000_000_000L, true, 777L))
    }

    @Test
    fun `monotonic camera clock subtracts the sampled sleep offset`() {
        // elapsedRealtime runs ahead of nanoTime by accumulated deep sleep.
        val offset = 3_600_000_000_000L
        val event = 7_200_000_000_000L
        assertEquals(event - offset, GyroflowLog.sensorEventToCameraClockNs(event, false, offset))
    }

    @Test
    fun `relative micros keep a half-second pre-roll and drop earlier samples`() {
        val first = 10_000_000_000L
        assertEquals(0L, GyroflowLog.relativeMicros(first, first))
        assertEquals(1_234L, GyroflowLog.relativeMicros(first + 1_234_567L, first))
        assertEquals(-500_000L, GyroflowLog.relativeMicros(first - GyroflowLog.PRE_ROLL_NS, first))
        assertNull(GyroflowLog.relativeMicros(first - GyroflowLog.PRE_ROLL_NS - 1L, first))
    }

    // --- formatting ---------------------------------------------------------------------------

    @Test
    fun `fixed decimals are locale independent and signed`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val sb = StringBuilder()
            GyroflowLog.appendFixed(sb, -0.0123456789, 6)
            sb.append('|')
            GyroflowLog.appendFixed(sb, 9.80665, 6)
            sb.append('|')
            GyroflowLog.appendFixed(sb, -0.0000001, 6)
            sb.append('|')
            GyroflowLog.appendFixed(sb, Double.NaN, 3)
            sb.append('|')
            GyroflowLog.appendFixed(sb, 12.5, 0)
            assertEquals("-0.012346|9.806650|0.000000|0.000|13", sb.toString())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `row is integer microseconds then six fixed-point values`() {
        val sb = StringBuilder()
        GyroflowLog.appendRow(sb, -42L, 0.1f, -0.2f, 0f, 9.80665f, 0f, -1.5f)
        assertEquals("-42,0.100000,-0.200000,0.000000,9.806650,0.000000,-1.500000\n", sb.toString())
    }

    private fun header(readoutNs: Long?, direction: Int?) = GyroflowLog.header(
        GyroflowLog.Header(
            orientation = "Xyz",
            note = "1.0.2",
            vendor = "OPPO",
            videoFileName = "VID_TELECAM_1.mp4",
            unixStartSeconds = 1_760_000_000L,
            frameReadoutTimeNs = readoutNs,
            frameReadoutDirection = direction,
        ),
    )

    @Test
    fun `header carries every v1_3 field in order with readout lines as a pair`() {
        assertEquals(
            "GYROFLOW IMU LOG\n" +
                "version,1.3\n" +
                "id,telecampro\n" +
                "orientation,Xyz\n" +
                "note,1.0.2\n" +
                "vendor,OPPO\n" +
                "videofilename,VID_TELECAM_1.mp4\n" +
                "timestamp,1760000000\n" +
                "frame_readout_time,16.667\n" +
                "frame_readout_direction,3\n" +
                "tscale,0.000001\n" +
                "gscale,1.0\n" +
                "ascale,0.101971621\n" +
                "t,gx,gy,gz,ax,ay,az\n",
            header(16_666_667L, 3),
        )
    }

    @Test
    fun `readout lines are omitted unless both skew and direction are known`() {
        assertFalse(header(null, 3).contains("frame_readout"))
        assertFalse(header(16_666_667L, null).contains("frame_readout"))
        assertFalse(header(0L, 3).contains("frame_readout"))
    }

    @Test
    fun `free-text header values stay one CSV cell`() {
        val h = GyroflowLog.header(
            GyroflowLog.Header("Xyz", "a,b\nc", "V", "x.mp4", 0L, null, null),
        )
        assertTrue(h.contains("note,a b c\n"))
    }

    @Test
    fun `sidecar name swaps the video extension`() {
        assertEquals("VID_TELECAM_20261014.gcsv", GyroflowLog.sidecarDisplayName("VID_TELECAM_20261014.mp4"))
        assertEquals("clip.gcsv", GyroflowLog.sidecarDisplayName("clip"))
    }
}
