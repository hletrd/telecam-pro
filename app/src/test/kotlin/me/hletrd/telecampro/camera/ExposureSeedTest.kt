package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The equal-EV exposure transfer that seeds the app-side AE loop across a lens switch.
 *
 * These pin PHYSICAL conclusions — a one-stop-faster lens halves the exposure, a round trip
 * returns, equal glass perturbs nothing — not the algebra of `(N_in / N_out)²`, which would just
 * restate the implementation.
 */
class ExposureSeedTest {

    // PMA110's actual advertised f-numbers (dumpsys media.camera, docs/reviews/stock-tc-analysis):
    // cam 0/2 main 23 mm f/1.58, cam 3 ultrawide f/2.05, cam 4 the 70 mm periscope f/2.26,
    // cam 5 the 230 mm periscope f/3.50, cam 1 front f/2.40.
    private val mainF = 1.58f
    private val teleF = 2.26f
    private val superTeleF = 3.50f

    private val isoMin = 50
    private val isoMax = 12800
    private val expMinNs = 14_000L
    private val expMaxNs = 4_000_000_000L

    private fun seed(
        outF: Float,
        inF: Float,
        mode: ExposureMode = ExposureMode.PROGRAM,
        iso: Int = 400,
        exposureNs: Long = 8_000_000L,
        angleDerivedShutter: Boolean = false,
        isoMax: Int = this.isoMax,
        expMaxNs: Long = this.expMaxNs,
    ): SeededExposure? = AutoExposure.seedForApertureChange(
        exposureMode = mode,
        angleDerivedShutter = angleDerivedShutter,
        iso = iso,
        exposureTimeNs = exposureNs,
        outgoingApertureF = outF,
        incomingApertureF = inF,
        isoMin = isoMin,
        isoMax = isoMax,
        expMinNs = expMinNs,
        expMaxNs = expMaxNs,
    )

    private fun stopsBetween(a: Double, b: Double): Double = ln(b / a) / ln(2.0)

    // One TRUE stop apart, not the rounded engraving: the nominal f/2.8 mark is really 2*sqrt(2).
    private val oneStopSlowerThanF2 = 2.828427f

    @Test
    fun `a one stop faster lens exactly halves the exposure`() {
        // f/2.83 -> f/2.0 is one whole stop of aperture: the incoming lens gathers twice the light,
        // so the same scene needs half the time. Nothing about the formula, everything about light.
        val seeded = seed(outF = oneStopSlowerThanF2, inF = 2.0f, exposureNs = 16_000_000L)!!
        assertEquals(ExposureSeedCarrier.EXPOSURE_TIME, seeded.carrier)
        assertEquals(8_000_000.0, seeded.exposureTimeNs.toDouble(), 8_000_000.0 * 0.001)
        // Two stops (f/4 -> f/2) is a quarter, by the same physics — and exact, no rounding excuse.
        val twoStops = seed(outF = 4.0f, inF = 2.0f, exposureNs = 16_000_000L)!!
        assertEquals(4_000_000L, twoStops.exposureTimeNs)
    }

    @Test
    fun `a slower lens lengthens by the same amount it would have to open`() {
        // The reverse direction is the one that matters on this device: main -> 10x periscope.
        val seeded = seed(outF = mainF, inF = superTeleF, exposureNs = 8_000_000L)!!
        val stops = stopsBetween(8_000_000.0, seeded.exposureTimeNs.toDouble())
        // f/1.58 -> f/3.50 is ~2.3 stops of real glass on PMA110. That gap IS the feature: an
        // unseeded switch to the 10x lens starts more than two stops dark and swings back.
        assertEquals(2.295, stops, 0.01)
        assertTrue("a slower lens must need MORE time", seeded.exposureTimeNs > 8_000_000L)
    }

    @Test
    fun `the transfer round trips back to the original exposure`() {
        val out = seed(outF = teleF, inF = mainF, exposureNs = 12_500_000L)!!
        val back = seed(outF = mainF, inF = teleF, exposureNs = out.exposureTimeNs)!!
        // Long rounding is the only loss; the physics is exactly invertible.
        assertEquals(12_500_000.0, back.exposureTimeNs.toDouble(), 3.0)
        assertEquals(400, back.iso) // ISO is held, not split
    }

    @Test
    fun `equal apertures perturb nothing`() {
        // The common case on a multi-lens phone: two lenses that share an f-number (PMA110's front
        // f/2.40 and cam 6 f/2.40). Identity must be EXACT — a switch between them may not nudge
        // the exposure by even one nanosecond.
        val time = seed(outF = 2.40f, inF = 2.40f, exposureNs = 8_333_333L)!!
        assertEquals(8_333_333L, time.exposureTimeNs)
        assertEquals(400, time.iso)
        val sensitivity = seed(outF = 2.40f, inF = 2.40f, mode = ExposureMode.SHUTTER, iso = 1600)!!
        assertEquals(1600, sensitivity.iso)
        assertEquals(8_000_000L, sensitivity.exposureTimeNs)
    }

    @Test
    fun `an unusable f number on either side refuses to guess`() {
        // LENS_INFO_AVAILABLE_APERTURES absent reads back as 0f through lensExifMetadataOf.
        assertNull(seed(outF = 0f, inF = teleF))
        assertNull(seed(outF = mainF, inF = 0f))
        assertNull(seed(outF = -1.8f, inF = teleF))
        assertNull(seed(outF = mainF, inF = -2.0f))
        assertNull(seed(outF = Float.NaN, inF = teleF))
        assertNull(seed(outF = mainF, inF = Float.POSITIVE_INFINITY))
        // Degenerate current values are equally unusable as a starting point.
        assertNull(seed(outF = mainF, inF = teleF, iso = 0))
        assertNull(seed(outF = mainF, inF = teleF, exposureNs = 0L))
    }

    @Test
    fun `only the axis the app-side loop owns may move`() {
        // MANUAL: the photographer set both. Swapping glass on a real body does not re-dial them.
        assertNull(AutoExposure.seedCarrier(ExposureMode.MANUAL, angleDerivedShutter = false))
        // SHUTTER priority: the time is the user's, so ISO carries and the time is untouched.
        val shutterPriority =
            seed(outF = 2.0f, inF = oneStopSlowerThanF2, mode = ExposureMode.SHUTTER, iso = 800)!!
        assertEquals(ExposureSeedCarrier.ISO, shutterPriority.carrier)
        assertEquals(1600.0, shutterPriority.iso.toDouble(), 1.0) // one stop slower lens
        assertEquals(8_000_000L, shutterPriority.exposureTimeNs)
        // ISO priority: ISO is the user's, so the time carries and ISO is untouched.
        val isoPriority =
            seed(outF = 2.0f, inF = oneStopSlowerThanF2, mode = ExposureMode.ISO, iso = 800)!!
        assertEquals(ExposureSeedCarrier.EXPOSURE_TIME, isoPriority.carrier)
        assertEquals(800, isoPriority.iso)
        assertTrue(isoPriority.exposureTimeNs > 8_000_000L)
    }

    @Test
    fun `an angle derived shutter is not a usable carrier`() {
        // In ShutterMode.ANGLE the effective exposure comes from the cine angle and fps, so writing
        // exposureTimeNs changes nothing on the wire. PROGRAM owns ISO too and falls back to it...
        val program = seed(outF = mainF, inF = superTeleF, angleDerivedShutter = true)!!
        assertEquals(ExposureSeedCarrier.ISO, program.carrier)
        assertTrue(program.iso > 400)
        // ...while ISO priority has nothing left it is allowed to move.
        assertNull(seed(outF = mainF, inF = superTeleF, mode = ExposureMode.ISO, angleDerivedShutter = true))
    }

    @Test
    fun `a transfer past the incoming route's range lands on the bound`() {
        // A 4 s ceiling (the device-verified HAL_SAFE_MAX_STILL_EXPOSURE_NS) with a 2 s start: the
        // ~2.28-stop transfer wants ~9.7 s. It must clamp, not overflow past what the route accepts.
        val clamped = seed(outF = mainF, inF = superTeleF, exposureNs = 2_000_000_000L)!!
        assertEquals(expMaxNs, clamped.exposureTimeNs)
        // The floor clamps too: a fast lens shortening a near-minimum exposure.
        val floored = seed(outF = superTeleF, inF = mainF, exposureNs = 20_000L)!!
        assertEquals(expMinNs, floored.exposureTimeNs)
        // Same on the ISO axis in SHUTTER priority.
        val ceiling = seed(outF = mainF, inF = superTeleF, mode = ExposureMode.SHUTTER, iso = 12800)!!
        assertEquals(isoMax, ceiling.iso)
        val floor = seed(outF = superTeleF, inF = mainF, mode = ExposureMode.SHUTTER, iso = 50)!!
        assertEquals(isoMin, floor.iso)
    }

    @Test
    fun `an inverted advertised range clamps nothing instead of throwing`() {
        // coerceIn on min > max throws; a caps-read glitch must not crash a lens switch.
        val inverted = seed(outF = teleF, inF = mainF, exposureNs = 8_000_000L, expMaxNs = 1_000L)!!
        assertTrue(inverted.exposureTimeNs > 0L)
        val invertedIso =
            seed(outF = teleF, inF = mainF, mode = ExposureMode.SHUTTER, iso = 400, isoMax = 10)!!
        assertTrue(invertedIso.iso > 0)
    }

    // ---- The route adapter: which routes get seeded at all ----

    private fun appSideCaps(
        isoMin: Int? = this.isoMin,
        isoMax: Int? = this.isoMax,
        expMinNs: Long? = this.expMinNs,
        expMaxNs: Long? = this.expMaxNs,
        aeModes: IntArray = intArrayOf(CameraMetadata.CONTROL_AE_MODE_OFF, CameraMetadata.CONTROL_AE_MODE_ON),
    ) = CameraControlCapabilities(
        supportsManualSensor = true,
        hasIsoRange = true,
        isoMin = isoMin,
        isoMax = isoMax,
        hasExposureTimeRange = true,
        exposureTimeMinNs = expMinNs,
        exposureTimeMaxNs = expMaxNs,
        aeModes = aeModes,
    )

    private fun seedRoute(
        controls: ManualControls,
        outgoing: CameraControlCapabilities? = appSideCaps(),
        outF: Float = mainF,
        incoming: CameraControlCapabilities = appSideCaps(),
        inF: Float = superTeleF,
        mode: CaptureMode = CaptureMode.PHOTO,
    ): ManualControls = seedExposureForRouteChange(
        requested = controls,
        outgoing = outgoing,
        outgoingApertureF = outF,
        incoming = incoming,
        incomingApertureF = inF,
        mode = mode,
    )

    private val appSideProgram = ManualControls(
        exposureMode = ExposureMode.PROGRAM,
        programAppSide = true,
        iso = 400,
        exposureTimeNs = 8_000_000L,
    )

    @Test
    fun `an app-side route on both sides is seeded`() {
        val seeded = seedRoute(appSideProgram)
        assertTrue(seeded.exposureTimeNs > appSideProgram.exposureTimeNs)
        assertEquals(appSideProgram.iso, seeded.iso) // only the carrier axis moved
    }

    @Test
    fun `a cold start has nothing to carry`() {
        assertSame(appSideProgram, seedRoute(appSideProgram, outgoing = null, outF = 0f))
    }

    @Test
    fun `a HAL-AE route on either side is skipped`() {
        // video PROGRAM / flash-metered PROGRAM: the HAL meters, and a seed would fight it.
        val halAe = appSideProgram.copy(programAppSide = false)
        assertSame(halAe, seedRoute(halAe))
        // A route that cannot take manual sensor values has no keys to seed either.
        val noManual = appSideCaps().copy(supportsManualSensor = false)
        assertSame(appSideProgram, seedRoute(appSideProgram, incoming = noManual))
        assertSame(appSideProgram, seedRoute(appSideProgram, outgoing = noManual))
        // AE_OFF unadvertised means the app never owns exposure on that route.
        val aeOnOnly = appSideCaps(aeModes = intArrayOf(CameraMetadata.CONTROL_AE_MODE_ON))
        assertSame(appSideProgram, seedRoute(appSideProgram, incoming = aeOnOnly))
    }

    @Test
    fun `an incoming route with no advertised bounds is skipped`() {
        assertSame(appSideProgram, seedRoute(appSideProgram, incoming = appSideCaps(isoMax = null)))
        assertSame(appSideProgram, seedRoute(appSideProgram, incoming = appSideCaps(expMaxNs = null)))
    }

    @Test
    fun `video seeds under the frame-interval ceiling, never past it`() {
        // In VIDEO the loop's own upper bound is 1/fps; seeding above it would just be walked back
        // on the next tick and would show a shutter the cadence cannot hold.
        val videoIsoPriority = ManualControls(
            exposureMode = ExposureMode.ISO,
            iso = 800,
            exposureTimeNs = 16_666_666L, // 1/60 s at 30 fps
            fps = 30,
        )
        val seeded = seedRoute(videoIsoPriority, mode = CaptureMode.VIDEO)
        assertNotEquals(videoIsoPriority.exposureTimeNs, seeded.exposureTimeNs)
        assertTrue(
            "video seed must stay within one frame interval (${seeded.exposureTimeNs} ns)",
            seeded.exposureTimeNs <= 1_000_000_000L / 30,
        )
        // PHOTO on the same packet has the whole sensor range and lands past a frame interval.
        val photo = seedRoute(videoIsoPriority, mode = CaptureMode.PHOTO)
        assertTrue(photo.exposureTimeNs > 1_000_000_000L / 30)
    }

    @Test
    fun `MANUAL exposure is never touched by a route change`() {
        val manual = ManualControls(exposureMode = ExposureMode.MANUAL, iso = 200, exposureTimeNs = 4_000_000L)
        assertSame(manual, seedRoute(manual))
    }

    @Test
    fun `re-delivering the same route is an exact identity`() {
        // onCapsReady can fire again for an unchanged route (fast same-route commits, rollback).
        // Outgoing and incoming f-numbers are then equal, so a second pass must change nothing.
        val once = seedRoute(appSideProgram)
        val twice = seedRoute(once, outF = superTeleF, inF = superTeleF)
        assertEquals(once, twice)
    }
}
