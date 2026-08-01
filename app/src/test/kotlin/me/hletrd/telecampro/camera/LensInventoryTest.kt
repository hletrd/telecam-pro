package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail must be ENUMERATED, not hardcoded (user-reported 2026-08-02): a single-camera Android 16
 * tablet was offering 0.6x and 10x — one below its advertised zoom floor of 1.0, the other above its
 * 8.0 ceiling — and tapping 0.6x left the wire zoom at 1.0. PMA110's own inventory must be
 * unchanged, which is what makes this safe to ship.
 */
class LensInventoryTest {

    // PMA110: ultrawide / main / 3x periscope / 10x periscope, logical camera spanning 0.6-20.
    private val pma110Lenses = listOf(14f, 23f, 70f, 230f)

    // Lenovo TB336ZU (device-measured): ONE back lens at 26 mm equivalent, zoom range 1.0-8.0.
    private val tabletLenses = listOf(26f)

    @Test
    fun `PMA110 keeps all four presets, every one of them optical`() {
        val inv = lensInventoryOf(pma110Lenses, 0.6f to 20f)
        assertEquals(LensChoice.entries.toSet(), inv.available)
        assertEquals(LensChoice.entries.toSet(), inv.optical)
    }

    @Test
    fun `a single-camera tablet drops the presets it cannot reach`() {
        val inv = lensInventoryOf(tabletLenses, 1f to 8f)
        assertFalse("0.6x is below the 1.0 zoom floor", LensChoice.ULTRAWIDE in inv.available)
        assertFalse("10x is above the 8.0 zoom ceiling", LensChoice.TELE10X in inv.available)
        assertTrue(LensChoice.MAIN in inv.available)
        // 3x IS reachable on this device — as digital zoom, so it must not be spoken as a lens.
        assertTrue(LensChoice.TELE3X in inv.available)
        assertFalse(LensChoice.TELE3X in inv.optical)
        assertTrue(LensChoice.MAIN in inv.optical)
    }

    @Test
    fun `a real periscope counts as optical across the tolerance band`() {
        // Real "3x" lenses land anywhere from ~65 to ~85 mm; all of them must match TELE3X.
        for (equiv in listOf(65f, 70f, 80f, 85f)) {
            val inv = lensInventoryOf(listOf(23f, equiv), 1f to 5f)
            assertTrue("$equiv mm should read as the 3x lens", LensChoice.TELE3X in inv.optical)
        }
        // A 2x (46 mm) lens is NOT a 3x: it stays out of the optical set.
        assertFalse(LensChoice.TELE3X in lensInventoryOf(listOf(23f, 46f), 1f to 5f).optical)
    }

    @Test
    fun `an ultrawide-only-by-zoom device still offers 0-6x when the range reaches it`() {
        // Some phones advertise a sub-1.0 zoom floor on the logical camera without exposing the
        // ultrawide as a separate id; the preset is genuinely reachable there.
        val inv = lensInventoryOf(listOf(24f), 0.5f to 10f)
        assertTrue(LensChoice.ULTRAWIDE in inv.available)
        assertFalse(LensChoice.ULTRAWIDE in inv.optical)
    }

    @Test
    fun `an unreadable enumeration degrades to the full set rather than an empty rail`() {
        assertEquals(LensInventory.ALL, lensInventoryOf(emptyList(), null))
        // Zero/negative equivalents are unreadable characteristics, not lenses.
        assertFalse(LensChoice.TELE10X in lensInventoryOf(listOf(0f, -1f), 1f to 4f).available)
    }
}
