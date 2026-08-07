package me.hletrd.telecampro.gl

import me.hletrd.telecampro.camera.MotionAgreement
import me.hletrd.telecampro.camera.MotionInversionData
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Motion-inversion metric: "does the scene move the way the gyro says it should, or the opposite?"
 *
 * WHY THIS EXISTS. The afocal teleconverter is a telescope with no erecting prism, so the image it
 * delivers is rotated 180 degrees, and the app corrects that by rotating the preview back — but ONLY
 * when the operator has told it the converter is mounted. Passive glass cannot announce itself:
 * there is no contact, no ID, and no optical trick that reports its presence (probed 2026-08-05 —
 * even the stock OPPO camera has no detection whatsoever; its Teleconverter mode puts up a dialog
 * with "Installed" / "Not installed" BUTTONS and asks the user, and its own guide text tells you to
 * judge by whether the picture looks inverted). So a mismatch between the world and the setting is
 * invisible to the app and silently wrong for the operator.
 *
 * The phone cannot see the glass. It CAN see what the glass does. A 180-degree rotation inverts the
 * sign of image motion on BOTH axes, and the gyroscope independently knows which way the phone
 * turned. Comparing the two answers the only question that matters.
 *
 * WHAT IT PROVES: that scene motion in the ANALYSED frame runs opposite to the rotation the gyro
 * measured. That is a statement about the frame the app is currently producing — i.e. about whether
 * the CURRENT rotation setting matches reality — and it therefore covers both failure directions at
 * once: converter mounted with TELE off, and TELE on with no converter.
 *
 * WHAT IT DOES NOT PROVE: that a teleconverter specifically is attached. Any inverting optic, or a
 * mirror rig, reads the same. The OSD wording must follow the proof: this path may say the image is
 * INVERTED, and must never name the accessory it cannot see.
 *
 * IT MAY MISS; IT MUST NEVER FALSE-FIRE. Every ambiguous input is steered to
 * [MotionAgreement.UNJUDGEABLE]. A miss costs the operator nothing — they set the toggle themselves,
 * as they do today. A false fire tells them their correct setting is wrong.
 *
 * ## The metric
 *
 * The gyro supplies a predicted image-motion DIRECTION (a unit vector in analysis-frame
 * coordinates). Scene displacement caused by rotation lies along that line, so instead of a 2D block
 * search this walks 1D along the predicted axis, in both directions:
 *
 *     SAD(s) = sum over block of |cur(p) - prev(p - s*u)|     for s in [-S, +S]
 *
 * and asks which side of zero the minimum falls on. `s > 0` agrees with the gyro; `s < 0` opposes.
 *
 * WHY 1D ALONG THE AXIS, not a 2D search. Three reasons, in order of importance:
 * 1. **It is scale-free.** Only the SIGN of `s` is read, never its magnitude, so the metric needs no
 *    model of focal length, zoom ratio, crop, frame interval, or rolling shutter — every one of
 *    which would otherwise have to be right, and each of which is a place to be quietly wrong. This
 *    is what lets a 300 mm converted view and a bare 23 mm lens use identical thresholds.
 * 2. It cannot alias. A 2D search over a window large enough for a 300 mm pan would routinely find
 *    its true minimum outside the window and report an edge-clamped, meaningless vector.
 * 3. It is ~30 SAD evaluations per block instead of ~600.
 *
 * PER-BLOCK VOTES, NOT A GLOBAL ESTIMATE. The frame verdict is a supermajority over independent
 * block votes, so the block agreement fraction IS the confidence measure — which is exactly what the
 * never-false-fire contract needs, and what a single global displacement could not supply. It also
 * makes a moving subject harmless by construction: a car crossing the frame is one or two blocks
 * voting against fourteen, not a corrupted global answer.
 *
 * DIRECTIONAL TEXTURE IS THE LOAD-BEARING GUARD. A block votes only if it carries structure ALONG
 * the search axis. A clean vertical edge is perfectly sharp and perfectly uninformative about
 * vertical motion — SAD(s) is flat, the minimum is noise, and the vote would be a coin flip. Since
 * coin flips at scale produce a ~50/50 split rather than a supermajority they would mostly land as
 * UNJUDGEABLE anyway, but "mostly" is not the contract, so the flat blocks are excluded before they
 * can vote at all.
 */

/** Block edge in analysis pixels. */
internal const val MOTION_BLOCK = 24

/** Max |s| walked along the predicted axis, in analysis pixels. Also the required edge inset. */
internal const val MOTION_SEARCH = 14

/** Sample stride inside a block; 24/2 -> 12x12 = 144 samples, plenty for a sign. */
internal const val MOTION_SAMPLE_STEP = 2

/**
 * The winning |s| must reach this, in analysis pixels. Below it the phone is effectively still and
 * the "displacement" is sensor noise — sign meaningless. This is the stillness guard.
 */
internal const val MOTION_MIN_SHIFT = 2

/**
 * Mean absolute first difference ALONG the search axis that a block must carry to vote. Excludes
 * flat sky and edges perpendicular to the motion, both of which make SAD(s) flat.
 */
internal const val MOTION_TEXTURE_FLOOR = 6.0

/**
 * The best SAD must beat the stationary hypothesis SAD(0) by this factor, else the block saw no
 * motion it can localise and abstains.
 */
internal const val MOTION_IMPROVEMENT = 0.80

/**
 * The winning side must beat the OTHER side's best by this factor. Near 1.0 the two hypotheses fit
 * equally well (periodic texture, a repeating fence) and the block abstains rather than guess.
 */
internal const val MOTION_SIDE_MARGIN = 0.85

/** Fewer voting blocks than this and the frame is unjudgeable however lopsided the votes. */
internal const val MOTION_MIN_VOTING_BLOCKS = 6

/** Fraction of voting blocks that must agree. Deliberately far above a coin flip. */
internal const val MOTION_SUPERMAJORITY = 0.80

/**
 * The gyro must predict at least this much motion (analysis px over the frame interval) for the
 * frame to be judged at all. Guards the degenerate case the whole metric rests on: with no rotation
 * there is no predicted direction, and any measured displacement is parallax or subject motion.
 */
internal const val MOTION_MIN_PREDICTED_PX = 3.0

/** Consecutive same-verdict frames required before the result may be shown. */
internal const val MOTION_CONFIRM_FRAMES = 4

/**
 * One frame's block votes -> verdict. Pure; the counters exist so a device bring-up can tell WHICH
 * rule refused from a single log line (the ColorOS 300-row quota forbids per-frame logging).
 */
internal fun motionFrameVerdict(votingBlocks: Int, agreeVotes: Int, opposeVotes: Int): MotionAgreement {
    if (votingBlocks < MOTION_MIN_VOTING_BLOCKS) return MotionAgreement.UNJUDGEABLE
    val needed = MOTION_SUPERMAJORITY * votingBlocks
    return when {
        agreeVotes >= needed -> MotionAgreement.MATCHES
        opposeVotes >= needed -> MotionAgreement.INVERTED
        else -> MotionAgreement.UNJUDGEABLE
    }
}

/**
 * Whether a block's SAD profile is decisive enough to vote, given the best fit on each side of zero.
 *
 * @param bestPositive lowest SAD found at s > 0, or null if that side was unreachable
 * @param bestNegative lowest SAD found at s < 0, or null if that side was unreachable
 * @param stationary SAD at s = 0
 */
internal fun motionBlockDecisive(bestPositive: Long?, bestNegative: Long?, stationary: Long): Boolean {
    if (bestPositive == null || bestNegative == null) return false
    val winner = minOf(bestPositive, bestNegative)
    val loser = maxOf(bestPositive, bestNegative)
    // Saw real motion: the moved hypothesis must beat standing still by a clear margin.
    if (winner > MOTION_IMPROVEMENT * stationary) return false
    // And one side must beat the other, else both directions explain the block equally well.
    if (loser == 0L) return false
    return winner <= MOTION_SIDE_MARGIN * loser
}

/**
 * Accumulated confidence across frames. Immutable; fold [observe] over successive frame verdicts.
 *
 * A single frame is never enough. Requiring [MOTION_CONFIRM_FRAMES] consecutive identical verdicts
 * costs under a second at the ~6 Hz analysis cadence and removes the entire class of one-frame
 * accidents — a passing truck, a shutter, a hand crossing the lens.
 *
 * UNJUDGEABLE does NOT reset a settled answer, it only fails to advance a pending one: a phone set
 * down on a desk goes unjudgeable forever, and forgetting a correct verdict because the operator
 * stopped moving would make the tag flicker exactly when nothing changed. A CONTRARY verdict does
 * reset, immediately and to zero — that is the operator toggling the converter.
 */
internal data class MotionInversionConfidence(
    val settled: MotionAgreement = MotionAgreement.UNJUDGEABLE,
    val pending: MotionAgreement = MotionAgreement.UNJUDGEABLE,
    val streak: Int = 0,
) {
    /** True once a real MATCHES/INVERTED answer has been confirmed and may be acted on. */
    val confident: Boolean get() = settled != MotionAgreement.UNJUDGEABLE

    fun observe(frame: MotionAgreement): MotionInversionConfidence = when {
        frame == MotionAgreement.UNJUDGEABLE -> this
        frame == pending -> {
            val next = streak + 1
            if (next >= MOTION_CONFIRM_FRAMES) {
                MotionInversionConfidence(settled = frame, pending = frame, streak = next)
            } else {
                copy(streak = next)
            }
        }
        // A verdict that contradicts what we were building drops the streak to this frame alone.
        else -> MotionInversionConfidence(settled = settled, pending = frame, streak = 1)
    }
}

/**
 * Rec.2020 luma plane from an RGBA8888 analysis snapshot — same weights and the same ROUNDING as
 * [computeFocusDetail], because this differences neighbours too and truncation's discontinuity at 0
 * would inject a spurious edge into near-black regions.
 */
internal fun motionLuma(bytes: ByteArray, w: Int, h: Int, out: IntArray) {
    var i = 0
    var p = 0
    val size = w * h
    while (p < size) {
        val r = bytes[i].toInt() and 0xFF
        val g = bytes[i + 1].toInt() and 0xFF
        val b = bytes[i + 2].toInt() and 0xFF
        out[p] = (0.2627f * r + 0.678f * g + 0.0593f * b).roundToInt().coerceIn(0, 255)
        p++
        i += 4
    }
}

/**
 * Compares two consecutive analysis luma planes against a gyro-predicted motion direction.
 *
 * [predictedX]/[predictedY] are the displacement the gyro expects the SCENE to undergo between the
 * two frames, in analysis-frame pixel coordinates (x right, y down), already carrying whatever
 * rotation the analysis draw applied. Only its direction is used; its magnitude gates judgeability
 * via [MOTION_MIN_PREDICTED_PX] and is otherwise discarded.
 *
 * Pure and allocation-light: runs on the analysis executor over bytes an existing readback already
 * produced, never on the GL thread.
 */
internal fun computeMotionInversion(
    previous: IntArray,
    current: IntArray,
    w: Int,
    h: Int,
    predictedX: Double,
    predictedY: Double,
): MotionInversionData {
    val size = w * h
    if (w <= 0 || h <= 0 || previous.size < size || current.size < size) {
        return MotionInversionData.UNJUDGED
    }
    val predictedLen = hypot(predictedX, predictedY)
    // Not enough rotation to have a direction worth testing. This is the common resting case.
    if (predictedLen < MOTION_MIN_PREDICTED_PX) return MotionInversionData.UNJUDGED

    val ux = predictedX / predictedLen
    val uy = predictedY / predictedLen

    // Blocks must stay inside the frame at every offset, so inset the grid by the full search range.
    val usableW = w - 2 * MOTION_SEARCH
    val usableH = h - 2 * MOTION_SEARCH
    if (usableW < MOTION_BLOCK || usableH < MOTION_BLOCK) return MotionInversionData.UNJUDGED
    val blocksX = usableW / MOTION_BLOCK
    val blocksY = usableH / MOTION_BLOCK
    val totalBlocks = blocksX * blocksY
    // Centre the grid so the dropped remainder is shared between opposite edges.
    val originX = MOTION_SEARCH + (usableW - blocksX * MOTION_BLOCK) / 2
    val originY = MOTION_SEARCH + (usableH - blocksY * MOTION_BLOCK) / 2

    // Step along the axis in whole pixels; the unit vector is scaled per offset and rounded.
    var voting = 0
    var agree = 0
    var oppose = 0

    var by = 0
    while (by < blocksY) {
        var bx = 0
        while (bx < blocksX) {
            val x0 = originX + bx * MOTION_BLOCK
            val y0 = originY + by * MOTION_BLOCK

            if (motionDirectionalTexture(current, w, x0, y0, ux, uy) >= MOTION_TEXTURE_FLOOR) {
                var stationary = -1L
                var bestPos = Long.MAX_VALUE
                var bestNeg = Long.MAX_VALUE

                // Only the SIDE of the minimum is kept, never which offset produced it: reading the
                // magnitude back would re-introduce every scale term this design exists to avoid.
                var s = -MOTION_SEARCH
                while (s <= MOTION_SEARCH) {
                    val dx = (s * ux).roundToInt()
                    val dy = (s * uy).roundToInt()
                    val sad = motionBlockSad(previous, current, w, h, x0, y0, dx, dy)
                    when {
                        s == 0 -> stationary = sad
                        // |s| below the stillness floor is neither evidence for nor against.
                        abs(s) < MOTION_MIN_SHIFT -> Unit
                        s > 0 -> if (sad < bestPos) bestPos = sad
                        else -> if (sad < bestNeg) bestNeg = sad
                    }
                    s++
                }

                val decisive = stationary >= 0 &&
                    motionBlockDecisive(
                        bestPositive = bestPos.takeIf { it != Long.MAX_VALUE },
                        bestNegative = bestNeg.takeIf { it != Long.MAX_VALUE },
                        stationary = stationary,
                    )
                if (decisive) {
                    voting++
                    if (bestPos <= bestNeg) agree++ else oppose++
                }
            }
            bx++
        }
        by++
    }

    return MotionInversionData(
        verdict = motionFrameVerdict(voting, agree, oppose),
        totalBlocks = totalBlocks,
        votingBlocks = voting,
        agreeVotes = agree,
        opposeVotes = oppose,
    )
}

/**
 * Mean absolute first difference along the search axis inside one block of [luma].
 *
 * Measured on the CURRENT frame only: it asks whether this block could localise a shift along this
 * axis at all, which is a property of the content, not of the pair.
 */
internal fun motionDirectionalTexture(
    luma: IntArray,
    w: Int,
    x0: Int,
    y0: Int,
    ux: Double,
    uy: Double,
): Double {
    // One-pixel step along the axis, rounded away from zero on the dominant component so a
    // near-axis-aligned direction still produces a real offset rather than (0, 0).
    val sx = if (abs(ux) >= abs(uy)) (if (ux >= 0) 1 else -1) else (ux.roundToInt())
    val sy = if (abs(uy) > abs(ux)) (if (uy >= 0) 1 else -1) else (uy.roundToInt())
    if (sx == 0 && sy == 0) return 0.0

    var total = 0L
    var n = 0
    var y = 0
    while (y < MOTION_BLOCK) {
        var x = 0
        while (x < MOTION_BLOCK) {
            val px = x0 + x
            val py = y0 + y
            val a = luma[py * w + px]
            val b = luma[(py + sy) * w + (px + sx)]
            total += abs(a - b).toLong()
            n++
            x += MOTION_SAMPLE_STEP
        }
        y += MOTION_SAMPLE_STEP
    }
    return if (n == 0) 0.0 else total.toDouble() / n
}

/**
 * Sum of absolute differences between the block in [current] and the same block in [previous]
 * displaced by ([dx], [dy]). Callers guarantee the displaced read stays in bounds via the
 * [MOTION_SEARCH] inset.
 */
internal fun motionBlockSad(
    previous: IntArray,
    current: IntArray,
    w: Int,
    h: Int,
    x0: Int,
    y0: Int,
    dx: Int,
    dy: Int,
): Long {
    var total = 0L
    var y = 0
    while (y < MOTION_BLOCK) {
        var x = 0
        while (x < MOTION_BLOCK) {
            val px = x0 + x
            val py = y0 + y
            val qx = px - dx
            val qy = py - dy
            if (qx in 0 until w && qy in 0 until h) {
                total += abs(current[py * w + px] - previous[qy * w + qx]).toLong()
            }
            x += MOTION_SAMPLE_STEP
        }
        y += MOTION_SAMPLE_STEP
    }
    return total
}
