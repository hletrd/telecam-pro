package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [CameraSelector2.pickBest] — the pure lens-selection logic. [TeleSelection] is a
 * plain data class (no Android types), so this runs on the JVM without a CameraManager.
 * This guards the single most HAL-safety-critical decision in the app (CLAUDE.md "Hard-won facts").
 */
class CameraSelector2Test {

    private fun sel(logical: String, physical: String?, equiv: Float) = TeleSelection(logical, physical, equiv)

    @Test
    fun `picks the candidate closest to 70mm, not the longest lens`() {
        val candidates = listOf(
            sel("0", null, 24f),   // ultra-wide
            sel("1", null, 70f),   // the 3x periscope the teleconverter mounts on
            sel("2", null, 230f),  // 10x periscope (longest — must NOT be chosen)
        )
        assertEquals(70f, CameraSelector2.pickBest(candidates)!!.equivFocalMm)
    }

    @Test
    fun `on a tie, prefers the standalone camera (physicalId == null) over a physical sub-camera`() {
        val candidates = listOf(
            sel("0", "4", 70f),   // tele reached via logical-multicamera physical routing (crashes HAL)
            sel("4", null, 70f),  // standalone tele id
        )
        val best = CameraSelector2.pickBest(candidates)!!
        assertEquals("4", best.logicalId)
        assertNull(best.physicalId)
    }

    @Test
    fun `tie-break holds regardless of list order`() {
        val candidates = listOf(
            sel("4", null, 70f),
            sel("0", "4", 70f),
        )
        assertNull(CameraSelector2.pickBest(candidates)!!.physicalId)
    }

    @Test
    fun `excludes candidates with non-positive equiv focal even if numerically closest`() {
        val candidates = listOf(
            sel("9", null, 0f),   // unreadable focal — must be filtered out
            sel("1", null, 70f),
        )
        assertEquals("1", CameraSelector2.pickBest(candidates)!!.logicalId)
    }

    @Test
    fun `returns null for an empty candidate list`() {
        assertNull(CameraSelector2.pickBest(emptyList()))
    }

    @Test
    fun `returns the sole candidate even if far from 70mm`() {
        val candidates = listOf(sel("0", null, 24f))
        assertEquals("0", CameraSelector2.pickBest(candidates)!!.logicalId)
    }

    @Test
    fun `falls back to the first candidate when all have non-positive focal`() {
        val candidates = listOf(sel("a", null, 0f), sel("b", null, -1f))
        // filter removes both -> firstOrNull() fallback keeps the app from selecting nothing.
        assertEquals("a", CameraSelector2.pickBest(candidates)!!.logicalId)
    }
}
