package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared stream-size selection rule ([matchesStreamAspect]/[pickStreamSize]) behind the
 * device-verified 4K / Open-Gate defaults — the engine fallback used to re-implement it inline.
 */
class PickStreamSizeTest {

    private val sizes = listOf(
        4096 to 3072, // 4:3, over the 3840 cap
        3840 to 2160, // 16:9 at the cap
        2560 to 1920, // 4:3
        1920 to 1080, // 16:9
        1280 to 720, // 16:9
        4000 to 3000, // 4:3, over cap
    )

    @Test
    fun aspectRule_matchesExactly() {
        assertTrue(matchesStreamAspect(3840, 2160, fourByThree = false))
        assertFalse(matchesStreamAspect(3840, 2160, fourByThree = true))
        assertTrue(matchesStreamAspect(2560, 1920, fourByThree = true))
        assertFalse(matchesStreamAspect(2560, 1921, fourByThree = true))
    }

    @Test
    fun picksLargestSixteenNine_underTheCap() {
        assertEquals(3840 to 2160, pickStreamSize(sizes, capWidth = 3840, fourByThree = false))
    }

    @Test
    fun picksLargestFourThree_underTheCap() {
        // Both 4:3 sizes above 3840 wide are excluded; 2560×1920 wins (the Open-Gate default).
        assertEquals(2560 to 1920, pickStreamSize(sizes, capWidth = 3840, fourByThree = true))
    }

    @Test
    fun noAspectMatch_fallsBackToLargestAtOrBelowTheCap() {
        val onlyOdd = listOf(4096 to 3072, 2000 to 1000, 999 to 500)
        assertEquals(2000 to 1000, pickStreamSize(onlyOdd, capWidth = 3840, fourByThree = false))
    }

    @Test
    fun automaticChoiceWithNoSizeAtOrBelowTheCap_returnsNull() {
        val onlyExtended = listOf(7680 to 4320, 5120 to 2880)
        assertNull(pickStreamSize(onlyExtended, capWidth = 3840, fourByThree = false))
    }

    @Test
    fun automaticVideoChoice_staysAtUhdWhen8kIsOffered() {
        val offered = listOf(7680 to 4320, 3840 to 2160)
        assertEquals(3840 to 2160, pickStreamSize(offered, capWidth = 3840, fourByThree = false))
    }

    @Test
    fun explicitVideoChoice_honoursOffered8k() {
        val offered = listOf(7680 to 4320, 3840 to 2160)
        assertEquals(
            7680 to 4320,
            explicitStreamSizeOrAutomatic(7680 to 4320, offered, automatic = 3840 to 2160),
        )
    }

    @Test
    fun restored8kChoice_fallsBackWhenCurrentGammaRemoves8k() {
        val offered = listOf(3840 to 2160, 1920 to 1080)
        assertEquals(
            3840 to 2160,
            explicitStreamSizeOrAutomatic(7680 to 4320, offered, automatic = 3840 to 2160),
        )
    }

    @Test
    fun automaticPhotoPreview_staysBelow8k4By3() {
        val offered = listOf(7680 to 5760, 2560 to 1920, 1440 to 1080)
        assertEquals(2560 to 1920, pickStreamSize(offered, capWidth = 3840, fourByThree = true))
    }

    @Test
    fun emptyInput_returnsNull() {
        assertNull(pickStreamSize(emptyList(), capWidth = 3840, fourByThree = false))
    }
}
