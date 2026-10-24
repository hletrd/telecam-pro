package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The converter setting is a PAIR — phone, then converter — and almost every assertion here exists
 * because one half can silently poison the other: a kit that does not clamp onto the selected phone,
 * a magnification derived from the wrong host focal, or a phone "detection" that is really just a
 * default. The optics math itself (magnification -> focal -> display base) is pinned alongside.
 */
class TeleconverterTest {

    // Every first-party kit: the entries bound to a phone. Derived, so a new kit cannot be left out of
    // the pair invariants below; the published-figure test still names each one explicitly.
    private val kits = TeleconverterProfile.entries.filter { it.phone != null }

    // Kits whose glass was designed for a telephoto OTHER than the phone they are offered on, with
    // that design focal: their magnification must come from it, not from the phone's own telephoto.
    private val designHostKits = mapOf(
        TeleconverterProfile.HASSELBLAD_X10 to 70f,
        TeleconverterProfile.HASSELBLAD_X9S_PRO to 70f,
        TeleconverterProfile.ZEISS_200_X500 to 85f,
        TeleconverterProfile.HONOR_G200_MAGIC9 to 85f,
    )

    private val fitsAnything = listOf(
        TeleconverterProfile.GENERIC_1_5,
        TeleconverterProfile.GENERIC_2,
        TeleconverterProfile.GENERIC_3,
        TeleconverterProfile.CUSTOM,
    )

    // ---- Magnification: the invariant that survives moving glass between bodies ----

    @Test
    fun `explorer profile keeps the historical magnification`() {
        assertEquals(300f / 70f, TeleconverterProfile.EXPLORER_300.magnification, 1e-6f)
        assertEquals(TELECONVERTER_MAGNIFICATION, TeleconverterProfile.EXPLORER_300.magnification, 1e-6f)
        assertEquals(DEFAULT_TELECONVERTER_PROFILE, TeleconverterProfile.EXPLORER_300)
    }

    @Test
    fun `each kit reproduces its manufacturer's published magnification`() {
        // Every kit magnification is (sold focal / ITS OWN host phone's tele focal); this pins that
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
        assertEquals(2.35f, TeleconverterProfile.ZEISS_200_X200.magnification, 1e-2f)
        assertEquals(2.35f, TeleconverterProfile.ZEISS_200_X300.magnification, 1e-2f)
        assertEquals(4.7f, TeleconverterProfile.ZEISS_400.magnification, 1e-2f)
        listOf(
            TeleconverterProfile.HASSELBLAD_230_X10_PRO_MAX,
            TeleconverterProfile.HASSELBLAD_X10,
            TeleconverterProfile.HASSELBLAD_X9S_PRO,
        ).forEach { assertEquals(it.name, 3.28f, it.magnification, 1e-2f) }
        listOf(
            TeleconverterProfile.ZEISS_200_X300_BASE,
            TeleconverterProfile.ZEISS_200_X300_PRO,
            TeleconverterProfile.ZEISS_200_X300S,
            TeleconverterProfile.ZEISS_200_X500,
            TeleconverterProfile.ZEISS_200_X500_PRO,
            TeleconverterProfile.ZEISS_200_X500_PRO_MAX,
            TeleconverterProfile.HONOR_200_MAGIC8_PRO,
            TeleconverterProfile.HONOR_200_MAGIC8_RSR,
            TeleconverterProfile.HONOR_G200_MAGIC9,
            TeleconverterProfile.HONOR_G200_MAGIC9_PRO_MAX,
        ).forEach { assertEquals(it.name, 2.35f, it.magnification, 1e-2f) }
        assertEquals(4.7f, TeleconverterProfile.ZEISS_400_X500_PRO_MAX.magnification, 1e-2f)
        // HONOR prints only focals; 500 / 85 is the derivation every kit here uses.
        assertEquals(5.88f, TeleconverterProfile.HONOR_G500.magnification, 1e-2f)
        // The exact ratios, so a kit that silently adopted a SIBLING's factors (which the
        // published-figure check above could not see) fails too.
        assertEquals(300f / 70f, TeleconverterProfile.EXPLORER_300.magnification, 0f)
        assertEquals(230f / 70f, TeleconverterProfile.HASSELBLAD_230.magnification, 0f)
        assertEquals(200f / 85f, TeleconverterProfile.ZEISS_200_X200.magnification, 0f)
        assertEquals(200f / 85f, TeleconverterProfile.ZEISS_200_X300.magnification, 0f)
        assertEquals(400f / 85f, TeleconverterProfile.ZEISS_400.magnification, 0f)
        assertEquals(400f / 85f, TeleconverterProfile.ZEISS_400_X500_PRO_MAX.magnification, 0f)
        assertEquals(500f / 85f, TeleconverterProfile.HONOR_G500.magnification, 0f)
    }

    @Test
    fun `a kit derives its magnification from its OWN host phone, never the selected one`() {
        // The glass is not reground when it is moved to another body. Both ZEISS entries are the
        // same 2.35x optic and must agree even though they are offered on different phones, and the
        // 85 mm-host entries must NOT come out as (sold focal / 70).
        assertEquals(
            TeleconverterProfile.ZEISS_200_X200.magnification,
            TeleconverterProfile.ZEISS_200_X300.magnification,
            0f,
        )
        assertFalse(
            "a ZEISS optic must not be divided by the Hasselblad host focal",
            TeleconverterProfile.ZEISS_200_X200.magnification == 200f / 70f,
        )
        kits.forEach { kit ->
            val host = kit.phone
            assertTrue("${kit.name} must name its host phone", host != null)
            val designHost = designHostKits[kit] ?: host!!.teleEquivMm
            assertEquals(kit.name, kit.soldFocalMm / designHost, kit.magnification, 0f)
        }
    }

    @Test
    fun `glass on a body it was not designed for reports its OPTICAL focal there`() {
        // The maker's printed figure on such a body includes a software crop (OPPO footnotes it; vivo
        // prints "200 mm-equivalent" on the 72 mm X500). The app reports magnification x the lens the
        // converter actually sits on, so these must NOT come out as the printed 253 / 200 / 200 mm.
        fun focalOn(kit: TeleconverterProfile) = effectiveFocalMm(kit.magnification, kit.phone!!.teleEquivMm)
        assertEquals(230f / 70f * 65f, focalOn(TeleconverterProfile.HASSELBLAD_X10), 1e-3f) // ~214 mm
        assertEquals(230f / 70f * 65f, focalOn(TeleconverterProfile.HASSELBLAD_X9S_PRO), 1e-3f)
        assertEquals(200f / 85f * 72f, focalOn(TeleconverterProfile.ZEISS_200_X500), 1e-3f) // ~169 mm
        assertEquals(200f / 85f * 80f, focalOn(TeleconverterProfile.HONOR_G200_MAGIC9), 1e-3f) // ~188 mm
        // On the body each kit was computed on, the sold figure comes back exactly.
        assertEquals(230f, focalOn(TeleconverterProfile.HASSELBLAD_230_X10_PRO_MAX), 1e-3f)
        assertEquals(400f, focalOn(TeleconverterProfile.ZEISS_400_X500_PRO_MAX), 1e-3f)
        assertEquals(500f, focalOn(TeleconverterProfile.HONOR_G500), 1e-3f)
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

    // ---- Phone detection: the ONE automatic part, and the only one there can be ----

    @Test
    fun `detects every phone the catalog carries a kit for`() {
        assertEquals(PhoneModel.FIND_X9_ULTRA, detectPhone("PMA110"))
        assertEquals(PhoneModel.FIND_X9_ULTRA, detectPhone("CPH2841"))
        assertEquals(PhoneModel.FIND_X9_PRO, detectPhone("CPH2791"))
        assertEquals(PhoneModel.VIVO_X200_ULTRA, detectPhone("V2454A"))
        assertEquals(PhoneModel.VIVO_X200_ULTRA, detectPhone("V2454DA"))
        assertEquals(PhoneModel.VIVO_X300_ULTRA, detectPhone("V2562"))
        // Google Play supported-devices CSV (2026-10-25): China AND global codes, satellite editions.
        assertEquals(PhoneModel.FIND_X9_ULTRA, detectPhone("PMA120"))
        listOf("PLG110", "PLG120").forEach { assertEquals(PhoneModel.FIND_X9_PRO, detectPhone(it)) }
        assertEquals(PhoneModel.FIND_X9S_PRO, detectPhone("PME110"))
        listOf("PMW110", "CPH2911", "OPG09").forEach { assertEquals(PhoneModel.FIND_X10, detectPhone(it)) }
        listOf("PMX110", "CPH2913").forEach { assertEquals(PhoneModel.FIND_X10_PRO_MAX, detectPhone(it)) }
        listOf("V2509A", "V2515").forEach { assertEquals(PhoneModel.VIVO_X300, detectPhone(it)) }
        listOf("V2502A", "V2502DA", "V2514").forEach { assertEquals(PhoneModel.VIVO_X300_PRO, detectPhone(it)) }
        assertEquals(PhoneModel.VIVO_X300S, detectPhone("V2548A"))
        listOf("V2547A", "V2547DA").forEach { assertEquals(PhoneModel.VIVO_X300_ULTRA, detectPhone(it)) }
        assertEquals(PhoneModel.VIVO_X500, detectPhone("V2609A"))
        listOf("V2608A", "V2608DA", "V2621", "V2622").forEach { assertEquals(PhoneModel.VIVO_X500_PRO, detectPhone(it)) }
        listOf("V2602A", "V2602DA", "V2610").forEach { assertEquals(PhoneModel.VIVO_X500_PRO_MAX, detectPhone(it)) }
        assertEquals(PhoneModel.HONOR_MAGIC8_PRO, detectPhone("BKQ-N49"))
    }

    @Test
    fun `detection tolerates vendor formatting of Build MODEL`() {
        assertEquals(PhoneModel.FIND_X9_ULTRA, detectPhone(" pma110 "))
        assertEquals(PhoneModel.VIVO_X300_ULTRA, detectPhone("v2562"))
    }

    @Test
    fun `detection is null for unknown, blank, and missing models`() {
        // Null is load-bearing: it is what keeps phoneModelDetected false, and the caption may only
        // say "Detected …" when a match actually happened.
        assertNull(detectPhone("SM-S928B"))
        assertNull(detectPhone(""))
        assertNull(detectPhone("   "))
        assertNull(detectPhone(null))
    }

    @Test
    fun `OTHER is never auto-detected`() {
        // It is the manual escape hatch, not a match: claiming a model would shadow a real phone.
        assertTrue(PhoneModel.OTHER.deviceModels.isEmpty())
    }

    @Test
    fun `no two phones claim the same Build MODEL`() {
        // detectPhone returns the FIRST match, so an overlap would make the seeded default depend on
        // declaration order — exactly the kind of silent coupling that survives review.
        val claims = PhoneModel.entries.flatMap { phone -> phone.deviceModels.map { it.lowercase() } }
        assertEquals(claims.size, claims.toSet().size)
    }

    // ---- The pair: which converters a phone offers, and what survives changing it ----

    @Test
    fun `a phone offers exactly its own kits plus everything that fits anything`() {
        PhoneModel.entries.forEach { phone ->
            val offered = phone.converters()
            val expected = TeleconverterProfile.entries.filter { it.phone == null || it.phone == phone }
            assertEquals("${phone.name} offer list", expected, offered)
            fitsAnything.forEach {
                assertTrue("${phone.name} must offer ${it.name}", it in offered)
            }
            offered.filter { it.phone != null }.forEach {
                assertEquals("${phone.name} offered a foreign kit ${it.name}", phone, it.phone)
            }
        }
    }

    @Test
    fun `no phone can reach another phone's kit`() {
        kits.forEach { kit ->
            PhoneModel.entries.filter { it != kit.phone }.forEach { other ->
                assertFalse(
                    "${other.name} must not offer ${kit.name}",
                    kit in other.converters(),
                )
            }
        }
    }

    @Test
    fun `OTHER offers only generics and custom`() {
        val offered = PhoneModel.OTHER.converters()
        assertEquals(fitsAnything, offered)
        assertTrue("OTHER must offer no kit", offered.none { it.phone != null })
    }

    @Test
    fun `the default converter is that phone's own kit, and a generic when it has none`() {
        assertEquals(TeleconverterProfile.EXPLORER_300, defaultConverterFor(PhoneModel.FIND_X9_ULTRA))
        assertEquals(TeleconverterProfile.HASSELBLAD_230, defaultConverterFor(PhoneModel.FIND_X9_PRO))
        assertEquals(TeleconverterProfile.ZEISS_200_X200, defaultConverterFor(PhoneModel.VIVO_X200_ULTRA))
        // The X300 Ultra takes two official converters; the BASE kit must win the default rather
        // than the exotic long optic.
        assertEquals(TeleconverterProfile.ZEISS_200_X300, defaultConverterFor(PhoneModel.VIVO_X300_ULTRA))
        // Same rule for every two-kit phone: the base optic, never the long one.
        assertEquals(TeleconverterProfile.ZEISS_200_X500_PRO_MAX, defaultConverterFor(PhoneModel.VIVO_X500_PRO_MAX))
        assertEquals(TeleconverterProfile.HONOR_G200_MAGIC9_PRO_MAX, defaultConverterFor(PhoneModel.HONOR_MAGIC9_PRO_MAX))
        // Every phone with a kit defaults to its own kit.
        PhoneModel.entries.filter { it != PhoneModel.OTHER }.forEach { phone ->
            assertEquals("${phone.name} default", phone, defaultConverterFor(phone).phone)
        }
        val other = defaultConverterFor(PhoneModel.OTHER)
        assertNull("OTHER's default must not be a kit", other.phone)
        assertFalse("OTHER's default must not be CUSTOM", other.isCustom)
        assertEquals(TeleconverterProfile.GENERIC_1_5, other)
    }

    @Test
    fun `the shipped defaults are a consistent pair`() {
        assertEquals(PhoneModel.FIND_X9_ULTRA, DEFAULT_PHONE_MODEL)
        assertEquals(DEFAULT_TELECONVERTER_PROFILE, defaultConverterFor(DEFAULT_PHONE_MODEL))
    }

    @Test
    fun `reconcile keeps anything that fits and replaces a foreign kit`() {
        // A generic or custom entry is glass that clamps to anything, so changing the phone must not
        // silently throw the user's pick away.
        PhoneModel.entries.forEach { phone ->
            fitsAnything.forEach {
                assertEquals("$it must survive a move to ${phone.name}", it, reconcileConverter(phone, it))
            }
        }
        // A kit for another body cannot physically mount; it falls back to the new phone's default.
        assertEquals(
            TeleconverterProfile.EXPLORER_300,
            reconcileConverter(PhoneModel.FIND_X9_ULTRA, TeleconverterProfile.ZEISS_400),
        )
        assertEquals(
            TeleconverterProfile.GENERIC_1_5,
            reconcileConverter(PhoneModel.OTHER, TeleconverterProfile.EXPLORER_300),
        )
    }

    @Test
    fun `reconcile is a no-op for a kit that already belongs to the phone`() {
        kits.forEach { kit ->
            assertEquals(kit, reconcileConverter(kit.phone!!, kit))
        }
    }

    @Test
    fun `reconcile always lands on something the phone actually offers`() {
        // The post-condition the two dropdowns depend on: whatever comes out is selectable in the
        // converter list, so the UI can never show a selection its own options do not contain.
        PhoneModel.entries.forEach { phone ->
            TeleconverterProfile.entries.forEach { profile ->
                val resolved = reconcileConverter(phone, profile)
                assertTrue(
                    "${phone.name} cannot offer $resolved",
                    resolved in phone.converters(),
                )
            }
        }
    }

    // ---- Focal / display scale ----

    @Test
    fun `a foreign converter reports the focal it actually delivers on this phone`() {
        // The label says "ZEISS 200 mm" because that is how the optic is sold, but it was computed
        // for an 85 mm host; on this phone's 70 mm periscope it is 165 mm, and the UI must say so.
        val onThisPhone = effectiveFocalMm(TeleconverterProfile.ZEISS_200_X200.magnification)
        assertEquals(165f, onThisPhone, 0.5f)
        assertTrue("must not repeat the product's own number", onThisPhone < 200f)
    }

    @Test
    fun `effective focal reproduces the nominal 300 mm at the kit magnification`() {
        assertEquals(300f, effectiveFocalMm(TELECONVERTER_MAGNIFICATION), 1e-3f)
        assertEquals(140f, effectiveFocalMm(2f), 1e-3f)
        assertEquals(70f, effectiveFocalMm(1f), 1e-3f)
    }

    // Review 2026-08-01: the hardcoded 70 mm default wrote a ZEISS 200's genuine 200 mm as
    // ≈165 mm into EXIF/OSD on the vivo hosts the converter list itself advertises. On the
    // DECLARED phone's own tele the kit must reproduce its sold focal exactly.
    @Test
    fun `a kit on its OWN host phone reports its sold focal`() {
        assertEquals(
            200f,
            effectiveFocalMm(
                TeleconverterProfile.ZEISS_200_X200.magnification,
                PhoneModel.VIVO_X200_ULTRA.teleEquivMm,
            ),
            0.5f,
        )
        assertEquals(
            400f,
            effectiveFocalMm(
                TeleconverterProfile.ZEISS_400.magnification,
                PhoneModel.VIVO_X300_ULTRA.teleEquivMm,
            ),
            0.5f,
        )
        assertEquals(
            300f,
            effectiveFocalMm(
                TELECONVERTER_MAGNIFICATION,
                PhoneModel.FIND_X9_ULTRA.teleEquivMm,
            ),
            1e-3f,
        )
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
