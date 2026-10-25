package me.hletrd.telecampro.stab

import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.rawEisConfigFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The RAW log gyro EIS math (RawEisMath.kt): orientation integration, the device→camera axis map, the
 * converter's sign and gain, the band homography, and the margin-bounded smoother.
 */
class RawEisMathTest {

    private val m = RawEisMath

    @Test
    fun `integrating a constant rate gives that rotation`() {
        // 0.5 rad/s about camera y for 1 s in 200 steps.
        var q = RawEisMath.Quat.IDENTITY
        repeat(200) { q = (q * RawEisMath.Quat.fromAngularVelocity(0.0, 0.5, 0.0, 0.005)).normalized() }
        assertEquals(0.5, m.angleBetween(RawEisMath.Quat.IDENTITY, q), 1e-9)
        val r = q.toMatrix()
        // Rotation about y: x' = cos·x + sin·z.
        assertEquals(kotlin.math.cos(0.5), r[0], 1e-9)
        assertEquals(kotlin.math.sin(0.5), r[2], 1e-9)
    }

    @Test
    fun `slerp lands on both ends and halfway`() {
        val a = RawEisMath.Quat.IDENTITY
        val b = RawEisMath.Quat.fromAngularVelocity(0.0, 0.0, 1.0, 0.4)
        assertEquals(0.0, m.angleBetween(a, m.slerp(a, b, 0.0)), 1e-9)
        assertEquals(0.0, m.angleBetween(b, m.slerp(a, b, 1.0)), 1e-9)
        assertEquals(0.2, m.angleBetween(a, m.slerp(a, b, 0.5)), 1e-9)
    }

    @Test
    fun `slerp takes the short way and stays exact for tiny steps`() {
        val a = RawEisMath.Quat.IDENTITY
        val b = RawEisMath.Quat.fromAngularVelocity(0.3, 0.0, 0.0, 1.0)
        // -b is the same rotation; interpolating toward it must not go the long way round.
        val flipped = RawEisMath.Quat(-b.w, -b.x, -b.y, -b.z)
        assertEquals(0.15, m.angleBetween(a, m.slerp(a, flipped, 0.5)), 1e-9)
        // A 1 µrad step takes the normalized-lerp branch and still lands on the right angle.
        val tiny = RawEisMath.Quat.fromAngularVelocity(1e-6, 0.0, 0.0, 1.0)
        assertEquals(0.5e-6, m.angleBetween(a, m.slerp(a, tiny, 0.5)), 1e-9)
    }

    @Test
    fun `a rect knows its centre`() {
        val r = RawEisMath.Rect(10.0, 20.0, 100.0, 50.0)
        assertEquals(60.0, r.centerX, 0.0)
        assertEquals(45.0, r.centerY, 0.0)
    }

    @Test
    fun `rear orientation 90 maps device axes as the displayed image requires`() {
        val c = m.rearDeviceToCamera(90)
        // x_cam = -y_dev, y_cam = -x_dev, z_cam = -z_dev.
        val expected = doubleArrayOf(0.0, -1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, -1.0)
        for (i in 0 until 9) assertEquals("[$i]", expected[i], c[i], 1e-12)
        // A proper rotation (det = +1), so integrated orientations stay rotations.
        val det = c[0] * (c[4] * c[8] - c[5] * c[7]) - c[1] * (c[3] * c[8] - c[5] * c[6]) + c[2] * (c[3] * c[7] - c[4] * c[6])
        assertEquals(1.0, det, 1e-12)
    }

    @Test
    fun `the inverting converter flips and magnifies the image motion`() {
        // PMA110 cam 4: 20.1 mm over a 6.5536 mm, 4096 px wide sensor.
        val bare = m.effectiveFocalPx(20.1, 6.5536, 4096, null)
        assertEquals(12562.5, bare, 0.5)
        val converted = m.effectiveFocalPx(20.1, 6.5536, 4096, 300.0 / 70.0)
        assertEquals(-bare * 300.0 / 70.0, converted, 1e-6)
        // A converter at or below 1× is no converter.
        assertEquals(bare, m.effectiveFocalPx(20.1, 6.5536, 4096, 1.0), 0.0)
    }

    private val array = 4096 to 3072
    private val developed = RawEisMath.Rect(0.0, 384.0, 4096.0, 2304.0)
    private val crop = RawEisMath.Rect(0.0, 384.0, 4096.0, 2304.0)

    @Test
    fun `with no rotation the band homography is the plain crop`() {
        val h = m.bandHomography(
            RawEisMath.Quat.IDENTITY, RawEisMath.Quat.IDENTITY, 12562.5, array.first, array.second, crop, developed,
        )
        for ((u, v) in listOf(0.0 to 0.0, 1.0 to 1.0, 0.5 to 0.25)) {
            val (tx, ty) = m.apply(h, u, v)
            assertEquals(u, tx, 1e-12)
            assertEquals(v, ty, 1e-12)
        }
    }

    @Test
    fun `a yaw shifts the sampled window by focal times angle, opposite under the converter`() {
        val window = RawEisMath.Rect(204.8, 499.2, 3686.4, 2073.6) // 10 % held back
        val theta = 0.002 // rad
        // The sensor turned by +theta about camera y (real), the virtual view did not.
        val real = RawEisMath.Quat.fromAngularVelocity(0.0, theta, 0.0, 1.0)
        val bare = 12562.5
        val hBare = m.bandHomography(real, RawEisMath.Quat.IDENTITY, bare, array.first, array.second, window, developed)
        val hTc = m.bandHomography(real, RawEisMath.Quat.IDENTITY, -bare * 300 / 70, array.first, array.second, window, developed)
        val (cx0, _) = m.apply(
            m.bandHomography(RawEisMath.Quat.IDENTITY, RawEisMath.Quat.IDENTITY, bare, array.first, array.second, window, developed),
            0.5, 0.5,
        )
        val shiftBare = (m.apply(hBare, 0.5, 0.5).first - cx0) * developed.width
        val shiftTc = (m.apply(hTc, 0.5, 0.5).first - cx0) * developed.width
        // Rᵀ rotates a ray about y by -theta: the scene point moves toward -x by f·theta px.
        assertEquals(-bare * theta, shiftBare, 0.05)
        // Through the inverting converter the same rotation moves it +M·f·theta.
        assertEquals(bare * 300 / 70 * theta, shiftTc, 0.2)
        assertTrue(shiftBare * shiftTc < 0)
    }

    @Test
    fun `the fit check accepts the centred window and rejects one pushed off the edge`() {
        val window = RawEisMath.Rect(204.8, 499.2, 3686.4, 2073.6)
        val still = m.bandHomography(
            RawEisMath.Quat.IDENTITY, RawEisMath.Quat.IDENTITY, 12562.5, array.first, array.second, window, developed,
        )
        assertTrue(m.windowFits(List(8) { still }, 1.5 / 4096))
        // 0.03 rad of yaw at 12.5k px focal is ~377 px: beyond the 204.8 px held back each side.
        val far = m.bandHomography(
            RawEisMath.Quat.fromAngularVelocity(0.0, 0.03, 0.0, 1.0), RawEisMath.Quat.IDENTITY, 12562.5,
            array.first, array.second, window, developed,
        )
        assertFalse(m.windowFits(List(8) { far }, 1.5 / 4096))
    }

    @Test
    fun `following speeds up only as the margin runs out`() {
        val base = m.baseFollow(strong = true)
        assertEquals(base, m.followFor(base, 0.0), 0.0)
        assertEquals(base, m.followFor(base, 0.6), 1e-12)
        assertTrue(m.followFor(base, 0.8) > base)
        assertTrue(m.followFor(base, 1.0) > m.followFor(base, 0.8))
        assertTrue(m.followFor(base, 5.0) <= 1.0)
        assertTrue(m.baseFollow(strong = false) > base)
    }

    @Test
    fun `the Stabilization setting picks the RAW EIS and the converter declaration its gain`() {
        assertNull(rawEisConfigFor(VideoStabMode.OFF, false, 4.286f, 90, 3.067f))
        assertNull(rawEisConfigFor(VideoStabMode.STANDARD, false, 4.286f, 90, 0f))
        val standard = rawEisConfigFor(VideoStabMode.STANDARD, false, 4.286f, 90, 3.067f)!!
        assertEquals(0.10, standard.margin, 0.0)
        assertFalse(standard.strong)
        assertNull(standard.converterMagnification)
        val active = rawEisConfigFor(VideoStabMode.ENHANCED, true, 4.286f, 90, 3.067f)!!
        assertEquals(0.20, active.margin, 0.0)
        assertTrue(active.strong)
        assertEquals(4.286, active.converterMagnification!!, 1e-6)
        assertTrue(abs(active.focalInImageWidths - 3.067) < 1e-6)
    }
}
