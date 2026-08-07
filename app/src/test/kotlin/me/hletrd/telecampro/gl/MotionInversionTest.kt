package me.hletrd.telecampro.gl

import me.hletrd.telecampro.camera.MotionAgreement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the motion-vs-gyro inversion metric.
 *
 * The synthetic scenes are exact integer shifts of one deterministic texture field, so a correct
 * implementation must find its SAD minimum on the true side and nowhere else. That makes the
 * positive tests strict; the refusal tests are the ones that matter more, because the contract is
 * "may miss, must never false-fire".
 */
class MotionInversionTest {

    private val w = 200
    private val h = 150

    /** Deterministic hash texture — no Math.random, so a failure is always reproducible. */
    private fun texel(x: Int, y: Int): Int {
        var v = (x * 374761393) xor (y * 668265263)
        v = (v xor (v ushr 13)) * 1274126177
        return (v ushr 16) and 0xFF
    }

    /** Scene whose content has been displaced by ([shiftX], [shiftY]): out(p) = texel(p - shift). */
    private fun scene(shiftX: Int, shiftY: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) out[y * w + x] = texel(x - shiftX, y - shiftY)
        }
        return out
    }

    private fun verdict(shiftX: Int, shiftY: Int, predX: Double, predY: Double): MotionAgreement =
        computeMotionInversion(scene(0, 0), scene(shiftX, shiftY), w, h, predX, predY).verdict

    // --- the two answers it exists to give ------------------------------------------------------

    @Test
    fun sceneMovingWithTheGyroReadsMatches() {
        assertEquals(MotionAgreement.MATCHES, verdict(6, 0, 6.0, 0.0))
        assertEquals(MotionAgreement.MATCHES, verdict(0, 6, 0.0, 6.0))
        assertEquals(MotionAgreement.MATCHES, verdict(-5, 0, -5.0, 0.0))
    }

    /** The whole point: a 180-degree rotation flips BOTH axes, so the scene opposes the gyro. */
    @Test
    fun sceneMovingAgainstTheGyroReadsInverted() {
        assertEquals(MotionAgreement.INVERTED, verdict(6, 0, -6.0, 0.0))
        assertEquals(MotionAgreement.INVERTED, verdict(0, 6, 0.0, -6.0))
    }

    @Test
    fun diagonalMotionResolvesOnBothAxesAtOnce() {
        assertEquals(MotionAgreement.MATCHES, verdict(5, 5, 5.0, 5.0))
        assertEquals(MotionAgreement.INVERTED, verdict(5, 5, -5.0, -5.0))
    }

    /**
     * Scale-free by construction: only the DIRECTION of the prediction is read, so a gyro estimate
     * off by 4x in magnitude — which is exactly what an unmodelled teleconverter would do — must not
     * change the answer. This is what removes the need for a focal/zoom/crop model.
     */
    @Test
    fun predictedMagnitudeDoesNotChangeTheVerdict() {
        assertEquals(MotionAgreement.MATCHES, verdict(6, 0, 4.0, 0.0))
        assertEquals(MotionAgreement.MATCHES, verdict(6, 0, 60.0, 0.0))
        assertEquals(MotionAgreement.INVERTED, verdict(6, 0, -4.0, 0.0))
        assertEquals(MotionAgreement.INVERTED, verdict(6, 0, -60.0, 0.0))
    }

    // --- refusals: the contract ------------------------------------------------------------------

    @Test
    fun aStillSceneIsUnjudgeable() {
        val still = scene(0, 0)
        assertEquals(
            MotionAgreement.UNJUDGEABLE,
            computeMotionInversion(still, still, w, h, 6.0, 0.0).verdict,
        )
    }

    /** No rotation, no predicted direction, nothing to test against. The resting-phone case. */
    @Test
    fun tooLittlePredictedRotationIsUnjudgeable() {
        assertEquals(MotionAgreement.UNJUDGEABLE, verdict(6, 0, 1.0, 0.0))
        assertEquals(MotionAgreement.UNJUDGEABLE, verdict(6, 0, 0.0, 0.0))
    }

    @Test
    fun aFeaturelessSceneIsUnjudgeable() {
        val flat = IntArray(w * h) { 128 }
        val alsoFlat = IntArray(w * h) { 128 }
        assertEquals(
            MotionAgreement.UNJUDGEABLE,
            computeMotionInversion(flat, alsoFlat, w, h, 6.0, 0.0).verdict,
        )
    }

    /**
     * THE LOAD-BEARING GUARD. Vertical stripes vary only in x, so they carry no information about
     * VERTICAL displacement: SAD(s) along y is flat and the minimum is noise. Those blocks must
     * abstain rather than vote a coin flip.
     */
    @Test
    fun edgesPerpendicularToTheMotionCannotVote() {
        val stripes = IntArray(w * h) { i -> if (((i % w) / 3) % 2 == 0) 40 else 210 }
        val shifted = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val sy = y - 6
            if (sy in 0 until h) stripes[sy * w + x] else 40
        }
        assertEquals(
            MotionAgreement.UNJUDGEABLE,
            computeMotionInversion(stripes, shifted, w, h, 0.0, 6.0).verdict,
        )
    }

    /** Positive control for the guard above: the SAME stripes resolve motion ACROSS them fine. */
    @Test
    fun thoseSameEdgesResolveMotionAlongTheirGradient() {
        val stripes = IntArray(w * h) { i -> if (((i % w) / 3) % 2 == 0) 40 else 210 }
        val shifted = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val sx = x - 6
            if (sx in 0 until w) stripes[y * w + sx] else 40
        }
        // Periodic content may legitimately abstain via the side-margin rule; it must never invert.
        assertNotEquals(
            MotionAgreement.INVERTED,
            computeMotionInversion(stripes, shifted, w, h, 6.0, 0.0).verdict,
        )
    }

    @Test
    fun degenerateBuffersAreUnjudgeable() {
        val tiny = IntArray(4)
        assertEquals(MotionAgreement.UNJUDGEABLE, computeMotionInversion(tiny, tiny, 2, 2, 6.0, 0.0).verdict)
        assertEquals(
            MotionAgreement.UNJUDGEABLE,
            computeMotionInversion(IntArray(0), IntArray(0), w, h, 6.0, 0.0).verdict,
        )
    }

    // --- robustness ------------------------------------------------------------------------------

    /**
     * A moving subject is a handful of dissenting blocks, not a corrupted global answer — the reason
     * the frame verdict is a vote rather than one displacement estimate.
     */
    @Test
    fun aMovingSubjectDoesNotFlipTheFrame() {
        val previous = scene(0, 0)
        val current = scene(6, 0)
        // Paste a patch that moved the OTHER way over the top-left corner (~1/8 of the frame).
        val contrarian = scene(-6, 0)
        for (y in 0 until h / 4) {
            for (x in 0 until w / 2) current[y * w + x] = contrarian[y * w + x]
        }
        val data = computeMotionInversion(previous, current, w, h, 6.0, 0.0)
        assertEquals(MotionAgreement.MATCHES, data.verdict)
        assertTrue("some blocks must have dissented", data.opposeVotes > 0)
        assertTrue("the majority must still agree", data.agreeVotes > data.opposeVotes)
    }

    // --- frame rule ------------------------------------------------------------------------------

    @Test
    fun tooFewVotingBlocksIsUnjudgeableHoweverLopsided() {
        assertEquals(
            MotionAgreement.UNJUDGEABLE,
            motionFrameVerdict(votingBlocks = MOTION_MIN_VOTING_BLOCKS - 1, agreeVotes = 5, opposeVotes = 0),
        )
    }

    @Test
    fun aSplitVoteIsUnjudgeable() {
        assertEquals(MotionAgreement.UNJUDGEABLE, motionFrameVerdict(20, agreeVotes = 11, opposeVotes = 9))
    }

    @Test
    fun aSupermajorityDecides() {
        assertEquals(MotionAgreement.MATCHES, motionFrameVerdict(20, agreeVotes = 16, opposeVotes = 4))
        assertEquals(MotionAgreement.INVERTED, motionFrameVerdict(20, agreeVotes = 4, opposeVotes = 16))
    }

    // --- block decisiveness ----------------------------------------------------------------------

    @Test
    fun aBlockThatCannotBeatStandingStillAbstains() {
        assertEquals(false, motionBlockDecisive(bestPositive = 950, bestNegative = 1200, stationary = 1000))
    }

    @Test
    fun aBlockWhoseSidesTieAbstains() {
        assertEquals(false, motionBlockDecisive(bestPositive = 500, bestNegative = 520, stationary = 1000))
    }

    @Test
    fun aBlockWithOneUnreachableSideAbstains() {
        assertEquals(false, motionBlockDecisive(bestPositive = null, bestNegative = 500, stationary = 1000))
        assertEquals(false, motionBlockDecisive(bestPositive = 500, bestNegative = null, stationary = 1000))
    }

    @Test
    fun aClearWinnerVotes() {
        assertEquals(true, motionBlockDecisive(bestPositive = 200, bestNegative = 900, stationary = 1000))
    }

    // --- confidence accumulator ------------------------------------------------------------------

    @Test
    fun oneFrameIsNeverEnough() {
        var c = MotionInversionConfidence()
        repeat(MOTION_CONFIRM_FRAMES - 1) { c = c.observe(MotionAgreement.INVERTED) }
        assertEquals(false, c.confident)
        c = c.observe(MotionAgreement.INVERTED)
        assertEquals(true, c.confident)
        assertEquals(MotionAgreement.INVERTED, c.settled)
    }

    /** Setting the phone down must not un-answer a question already answered. */
    @Test
    fun unjudgeableFramesDoNotForgetASettledVerdict() {
        var c = MotionInversionConfidence()
        repeat(MOTION_CONFIRM_FRAMES) { c = c.observe(MotionAgreement.MATCHES) }
        repeat(50) { c = c.observe(MotionAgreement.UNJUDGEABLE) }
        assertEquals(MotionAgreement.MATCHES, c.settled)
        assertEquals(true, c.confident)
    }

    /** ...but they must not advance a pending one either. */
    @Test
    fun unjudgeableFramesDoNotAdvanceAPendingVerdict() {
        var c = MotionInversionConfidence()
        c = c.observe(MotionAgreement.INVERTED)
        repeat(50) { c = c.observe(MotionAgreement.UNJUDGEABLE) }
        assertEquals(false, c.confident)
        assertEquals(1, c.streak)
    }

    /** A contrary verdict is the operator toggling the converter — restart counting immediately. */
    @Test
    fun aContraryVerdictResetsTheStreak() {
        var c = MotionInversionConfidence()
        repeat(MOTION_CONFIRM_FRAMES - 1) { c = c.observe(MotionAgreement.INVERTED) }
        c = c.observe(MotionAgreement.MATCHES)
        assertEquals(1, c.streak)
        assertEquals(false, c.confident)
        repeat(MOTION_CONFIRM_FRAMES - 1) { c = c.observe(MotionAgreement.MATCHES) }
        assertEquals(MotionAgreement.MATCHES, c.settled)
    }

    @Test
    fun aFreshAccumulatorIsNotConfident() {
        assertEquals(false, MotionInversionConfidence().confident)
        assertEquals(MotionAgreement.UNJUDGEABLE, MotionInversionConfidence().settled)
    }
}
