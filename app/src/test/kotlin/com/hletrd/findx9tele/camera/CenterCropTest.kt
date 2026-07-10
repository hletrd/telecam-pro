package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the still-photo aspect-crop rect math ([centerCropBox]) for the 4:3-native sensor. */
class CenterCropTest {

    @Test
    fun `16to9 crop of the full 4to3 sensor is a centered height crop`() {
        // 4096x3072 sensor → 16:9 keeps full width, crops height to 2304, centered.
        val box = centerCropBox(4096, 3072, 16, 9)
        assertEquals(CropBox(x = 0, y = (3072 - 2304) / 2, w = 4096, h = 2304), box)
    }

    @Test
    fun `same-aspect crop is the identity`() {
        assertEquals(CropBox(0, 0, 4096, 3072), centerCropBox(4096, 3072, 4, 3))
        assertEquals(CropBox(0, 0, 3840, 2160), centerCropBox(3840, 2160, 16, 9))
    }

    @Test
    fun `narrower target than source crops width instead`() {
        // A 4:3 crop out of a 16:9 source keeps full height and crops width to 2880, centered.
        val box = centerCropBox(3840, 2160, 4, 3)
        assertEquals(CropBox(x = (3840 - 2880) / 2, y = 0, w = 2880, h = 2160), box)
    }

    @Test
    fun `crop box always stays inside the source`() {
        for ((w, h) in listOf(4096 to 3072, 3840 to 2160, 1440 to 1080, 101 to 77)) {
            for ((rw, rh) in listOf(4 to 3, 16 to 9)) {
                val b = centerCropBox(w, h, rw, rh)
                org.junit.Assert.assertTrue("origin in bounds", b.x >= 0 && b.y >= 0)
                org.junit.Assert.assertTrue(
                    "extent in bounds for ${w}x$h $rw:$rh",
                    b.x + b.w <= w && b.y + b.h <= h,
                )
            }
        }
    }
}
