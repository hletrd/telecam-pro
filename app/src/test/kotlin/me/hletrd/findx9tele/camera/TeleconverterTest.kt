package me.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeleconverterTest {

    @Test
    fun `explorer profile keeps the historical magnification`() {
        assertEquals(300f / 70f, TeleconverterProfile.EXPLORER_300.magnification, 1e-6f)
        assertEquals(TELECONVERTER_MAGNIFICATION, TeleconverterProfile.EXPLORER_300.magnification, 1e-6f)
        assertEquals(DEFAULT_TELECONVERTER_PROFILE, TeleconverterProfile.EXPLORER_300)
    }

    @Test
    fun `only custom is custom`() {
        assertTrue(TeleconverterProfile.CUSTOM.isCustom)
        TeleconverterProfile.entries.filter { it != TeleconverterProfile.CUSTOM }
            .forEach { assertFalse("${it.name} must not report custom", it.isCustom) }
    }

    @Test
    fun `effective magnification prefers the profile over the custom value`() {
        // A stale custom number must not leak into a fixed profile.
        assertEquals(
            300f / 70f,
            effectiveMagnification(TeleconverterProfile.EXPLORER_300, custom = 9f),
            1e-6f,
        )
        assertEquals(2f, effectiveMagnification(TeleconverterProfile.GENERIC_2, custom = 9f), 1e-6f)
    }

    @Test
    fun `custom profile uses the user value, normalized`() {
        assertEquals(2.35f, effectiveMagnification(TeleconverterProfile.CUSTOM, 2.35f), 1e-6f)
        assertEquals(
            MAX_TELECONVERTER_MAGNIFICATION,
            effectiveMagnification(TeleconverterProfile.CUSTOM, 999f),
            1e-6f,
        )
        assertEquals(
            MIN_TELECONVERTER_MAGNIFICATION,
            effectiveMagnification(TeleconverterProfile.CUSTOM, 0.2f),
            1e-6f,
        )
    }

    @Test
    fun `normalize maps non-finite input to the default rather than clamping it`() {
        // A clamp would silently turn NaN into the floor; a corrupt persisted value should come
        // back as the optic the app was built around instead.
        assertEquals(TELECONVERTER_MAGNIFICATION, normalizeMagnification(Float.NaN), 1e-6f)
        assertEquals(TELECONVERTER_MAGNIFICATION, normalizeMagnification(Float.POSITIVE_INFINITY), 1e-6f)
        assertEquals(TELECONVERTER_MAGNIFICATION, normalizeMagnification(Float.NEGATIVE_INFINITY), 1e-6f)
    }

    @Test
    fun `normalize is idempotent and in range`() {
        listOf(-5f, 0f, 1f, 1.1f, 4.286f, 10f, 50f).forEach { raw ->
            val once = normalizeMagnification(raw)
            assertEquals(once, normalizeMagnification(once), 1e-6f)
            assertTrue(once in MIN_TELECONVERTER_MAGNIFICATION..MAX_TELECONVERTER_MAGNIFICATION)
        }
    }

    @Test
    fun `detects the kit converter for both Find X9 Ultra model names`() {
        assertEquals(TeleconverterProfile.EXPLORER_300, detectProfile("PMA110"))
        assertEquals(TeleconverterProfile.EXPLORER_300, detectProfile("CPH2841"))
        assertEquals(TeleconverterProfile.EXPLORER_300, detectProfile(" pma110 "))
    }

    @Test
    fun `each first-party preset reproduces its manufacturer's published magnification`() {
        // Every kit magnification is written as (converter focal / host tele focal); this pins that
        // arithmetic against the number the maker actually prints, so a typo in either factor fails
        // here instead of silently mislabelling a focal length in the OSD and the EXIF.
        //
        // The tolerance is 1e-2, NOT half of the printed last digit: makers TRUNCATE rather than
        // round. 230/70 = 3.2857 is sold as "3.28×" (a rounded figure would print 3.29) and
        // 400/85 = 4.7059 as "4.7×", both of which sit just outside a ±5e-3 band. One digit of the
        // printed value is all the published figure can honestly pin — and it is still two orders
        // of magnitude tighter than any plausible typo in either factor.
        assertEquals(4.286f, TeleconverterProfile.EXPLORER_300.magnification, 1e-2f)
        assertEquals(3.28f, TeleconverterProfile.HASSELBLAD_230.magnification, 1e-2f)
        assertEquals(2.35f, TeleconverterProfile.ZEISS_200.magnification, 1e-2f)
        assertEquals(4.7f, TeleconverterProfile.ZEISS_400.magnification, 1e-2f)
        // The factors themselves, so a preset that silently adopted a SIBLING's ratio (which the
        // published-figure check above could not see) fails too.
        assertEquals(300f / 70f, TeleconverterProfile.EXPLORER_300.magnification, 0f)
        assertEquals(230f / 70f, TeleconverterProfile.HASSELBLAD_230.magnification, 0f)
        assertEquals(200f / 85f, TeleconverterProfile.ZEISS_200.magnification, 0f)
        assertEquals(400f / 85f, TeleconverterProfile.ZEISS_400.magnification, 0f)
    }

    @Test
    fun `first-party presets name their kit and generics do not`() {
        listOf(
            TeleconverterProfile.EXPLORER_300,
            TeleconverterProfile.HASSELBLAD_230,
            TeleconverterProfile.ZEISS_200,
            TeleconverterProfile.ZEISS_400,
        ).forEach { assertTrue("${it.name} must name its kit", it.kit.isNotEmpty()) }
        listOf(
            TeleconverterProfile.GENERIC_1_5,
            TeleconverterProfile.GENERIC_2,
            TeleconverterProfile.GENERIC_3,
            TeleconverterProfile.CUSTOM,
        ).forEach { assertTrue("${it.name} must claim no kit", it.kit.isEmpty()) }
    }

    @Test
    fun `detects each first-party kit from its phone`() {
        assertEquals(TeleconverterProfile.HASSELBLAD_230, detectProfile("CPH2791"))
        assertEquals(TeleconverterProfile.ZEISS_200, detectProfile("V2454A"))
        assertEquals(TeleconverterProfile.ZEISS_200, detectProfile("V2454DA"))
        // The X300 Ultra takes two official converters; the BASE kit must win the default.
        assertEquals(TeleconverterProfile.ZEISS_200, detectProfile("V2562"))
    }

    @Test
    fun `no two profiles claim the same phone`() {
        // detectProfile returns the FIRST match, so an overlap would make the default depend on
        // declaration order — exactly the kind of silent coupling that survives review.
        val claims = TeleconverterProfile.entries.flatMap { profile ->
            profile.deviceModels.map { it.lowercase() }
        }
        assertEquals(claims.size, claims.toSet().size)
    }

    @Test
    fun `the long vivo optic is never auto-selected`() {
        assertTrue(TeleconverterProfile.ZEISS_400.deviceModels.isEmpty())
        assertNull(TeleconverterProfile.entries.firstOrNull { it == TeleconverterProfile.ZEISS_400 }
            ?.deviceModels?.firstOrNull())
    }

    @Test
    fun `a foreign converter reports the focal it actually delivers on this phone`() {
        // The label says "ZEISS 200" because that is how the optic is sold, but it was computed for
        // an 85 mm host; on this phone's 70 mm periscope it is 165 mm, and the UI must say so.
        val onThisPhone = effectiveFocalMm(TeleconverterProfile.ZEISS_200.magnification)
        assertEquals(165f, onThisPhone, 0.5f)
        assertTrue("must not repeat the product's own number", onThisPhone < 200f)
    }

    @Test
    fun `every catalog magnification is inside the custom bounds`() {
        // A preset outside the bounds would be unreachable by the custom ruler and would break the
        // round-trip through normalizeMagnification on restore.
        TeleconverterProfile.entries.filter { !it.isCustom }.forEach {
            assertEquals(
                "${it.name} must survive normalization unchanged",
                it.magnification,
                normalizeMagnification(it.magnification),
                1e-6f,
            )
        }
    }

    @Test
    fun `detection is null for unknown, blank, and missing models`() {
        assertNull(detectProfile("SM-S928B"))
        assertNull(detectProfile(""))
        assertNull(detectProfile("   "))
        assertNull(detectProfile(null))
    }

    @Test
    fun `generic and custom profiles are never device-detected`() {
        // Otherwise a generic entry could shadow the kit optic for the device it ships with.
        listOf(
            TeleconverterProfile.GENERIC_1_5,
            TeleconverterProfile.GENERIC_2,
            TeleconverterProfile.GENERIC_3,
            TeleconverterProfile.CUSTOM,
        ).forEach { assertTrue("${it.name} must claim no device", it.deviceModels.isEmpty()) }
    }

    @Test
    fun `effective focal reproduces the nominal 300 mm at the kit magnification`() {
        assertEquals(300f, effectiveFocalMm(TELECONVERTER_MAGNIFICATION), 1e-3f)
        assertEquals(140f, effectiveFocalMm(2f), 1e-3f)
        assertEquals(70f, effectiveFocalMm(1f), 1e-3f)
    }

    @Test
    fun `tele display base reproduces the historical 13x at the kit magnification`() {
        val base = teleDisplayBase(TELECONVERTER_MAGNIFICATION)
        assertEquals((70f / 23f) * (300f / 70f), base, 1e-4f)
        assertEquals(300f / 23f, base, 1e-4f)
    }

    @Test
    fun `a weaker converter earns a higher local zoom ceiling under the fixed display cap`() {
        // The 60x ceiling is a cap on TOTAL magnification, so the digital headroom moves inversely.
        val kitCeiling = TELE_MAX_DISPLAY_ZOOM / teleDisplayBase(TELECONVERTER_MAGNIFICATION)
        val weakCeiling = TELE_MAX_DISPLAY_ZOOM / teleDisplayBase(2f)
        assertTrue("weaker converter should allow more digital zoom", weakCeiling > kitCeiling)
        assertEquals(60f / (300f / 23f), kitCeiling, 1e-4f)
    }

    @Test
    fun `display base scales linearly with magnification`() {
        assertEquals(2f * teleDisplayBase(2f), teleDisplayBase(4f), 1e-4f)
    }
}
