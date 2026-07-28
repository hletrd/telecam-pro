package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the EXIF device/lens labels, which used to be literals measured on one phone.
 *
 * The two failures these prevent are both "a file that lies about itself": a camera model naming a
 * handset the shot was not taken on, and a lens name from an id table that defaulted every
 * unrecognised rear lens to "tele".
 */
class DeviceExifLabelsTest {

    @Test
    fun `make and model are omitted rather than written empty`() {
        // Build.MANUFACTURER/MODEL are not guaranteed to be populated; a blank EXIF Make is worse
        // than an absent one, because readers stop applying their own fallbacks.
        assertNull(exifMake(null))
        assertNull(exifMake("   "))
        assertNull(exifModel(""))
        assertEquals("OPPO", exifMake("  OPPO "))
        assertEquals("PMA110", exifModel("PMA110 "))
    }

    @Test
    fun `the device label does not repeat a manufacturer the model already carries`() {
        // Vendors disagree: OPPO reports a bare code, Google reports a name that already includes
        // the make. Concatenating blindly gives "Google Google Pixel 8 Pro".
        assertEquals("OPPO PMA110", deviceLabel("OPPO", "PMA110"))
        assertEquals("Google Pixel 8 Pro", deviceLabel("Google", "Pixel 8 Pro"))
        assertEquals("samsung SM-S928B", deviceLabel("samsung", "SM-S928B"))
    }

    @Test
    fun `the device label degrades to whichever half exists`() {
        assertEquals("OPPO", deviceLabel("OPPO", null))
        assertEquals("PMA110", deviceLabel(null, "PMA110"))
        assertEquals("", deviceLabel(null, null))
    }

    @Test
    fun `lens name comes from the measured focal, not a camera id`() {
        // The id table this replaced read "2 = wide, 3 = ultra-wide, 4 = tele, 5 = periscope" —
        // true on one phone and arbitrary anywhere else.
        assertEquals("ultra-wide", lensNameForEquiv(14f, frontFacing = false))
        assertEquals("wide", lensNameForEquiv(23f, frontFacing = false))
        assertEquals("tele", lensNameForEquiv(70f, frontFacing = false))
        assertEquals("periscope tele", lensNameForEquiv(230f, frontFacing = false))
    }

    @Test
    fun `an unknown focal never silently becomes tele, and the front is never a rear lens`() {
        // The old `else -> "tele"` default stamped "tele" on anything it did not recognise.
        assertEquals("camera", lensNameForEquiv(0f, frontFacing = false))
        assertEquals("camera", lensNameForEquiv(-1f, frontFacing = false))
        // Facing is enumerated, so it wins outright: a 21 mm selfie camera is not "wide".
        assertEquals("front", lensNameForEquiv(21f, frontFacing = true))
        assertEquals("front", lensNameForEquiv(0f, frontFacing = true))
    }

    @Test
    fun `the lens model reads like a lens barrel`() {
        // f-number TRUNCATES like a barrel marking: f/2.26 is sold as f/2.2, not f/2.3.
        assertEquals(
            "OPPO PMA110 tele camera 70mm f/2.2",
            exifLensModel("OPPO", "PMA110", equivMm = 69.6f, apertureF = 2.26f, frontFacing = false),
        )
        assertEquals(
            "Google Pixel 8 Pro front camera 21mm f/2.2",
            exifLensModel("Google", "Pixel 8 Pro", equivMm = 21f, apertureF = 2.2f, frontFacing = true),
        )
    }

    @Test
    fun `a build with no identity still yields a usable lens description`() {
        // No leading space, no doubled separator — the device half simply drops out.
        assertEquals(
            "wide camera 23mm f/1.6",
            exifLensModel(null, null, equivMm = 23f, apertureF = 1.63f, frontFacing = false),
        )
    }
}
