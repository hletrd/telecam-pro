package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `all invalid focal metadata falls back only to a standalone candidate`() {
        val candidates = listOf(sel("logical", "4", 0f), sel("safe", null, -1f))
        assertEquals("safe", CameraSelector2.pickBest(candidates)!!.logicalId)
        assertNull(CameraSelector2.pickBest(candidates)!!.physicalId)
    }

    @Test
    fun `all invalid routed candidates fail closed`() {
        val candidates = listOf(sel("logical", "4", 0f), sel("logical", "5", -1f))
        assertNull(CameraSelector2.pickBest(candidates))
    }

    // ---- pickClosest: the lens-switcher resolver (UW / main / 3× / 10×) ----

    private val fourLenses = listOf(
        sel("3", null, 14f),   // ultra-wide
        sel("2", null, 23f),   // main
        sel("4", null, 70f),   // 3× periscope (teleconverter lens)
        sel("5", null, 230f),  // 10× periscope
    )

    @Test
    fun `pickClosest resolves each lens choice to its nearest camera`() {
        assertEquals("3", CameraSelector2.pickClosest(fourLenses, LensChoice.ULTRAWIDE.targetEquivMm)!!.logicalId)
        assertEquals("2", CameraSelector2.pickClosest(fourLenses, LensChoice.MAIN.targetEquivMm)!!.logicalId)
        assertEquals("4", CameraSelector2.pickClosest(fourLenses, LensChoice.TELE3X.targetEquivMm)!!.logicalId)
        assertEquals("5", CameraSelector2.pickClosest(fourLenses, LensChoice.TELE10X.targetEquivMm)!!.logicalId)
    }

    @Test
    fun `pickClosest prefers the standalone id when a physical route ties on focal`() {
        val candidates = listOf(sel("0", "5", 230f), sel("5", null, 230f))
        assertNull(CameraSelector2.pickClosest(candidates, LensChoice.TELE10X.targetEquivMm)!!.physicalId)
    }

    @Test
    fun `every lens choice targets a distinct focal so they never collapse`() {
        val targets = LensChoice.entries.map { it.targetEquivMm }
        assertEquals(targets.size, targets.toSet().size)
    }

    // ---- pickFrontBest: the front (selfie) camera resolver ----

    private fun front(id: String, logical: Boolean, area: Long) = FrontCandidate(id, logical, area)

    @Test
    fun `front pick prefers a plain id over a logical composite even with a smaller array`() {
        // Same discipline as pickBest's standalone preference: a plain front id opens safely;
        // a logical composite is only the fallback.
        val best = CameraSelector2.pickFrontBest(
            listOf(front("7", logical = true, area = 50_000_000), front("1", logical = false, area = 12_000_000)),
        )!!
        assertEquals("1", best.id)
    }

    @Test
    fun `front pick takes the largest active array among plain ids`() {
        val best = CameraSelector2.pickFrontBest(
            listOf(front("6", logical = false, area = 8_000_000), front("1", logical = false, area = 12_582_912)),
        )!!
        assertEquals("1", best.id)
    }

    @Test
    fun `front pick falls back to a logical front when it is the only candidate`() {
        // A logical front is still opened PLAINLY (never physical routing), which is safe.
        val best = CameraSelector2.pickFrontBest(listOf(front("7", logical = true, area = 0)))!!
        assertEquals("7", best.id)
    }

    @Test
    fun `front pick is deterministic on a full tie`() {
        val tied = listOf(front("9", logical = false, area = 12_000_000), front("1", logical = false, area = 12_000_000))
        assertEquals("1", CameraSelector2.pickFrontBest(tied)!!.id)
        assertEquals("1", CameraSelector2.pickFrontBest(tied.reversed())!!.id)
    }

    @Test
    fun `front pick returns null when the device exposes no front camera`() {
        assertNull(CameraSelector2.pickFrontBest(emptyList()))
    }

    @Test
    fun `startup route inventory prefers rear then front then external`() {
        val all = cameraRouteInventoryOf(CameraRoute.entries)
        assertEquals(CameraRoute.BACK, all.initialRoute())
        assertTrue(all.switchAvailable)

        val frontExternal = cameraRouteInventoryOf(
            listOf(CameraRoute.FRONT, CameraRoute.EXTERNAL),
        )
        assertEquals(CameraRoute.FRONT, frontExternal.initialRoute())
        assertTrue(frontExternal.switchAvailable)

        val externalOnly = cameraRouteInventoryOf(listOf(CameraRoute.EXTERNAL))
        assertEquals(CameraRoute.EXTERNAL, externalOnly.initialRoute())
        assertFalse(externalOnly.switchAvailable)
    }

    @Test
    fun `every route inventory matrix has an honest startup and switch projection`() {
        assertFalse(CameraRouteInventory.UNKNOWN.complete)
        for (mask in 0 until 8) {
            val routes = CameraRoute.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            val inventory = cameraRouteInventoryOf(routes)
            assertTrue(inventory.complete)
            assertEquals(routes.isNotEmpty(), inventory.any)
            assertEquals(
                CameraRoute.BACK.takeIf { CameraRoute.BACK in routes }
                    ?: CameraRoute.FRONT.takeIf { CameraRoute.FRONT in routes }
                    ?: CameraRoute.EXTERNAL.takeIf { CameraRoute.EXTERNAL in routes },
                inventory.initialRoute(),
            )
            assertEquals(
                CameraRoute.FRONT in routes &&
                    (CameraRoute.BACK in routes || CameraRoute.EXTERNAL in routes),
                inventory.switchAvailable,
            )
        }
    }
}
