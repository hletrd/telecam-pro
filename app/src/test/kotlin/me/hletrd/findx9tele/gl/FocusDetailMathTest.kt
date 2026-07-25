package me.hletrd.findx9tele.gl

import me.hletrd.findx9tele.camera.FocusDetailData
import me.hletrd.findx9tele.camera.FrameDetail
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure frame-detail metric ([computeFocusDetail]) on synthetic RGBA buffers —
 * no GL, no device, mirroring [AnalysisMathTest]'s solid-buffer style.
 *
 * The contract under test is asymmetric: the metric MAY MISS, it must NEVER report [FrameDetail.SOFT]
 * on a frame that resolves detail. So every case here asserts either an exact verdict or, where the
 * input is random, the one-sided `!= SOFT`.
 *
 * Expected ratios are closed-form where the input is periodic (see the derivation in FocusDetail.kt)
 * and pinned to a tolerance elsewhere; the surrounding VERDICT is always the load-bearing assertion.
 */
class FocusDetailMathTest {

    // ---- synthetic frame builders (all 256x192, R=G=B so luma == the plane value) --------------

    companion object {
        private const val W = 256
        private const val H = 192

        private fun q(v: Double): Int = Math.rint(v).toInt().coerceIn(0, 255)

        private fun plane(f: (Int, Int) -> Double): IntArray {
            val p = IntArray(W * H)
            for (y in 0 until H) for (x in 0 until W) p[y * W + x] = q(f(x, y))
            return p
        }

        /** Separable box blur, edge-clamped, [passes] passes; quantised once at the end. */
        private fun boxBlur(src: IntArray, taps: Int, passes: Int = 3): IntArray {
            var cur = DoubleArray(src.size) { src[it].toDouble() }
            val half = taps / 2
            repeat(passes) {
                val tmp = DoubleArray(src.size)
                for (y in 0 until H) for (x in 0 until W) {
                    var s = 0.0
                    for (d in -half..half) s += cur[y * W + (x + d).coerceIn(0, W - 1)]
                    tmp[y * W + x] = s / taps
                }
                val out = DoubleArray(src.size)
                for (y in 0 until H) for (x in 0 until W) {
                    var s = 0.0
                    for (d in -half..half) s += tmp[(y + d).coerceIn(0, H - 1) * W + x]
                    out[y * W + x] = s / taps
                }
                cur = out
            }
            return IntArray(src.size) { q(cur[it]) }
        }

        /** Blur along x ONLY — the motion-blur model. */
        private fun boxBlurX(src: IntArray, taps: Int, passes: Int = 3): IntArray {
            var cur = DoubleArray(src.size) { src[it].toDouble() }
            val half = taps / 2
            repeat(passes) {
                val out = DoubleArray(src.size)
                for (y in 0 until H) for (x in 0 until W) {
                    var s = 0.0
                    for (d in -half..half) s += cur[y * W + (x + d).coerceIn(0, W - 1)]
                    out[y * W + x] = s / taps
                }
                cur = out
            }
            return IntArray(src.size) { q(cur[it]) }
        }

        private fun scale(src: IntArray, k: Double) = IntArray(src.size) { q(src[it] * k) }

        private fun mirrorX(src: IntArray) =
            IntArray(src.size) { src[(it / W) * W + (W - 1 - it % W)] }

        private fun rgba(p: IntArray): ByteArray {
            val b = ByteArray(p.size * 4)
            for (i in p.indices) {
                val v = p[i].toByte()
                b[i * 4] = v
                b[i * 4 + 1] = v
                b[i * 4 + 2] = v
                b[i * 4 + 3] = 255.toByte()
            }
            return b
        }

        // Shared heavy builds (blur is the expensive part); computed once for the whole class.
        val checker: IntArray by lazy { plane { x, y -> if (((x / 8) + (y / 8)) % 2 == 0) 210.0 else 30.0 } }
        val checkerBlur7: IntArray by lazy { boxBlur(checker, 7) }
        val macroSharp: IntArray by lazy {
            plane { x, y ->
                128 + 90 * sin(2 * PI * x / 110.0) * sin(2 * PI * y / 95.0) +
                    60 * sin(2 * PI * (x + y) / 6.0)
            }
        }
        val macroBlur31: IntArray by lazy { boxBlur(macroSharp, 31) }
    }

    private fun detail(p: IntArray): FocusDetailData = computeFocusDetail(rgba(p), W, H)

    private fun sine(period: Double) = plane { x, y ->
        128 + 20 * sin(2 * PI * x / period) + 20 * sin(2 * PI * y / period)
    }

    // ---- 1..3: nothing to judge --------------------------------------------------------------

    @Test
    fun `a flat frame is unjudgeable, never soft`() {
        val d = detail(plane { _, _ -> 128.0 })
        assertEquals(FrameDetail.UNJUDGEABLE, d.verdict)
        assertEquals(0, d.judgeableTiles)
        assertEquals(96, d.totalTiles)
        assertEquals(0f, d.bestRatio, 0f)
    }

    @Test
    fun `a linear ramp has exactly zero curvature and is unjudgeable`() {
        // THE RAMP PIN. The first-difference formulation this metric rejected returns exactly 1/k
        // here — indistinguishable from blur — so a sky-gradient pan would be a guaranteed false
        // fire. Curvature is identically zero on any ramp, at every lag.
        val d = detail(plane { x, _ -> 20 + 0.8 * x })
        assertEquals(FrameDetail.UNJUDGEABLE, d.verdict)
        assertEquals(0, d.judgeableTiles)
    }

    @Test
    fun `a fractional ramp's quantisation dither is not structure`() {
        // Realistic sky: the rounding dither is white, so it lands in the fine AND coarse lags
        // equally and never lifts a tile over the coarse floor.
        assertEquals(FrameDetail.UNJUDGEABLE, detail(plane { x, _ -> 40 + 0.63 * x }).verdict)
    }

    // ---- 4..6: the sharp/soft axis -----------------------------------------------------------

    @Test
    fun `a sharp checkerboard resolves and lands on its closed-form ratio`() {
        // 8 px cells => a period-16 square wave per axis. P_1 = 0.25A^2 (two transitions per
        // period), P_8 = 4A^2 (the +-8 neighbours are both in the opposite run) => R = 0.5A/2A.
        val d = detail(checker)
        assertEquals(FrameDetail.RESOLVED, d.verdict)
        assertEquals(96, d.judgeableTiles)
        assertEquals(0, d.softTiles)
        assertEquals(96, d.sharpTiles)
        assertEquals(0.25, d.bestRatio.toDouble(), 0.01)
    }

    @Test
    fun `blurring that checkerboard makes every tile soft`() {
        val d = detail(checkerBlur7)
        assertEquals(FrameDetail.SOFT, d.verdict)
        assertEquals(96, d.judgeableTiles)
        assertEquals(96, d.softTiles)
        assertEquals(0, d.sharpTiles)
        assertTrue("blurred best=${d.bestRatio}", d.bestRatio < FOCUS_SOFT_RATIO)
        // Ordering, not just thresholds: blurring must move the ratio by a wide margin.
        assertTrue(d.bestRatio < detail(checker).bestRatio / 3f)
    }

    @Test
    fun `a one-pixel blur is still resolved - the threshold is not hair-trigger`() {
        // THE MILD-BLUR PIN, and the reason FOCUS_SOFT_RATIO is 0.08 rather than the 0.10 the
        // sinusoid closed form alone would allow: a single 3-tap box pass leaves an obviously
        // resolved image measuring 0.0945, which 0.10 would have called SOFT.
        val d = detail(boxBlur(checker, 3, passes = 1))
        assertEquals(FrameDetail.RESOLVED, d.verdict)
        assertEquals(0.0945, d.bestRatio.toDouble(), 0.01)
        assertTrue("must sit above the threshold", d.bestRatio > FOCUS_SOFT_RATIO)
    }

    @Test
    fun `sinusoid response follows the closed form and falls monotonically with period`() {
        val r8 = detail(sine(8.0))
        val r12 = detail(sine(12.0))
        val r24 = detail(sine(24.0))
        val r64 = detail(sine(64.0))
        // R(T) = (1 - cos 2pi/T) / max_k (1 - cos 2pi k/T), k in {4,8,16,32}.
        assertEquals(0.1464, r8.bestRatio.toDouble(), 0.015)
        assertEquals(0.0893, r12.bestRatio.toDouble(), 0.015)
        assertEquals(0.0227, r24.bestRatio.toDouble(), 0.010)
        assertEquals(0.0024, r64.bestRatio.toDouble(), 0.015)
        assertTrue(r8.bestRatio > r12.bestRatio)
        assertTrue(r12.bestRatio > r24.bestRatio)
        assertTrue(r24.bestRatio > r64.bestRatio)
        assertEquals(FrameDetail.RESOLVED, r8.verdict)
        assertEquals(FrameDetail.RESOLVED, r12.verdict)
        assertEquals(FrameDetail.SOFT, r24.verdict)
        assertEquals(FrameDetail.SOFT, r64.verdict)
    }

    // ---- 7..8: isotropy --------------------------------------------------------------------

    @Test
    fun `a diagonal step edge resolves at its closed-form ratio`() {
        // s_k = A*sqrt(2k/N) for a step, so R = sqrt(2/64) against the lag-32 coarse term.
        val d = detail(plane { x, y -> if (x + y > 220) 200.0 else 30.0 })
        assertEquals(FrameDetail.RESOLVED, d.verdict)
        assertEquals(0.33, d.bestRatio.toDouble(), 0.05)
        assertTrue("edge tiles must vote sharp", d.sharpTiles > 0)
    }

    @Test
    fun `a one-dimensional step is unjudgeable - both axes must carry coarse structure`() {
        // THE ISOTROPY PIN. A purely vertical edge has zero curvature along y, so no tile qualifies
        // and the frame cannot be called soft off one axis alone.
        val d = detail(plane { x, _ -> if (x > 128) 200.0 else 30.0 })
        assertEquals(FrameDetail.UNJUDGEABLE, d.verdict)
        assertEquals(0, d.judgeableTiles)
    }

    @Test
    fun `blur along one axis only stays out of the soft verdict`() {
        // THE MOTION-BLUR PIN: pan/track blur is directional, so the cross-axis curvature survives
        // and the frame never reads soft (here it drops out entirely, which is equally safe).
        assertNotEquals(FrameDetail.SOFT, detail(boxBlurX(checker, 25)).verdict)
    }

    // ---- 9..10: noise and exposure ----------------------------------------------------------

    @Test
    fun `noise is never soft in either regime`() {
        val rng = Random(4242)
        // Low-amplitude grain: white energy sits below the coarse floor -> nothing votes.
        val quiet = detail(plane { _, _ -> 128 + rng.nextDouble(-8.0, 8.0) })
        assertEquals(FrameDetail.UNJUDGEABLE, quiet.verdict)
        // Loud grain: white energy is EQUAL at every lag, so the ratio goes to ~1 -> every tile
        // votes sharp. Noise suppresses this detector by construction, in both directions.
        val loud = detail(plane { _, _ -> 128 + rng.nextDouble(-25.0, 25.0) })
        assertEquals(FrameDetail.RESOLVED, loud.verdict)
        assertTrue("white noise ratio ~1, was ${loud.bestRatio}", loud.bestRatio > 0.5f)
    }

    @Test
    fun `crushed and blown frames are refused by the luma window`() {
        val rng = Random(99)
        assertEquals(FrameDetail.UNJUDGEABLE, detail(plane { _, _ -> 5.0 }).verdict)
        assertEquals(FrameDetail.UNJUDGEABLE, detail(plane { _, _ -> 5 + rng.nextDouble(-3.0, 3.0) }).verdict)
        assertEquals(FrameDetail.UNJUDGEABLE, detail(plane { _, _ -> 250.0 }).verdict)
    }

    // ---- 11..13: partial scenes and amplitude ------------------------------------------------

    @Test
    fun `a half-sharp frame resolves - one detailed region is enough`() {
        // THE PARTIAL-SCENE PIN. Soft requires essentially EVERY judgeable tile to lack detail.
        val split = checker.copyOf()
        for (y in 0 until H) for (x in W / 2 until W) split[y * W + x] = checkerBlur7[y * W + x]
        val d = detail(split)
        assertEquals(FrameDetail.RESOLVED, d.verdict)
        assertTrue(d.sharpTiles > FOCUS_SHARP_TOLERANCE * d.judgeableTiles)
    }

    @Test
    fun `a small sharp subject inside a defocused field keeps the frame resolved`() {
        val sub = macroBlur31.copyOf()
        for (y in 80 until 112) for (x in 110 until 142) sub[y * W + x] = macroSharp[y * W + x]
        val d = detail(sub)
        assertEquals(FrameDetail.RESOLVED, d.verdict)
        assertTrue(d.sharpTiles >= 2)
    }

    @Test
    fun `a single hot-pixel cluster cannot veto an otherwise defocused frame`() {
        // The reason FOCUS_SHARP_TOLERANCE is not zero: a stuck-pixel cluster is one tile, and a
        // strict rule would let it disable the feature permanently.
        val hot = macroBlur31.copyOf()
        for (y in 100 until 104) for (x in 132 until 136) hot[y * W + x] = 255
        val d = detail(hot)
        assertEquals(FrameDetail.SOFT, d.verdict)
        assertEquals(1, d.sharpTiles)
    }

    @Test
    fun `the ratio is amplitude-invariant until the coarse floor refuses`() {
        val full = detail(checkerBlur7)
        val dim = detail(scale(checkerBlur7, 0.6))
        assertEquals(FrameDetail.SOFT, dim.verdict)
        assertTrue("ratio must not swing with level", dim.bestRatio < full.bestRatio * 2f)
        // Far enough down, the coarse floor refuses rather than inventing a verdict — and it
        // refuses toward UNJUDGEABLE, never toward RESOLVED-by-accident.
        assertEquals(FrameDetail.UNJUDGEABLE, detail(scale(checkerBlur7, 0.15)).verdict)
    }

    // ---- 14: display-gain contamination ------------------------------------------------------

    @Test
    fun `the display gain LUT cannot create a fire`() {
        // computeFocusDetail deliberately has NO lut parameter, so the scopes' brightness
        // simulation can never reach it. This pins the other direction too: pushing the pixels
        // through that LUT by hand does not flip a soft frame into a resolved one or vice versa.
        val lut = checkNotNull(digitalGainDisplayLut(4f))
        val boosted = IntArray(checkerBlur7.size) { lut[checkerBlur7[it]] }
        val d = detail(boosted)
        assertEquals(FrameDetail.SOFT, d.verdict)
        assertEquals(detail(checkerBlur7).bestRatio.toDouble(), d.bestRatio.toDouble(), 0.02)
    }

    // ---- 15..16: degenerate inputs and mirror invariance --------------------------------------

    @Test
    fun `degenerate buffers return the unjudged singleton without throwing`() {
        assertSame(FocusDetailData.UNJUDGED, computeFocusDetail(ByteArray(16 * 16 * 4), 16, 16))
        assertSame(FocusDetailData.UNJUDGED, computeFocusDetail(ByteArray(4), 1, 1))
        assertSame(FocusDetailData.UNJUDGED, computeFocusDetail(ByteArray(16), W, H))
        assertSame(FocusDetailData.UNJUDGED, computeFocusDetail(ByteArray(0), 0, 0))
        assertSame(FocusDetailData.UNJUDGED, computeFocusDetail(ByteArray(64), -4, 8))
        // A buffer whose short edge cannot host one tile beyond the lag border.
        assertSame(FocusDetailData.UNJUDGED, computeFocusDetail(ByteArray(256 * 70 * 4), 256, 70))
        assertTrue(FocusDetailData.UNJUDGED.bestRatio.isFinite())
    }

    @Test
    fun `the metric is invariant under an x mirror`() {
        // Front-facing needs no special case: the analysis draw un-mirrors, and |D_k^x| is
        // symmetric under an x-inversion anyway. Pinned so the question is not re-litigated.
        val direct = detail(checkerBlur7)
        val mirrored = detail(mirrorX(checkerBlur7))
        assertEquals(direct.verdict, mirrored.verdict)
        assertEquals(direct.bestRatio, mirrored.bestRatio, 1e-6f)
        assertEquals(direct.judgeableTiles, mirrored.judgeableTiles)
    }

    // ---- the target case ---------------------------------------------------------------------

    @Test
    fun `a heavily defocused frame with large-scale contrast reads soft`() {
        // The case this whole seam exists for: fine structure annihilated, coarse structure intact.
        // Lag 32 is what makes it reachable — with coarse lags capped at 16 this frame came out
        // UNJUDGEABLE, i.e. a miss of the one scenario the detector is for.
        val d = detail(macroBlur31)
        assertEquals(FrameDetail.SOFT, d.verdict)
        assertEquals(0, d.sharpTiles)
        assertTrue("coverage must be real, was ${d.judgeableTiles}/${d.totalTiles}", d.judgeableTiles >= 80)
        assertTrue(d.bestRatio < FOCUS_SOFT_RATIO)
        // ...and the same scene in focus does not.
        assertEquals(FrameDetail.RESOLVED, detail(macroSharp).verdict)
    }

    // ---- the pure sub-seams -------------------------------------------------------------------

    @Test
    fun `rms clamps a negative accumulator instead of returning NaN`() {
        assertEquals(0.0, focusRms(-1.0), 0.0)
        assertEquals(0.0, focusRms(0.0), 0.0)
        assertEquals(4.0, focusRms(16.0), 1e-9)
    }

    @Test
    fun `a tile needs coarse structure on both axes to vote`() {
        assertTrue(focusTileJudgeable(FOCUS_COARSE_FLOOR, FOCUS_COARSE_FLOOR))
        assertTrue(focusTileJudgeable(200.0, 40.0))
        assertTrue(!focusTileJudgeable(200.0, FOCUS_COARSE_FLOOR - 0.01))
        assertTrue(!focusTileJudgeable(0.0, 200.0))
    }

    @Test
    fun `the tile ratio takes the worst axis`() {
        assertEquals(0.5, focusTileRatio(fineRmsX = 50.0, fineRmsY = 1.0, coarseRmsX = 100.0, coarseRmsY = 100.0), 1e-9)
        assertEquals(0.5, focusTileRatio(fineRmsX = 1.0, fineRmsY = 50.0, coarseRmsX = 100.0, coarseRmsY = 100.0), 1e-9)
        // A zero denominator can only mean "no coarse structure", which judgeability already
        // refuses; if it ever reached here it yields +Inf, i.e. sharp — the fail-safe direction.
        assertTrue(focusTileRatio(1.0, 1.0, 0.0, 100.0) > FOCUS_SOFT_RATIO)
    }

    @Test
    fun `the frame rule needs both an absolute tile count and real coverage`() {
        assertEquals(FrameDetail.UNJUDGEABLE, focusFrameVerdict(totalTiles = 96, judgeableTiles = 23, sharpTiles = 0))
        // 24 tiles clears the absolute floor but not 30% of 96.
        assertEquals(FrameDetail.UNJUDGEABLE, focusFrameVerdict(96, 24, 0))
        assertEquals(FrameDetail.SOFT, focusFrameVerdict(96, 29, 0))
        assertEquals(FrameDetail.RESOLVED, focusFrameVerdict(96, 29, 5))
        // 2% of 96 judgeable tiles tolerates exactly one sharp tile.
        assertEquals(FrameDetail.SOFT, focusFrameVerdict(96, 96, 1))
        assertEquals(FrameDetail.RESOLVED, focusFrameVerdict(96, 96, 2))
        // A tiny grid can never reach the absolute floor, whatever its coverage.
        assertEquals(FrameDetail.UNJUDGEABLE, focusFrameVerdict(10, 10, 0))
    }

    @Test
    fun `the data carrier reports sharp tiles and behaves as a value`() {
        val d = FocusDetailData(FrameDetail.SOFT, totalTiles = 96, judgeableTiles = 90, softTiles = 89, bestRatio = 0.03f)
        assertEquals(1, d.sharpTiles)
        assertEquals(d, d.copy())
        assertEquals(d.hashCode(), d.copy().hashCode())
        assertNotEquals(d, d.copy(verdict = FrameDetail.RESOLVED))
        assertTrue(d.toString().contains("SOFT"))
        val (verdict, total, judgeable, soft, ratio) = d
        assertEquals(FrameDetail.SOFT, verdict)
        assertEquals(96, total)
        assertEquals(90, judgeable)
        assertEquals(89, soft)
        assertEquals(0.03f, ratio, 0f)
        assertEquals(FrameDetail.UNJUDGEABLE, FocusDetailData.UNJUDGED.verdict)
        assertEquals(0, FocusDetailData.UNJUDGED.sharpTiles)
    }
}
