package me.hletrd.findx9tele.gl

import me.hletrd.findx9tele.camera.FocusDetailData
import me.hletrd.findx9tele.camera.FrameDetail
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Frame-detail metric: "does this frame resolve any FINE structure, or only coarse structure?"
 *
 * WHY THIS EXISTS (device-measured 2026-07-25, PMA110): with a subject ~9 cm from the lens on the
 * TELE route (advertised minimum focus 120 cm) the preview is completely defocused, yet the HAL
 * reports `afState = FOCUSED_LOCKED` with `LENS_FOCUS_DISTANCE = 0.0068` diopters (~146 m — racked
 * to infinity). Both inputs of [me.hletrd.findx9tele.focus.macroTooCloseCandidate] (an AF failure
 * verdict AND a lens racked near its close limit) are therefore structurally unreachable here, so
 * the only remaining evidence is the app's OWN pixels.
 *
 * WHAT IT PROVES: that the analysed frame carries coarse structure but resolves nothing fine. That
 * is strictly WEAKER than "the subject is closer than this lens can focus" — a single frame cannot
 * separate defocus from a genuinely soft subject (haze, a fogged converter, isotropic shake at
 * 300 mm). The OSD wording follows the proof, not the hope: this path says `SOFT`, never
 * `TOO CLOSE`, and never names a lens to switch to (see focus/MacroProximity.kt).
 *
 * IT MAY MISS; IT MUST NEVER FALSE-FIRE. Every ambiguous input is steered to UNJUDGEABLE or
 * RESOLVED, never SOFT.
 *
 * ## The metric
 *
 * Per 16x16 tile, per axis, the mean SQUARE of the second difference (curvature) at a fine lag and
 * three coarse lags:
 *
 *     D_k(p) = 2*L(p) - L(p - k) - L(p + k)          P_k = mean(D_k^2)     s_k = sqrt(P_k)
 *     R      = max over axes of  s_1 / max(s_4, s_8, s_16, s_32)
 *
 * CURVATURE, not gradient: a linear ramp (sky gradient, wall falloff) is locally linear at every
 * scale, so a FIRST-difference ratio returns exactly 1/k on it — indistinguishable from blur, and a
 * guaranteed false fire on a clear-sky pan. Curvature is exactly zero on a ramp, which makes a ramp
 * *unjudgeable by construction* instead of "soft".
 *
 * NO NOISE SUBTRACTION. An earlier form of this design subtracted a per-frame noise floor
 * (percentile-10 of the per-tile fine energy) from every lag so the detector would survive grain.
 * Prototyping killed it: on a SHARP but UNIFORMLY textured frame that percentile IS the real fine
 * content, so subtracting it drove s_1 to ~0 while coarse structure survived — a reproducible
 * FALSE FIRE on an in-focus scene. Without subtraction, sensor noise is white and therefore lands
 * identically in every lag; because s_1 is small in a blurred frame and the noise floor is not, it
 * RAISES R and suppresses the detector. Noise fails safe by construction rather than by tuning,
 * and the cost — a grainy (dark, high-ISO) preview simply never fires — is bought back by the
 * exposure gate in focus/MacroProximity.kt, which refuses those frames anyway.
 *
 * MAX over three coarse lags, not one: a single lag has spectral nulls (lag 8 is blind to period-8
 * content). A null is fail-safe (it drives the tile unjudgeable) but it is also a sensitivity hole;
 * only content whose period divides EVERY coarse lag (period 2 and 4) nulls them all, and that case
 * stays fail-safe. Lag 32 is what makes heavy defocus detectable at all: measured on synthetic
 * frames, a blur wide enough to model the ~9 cm case leaves structure only at periods >100 analysis
 * px, which lags 4/8/16 read as almost flat (the frame came out UNJUDGEABLE, i.e. a MISS of the one
 * case this exists for).
 */

/** Tile edge in analysis pixels. 16 of a <=256 px long edge ~= 6% of frame width. */
internal const val FOCUS_TILE = 16

/** Sample stride inside a tile; lags are still taken at full resolution. 8x8 = 64 samples/tile. */
internal const val FOCUS_SAMPLE_STEP = 2

/** Coarse lags. See the class note: 32 carries the heavy-defocus case, 4/8/16 fill its nulls. */
internal val FOCUS_COARSE_LAGS = intArrayOf(4, 8, 16, 32)

/** Border (max coarse lag) excluded on every side so no difference reads outside the buffer. */
internal const val FOCUS_MAX_LAG = 32

/** A tile must actually have been sampled (guards partial tiles). 64 are possible. */
internal const val FOCUS_TILE_MIN_SAMPLES = 40

/**
 * Mean-luma window a tile must sit in to vote. Outside it the 8-bit readback has crushed or blown
 * the local signal, so "no fine detail" would be a statement about the encoding, not the optics.
 * Makes "too dark / too blown to judge" an explicit, testable refusal instead of an emergent one.
 */
internal const val FOCUS_LUMA_FLOOR = 12

internal const val FOCUS_LUMA_CEIL = 243

/**
 * Minimum coarse RMS curvature (LSB) for a tile to vote at all. Below this the ratio is dividing
 * one near-zero by another and its verdict would be estimator noise, so near-flat tiles (blank
 * wall, clear sky, smooth vignette) are excluded rather than judged.
 */
internal const val FOCUS_COARSE_FLOOR = 16.0

/**
 * Fine/coarse ratio at or below which a tile resolves nothing fine.
 *
 * Chosen from measurement, not from the closed form alone. The closed form for a sinusoid of period
 * T is R = (1 - cos 2pi/T) / max_k(1 - cos 2pi*k/T); at 0.08 the boundary sits near T = 12..13
 * analysis px, i.e. ~200 sensor px ~= 5% of frame width. The binding evidence is the synthetic
 * matrix: an 8 px checkerboard given a single 3-tap box blur — still obviously resolved — measures
 * 0.0945, so the 0.10 the closed form alone would justify calls a MILDLY soft frame SOFT. Heavy
 * blur plateaus at 0.039..0.058 (8-bit quantisation, not signal), leaving 0.08 a ~1.4-2x margin on
 * the fire side and a real margin on the refuse side. Between those two facts, 0.08 is the largest
 * value that does not fire on content the eye still reads as detailed.
 */
internal const val FOCUS_SOFT_RATIO = 0.08

/** Absolute floor on voting tiles: a verdict from a handful of tiles is not a frame verdict. */
internal const val FOCUS_MIN_JUDGEABLE_TILES = 24

/** ...and they must cover a real share of the frame, not one textured corner. */
internal const val FOCUS_JUDGEABLE_COVERAGE = 0.30

/**
 * Share of judgeable tiles allowed to resolve fine detail while the FRAME still reads SOFT. Not
 * zero: a stuck-pixel cluster is one tile, and a strict `== 0` rule would let it disable the
 * feature permanently. At the ~96-tile working size this tolerates exactly one sharp tile, which a
 * hot-pixel cluster fits inside and a genuine sharp subject (>=2 tiles) does not.
 */
internal const val FOCUS_SHARP_TOLERANCE = 0.02

/** RMS curvature from a mean-square accumulator (negative input is impossible; clamped anyway). */
internal fun focusRms(meanSquare: Double): Double = sqrt(max(0.0, meanSquare))

/**
 * A tile votes only when BOTH axes carry coarse structure. This is the motion-blur guard: pan or
 * track blur is directional, so a frame blurred along one axis keeps cross-axis curvature and its
 * tiles either stay judgeable-and-sharp or drop out entirely — they can never vote SOFT.
 */
internal fun focusTileJudgeable(coarseRmsX: Double, coarseRmsY: Double): Boolean =
    min(coarseRmsX, coarseRmsY) >= FOCUS_COARSE_FLOOR

/**
 * Fine-to-coarse curvature ratio, worst (largest) axis wins — so BOTH axes must lack fine structure
 * for a tile to read soft. Only ever called on a judgeable tile, where both denominators are
 * >= [FOCUS_COARSE_FLOOR]; a zero denominator would yield +Inf, i.e. "sharp", which is the
 * fail-safe direction.
 */
internal fun focusTileRatio(
    fineRmsX: Double,
    fineRmsY: Double,
    coarseRmsX: Double,
    coarseRmsY: Double,
): Double = max(fineRmsX / coarseRmsX, fineRmsY / coarseRmsY)

/** Frame rule over the tile votes. UNJUDGEABLE and RESOLVED are distinct: neither may arm the tag. */
internal fun focusFrameVerdict(totalTiles: Int, judgeableTiles: Int, sharpTiles: Int): FrameDetail =
    when {
        judgeableTiles < FOCUS_MIN_JUDGEABLE_TILES -> FrameDetail.UNJUDGEABLE
        judgeableTiles < FOCUS_JUDGEABLE_COVERAGE * totalTiles -> FrameDetail.UNJUDGEABLE
        sharpTiles <= FOCUS_SHARP_TOLERANCE * judgeableTiles -> FrameDetail.SOFT
        else -> FrameDetail.RESOLVED
    }

/**
 * RGBA8888 analysis snapshot -> frame-detail verdict. Pure; runs on the analysis executor, never on
 * the GL thread, and only over bytes the existing scope/AE readback already produced.
 *
 * DELIBERATELY TAKES NO `lut`. Its siblings [computeHistogram]/[computeWaveform] apply
 * [digitalGainDisplayLut] so the scopes match the brightness-simulated preview. This must NOT: the
 * LUT clips at white (fabricating exactly-zero curvature at every lag — the precise confusion this
 * metric exists to avoid) and its slope varies with level (so it is not the identity on a ratio of
 * curvatures). An OPTICS verdict must not move when a display-only brightness simulation moves;
 * omitting the parameter makes that a compile error rather than a review catch.
 *
 * Note the ratio is invariant under any smooth monotone tone curve anyway: for locally smooth L,
 * d^2 g(L)/dk^2 ~= g'*L''k^2 + g''*(L'k)^2 — both terms scale as k^2, so the two-lag ratio survives
 * the BT.1886 encoding the analysis draw always uses.
 */
internal fun computeFocusDetail(bytes: ByteArray, w: Int, h: Int): FocusDetailData {
    if (w <= 0 || h <= 0 || bytes.size.toLong() < w.toLong() * h.toLong() * 4L) {
        return FocusDetailData.UNJUDGED
    }
    val usableW = w - 2 * FOCUS_MAX_LAG
    val usableH = h - 2 * FOCUS_MAX_LAG
    if (usableW < FOCUS_TILE || usableH < FOCUS_TILE) return FocusDetailData.UNJUDGED
    val tilesX = usableW / FOCUS_TILE
    val tilesY = usableH / FOCUS_TILE
    val totalTiles = tilesX * tilesY
    // Centre the tile grid in the usable region so the dropped remainder is shared between edges.
    val originX = FOCUS_MAX_LAG + (usableW - tilesX * FOCUS_TILE) / 2
    val originY = FOCUS_MAX_LAG + (usableH - tilesY * FOCUS_TILE) / 2

    // Rec.2020 luma, same weights as the scopes, materialised once. ROUNDED, unlike
    // computeHistogram's truncation: the scopes bin absolute levels where a sub-LSB bias is
    // cosmetic, whereas this differences neighbours, and truncation's discontinuity at 0 would
    // inject a spurious edge into near-black regions.
    val luma = IntArray(w * h)
    var i = 0
    var p = 0
    while (p < luma.size) {
        val r = bytes[i].toInt() and 0xFF
        val g = bytes[i + 1].toInt() and 0xFF
        val b = bytes[i + 2].toInt() and 0xFF
        luma[p] = (0.2627f * r + 0.678f * g + 0.0593f * b).roundToInt().coerceIn(0, 255)
        p++
        i += 4
    }

    val lagCount = FOCUS_COARSE_LAGS.size
    val counts = IntArray(totalTiles)
    val sums = LongArray(totalTiles)
    val fineX = LongArray(totalTiles)
    val fineY = LongArray(totalTiles)
    // Long accumulators are load-bearing: |D| <= 510 so D^2 <= 260100, which fits Int only by ~30x
    // at the current 64 samples/tile — any future tile growth would overflow silently.
    val coarseX = LongArray(totalTiles * lagCount)
    val coarseY = LongArray(totalTiles * lagCount)

    val endY = originY + tilesY * FOCUS_TILE
    val endX = originX + tilesX * FOCUS_TILE
    var y = originY
    while (y < endY) {
        val rowBase = y * w
        val tileRow = ((y - originY) / FOCUS_TILE) * tilesX
        var x = originX
        while (x < endX) {
            val t = tileRow + (x - originX) / FOCUS_TILE
            val centre = luma[rowBase + x]
            val twice = 2 * centre
            counts[t]++
            sums[t] += centre.toLong()
            val dfx = twice - luma[rowBase + x - 1] - luma[rowBase + x + 1]
            val dfy = twice - luma[rowBase - w + x] - luma[rowBase + w + x]
            fineX[t] += (dfx * dfx).toLong()
            fineY[t] += (dfy * dfy).toLong()
            var li = 0
            while (li < lagCount) {
                val k = FOCUS_COARSE_LAGS[li]
                val dcx = twice - luma[rowBase + x - k] - luma[rowBase + x + k]
                val dcy = twice - luma[rowBase - k * w + x] - luma[rowBase + k * w + x]
                val slot = li * totalTiles + t
                coarseX[slot] += (dcx * dcx).toLong()
                coarseY[slot] += (dcy * dcy).toLong()
                li++
            }
            x += FOCUS_SAMPLE_STEP
        }
        y += FOCUS_SAMPLE_STEP
    }

    var judgeable = 0
    var soft = 0
    var best = 0.0
    var t = 0
    while (t < totalTiles) {
        val n = counts[t]
        if (n >= FOCUS_TILE_MIN_SAMPLES) {
            val meanL = sums[t].toDouble() / n
            if (meanL >= FOCUS_LUMA_FLOOR && meanL <= FOCUS_LUMA_CEIL) {
                var maxCx = 0L
                var maxCy = 0L
                var li = 0
                while (li < lagCount) {
                    val slot = li * totalTiles + t
                    if (coarseX[slot] > maxCx) maxCx = coarseX[slot]
                    if (coarseY[slot] > maxCy) maxCy = coarseY[slot]
                    li++
                }
                val scx = focusRms(maxCx.toDouble() / n)
                val scy = focusRms(maxCy.toDouble() / n)
                if (focusTileJudgeable(scx, scy)) {
                    val ratio = focusTileRatio(
                        focusRms(fineX[t].toDouble() / n),
                        focusRms(fineY[t].toDouble() / n),
                        scx,
                        scy,
                    )
                    judgeable++
                    if (ratio <= FOCUS_SOFT_RATIO) soft++
                    if (ratio > best) best = ratio
                }
            }
        }
        t++
    }
    return FocusDetailData(
        verdict = focusFrameVerdict(totalTiles, judgeable, judgeable - soft),
        totalTiles = totalTiles,
        judgeableTiles = judgeable,
        softTiles = soft,
        bestRatio = best.toFloat(),
    )
}
