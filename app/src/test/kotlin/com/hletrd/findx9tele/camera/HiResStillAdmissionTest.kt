package com.hletrd.findx9tele.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ONE hi-res admission predicate ([hiResAdmitted]) the engine resolves at every optics
 * door. Every axis is load-bearing: video admission would put a 200MP reader in a video session,
 * 16:9 admission would silently skip the crop (the hi-res save path is a byte passthrough), and
 * non-standalone admission re-arms the documented gralloc blob-allocation crash class.
 */
class HiResStillAdmissionTest {

    private fun admitted(
        requested: Boolean = true,
        videoMode: Boolean = false,
        aspect: AspectRatio = AspectRatio.W4_3,
        standalone: Boolean = true,
        advertised: Boolean = true,
    ) = hiResAdmitted(requested, videoMode, aspect, standalone, advertised)

    @Test
    fun `admitted only with every axis satisfied`() {
        assertTrue(admitted())
    }

    @Test
    fun `not requested is never admitted`() {
        assertFalse(admitted(requested = false))
    }

    @Test
    fun `video mode is never admitted`() {
        assertFalse(admitted(videoMode = true))
    }

    @Test
    fun `16 by 9 is never admitted`() {
        assertFalse(admitted(aspect = AspectRatio.W16_9))
    }

    @Test
    fun `non-standalone routes are never admitted`() {
        assertFalse(admitted(standalone = false))
    }

    @Test
    fun `an unadvertised camera is never admitted`() {
        assertFalse(admitted(advertised = false))
    }

    @Test
    fun `the front route is decided by the standalone-advertised axes, not a facing special case`() {
        // The facing door has no axis here on purpose: the front selection is standalone-shaped
        // (physicalId == null, not a logical composite), so admission reduces to "advertises a
        // full-sensor size". The PMA110 front is expected to advertise none → denied; a
        // hypothetical advertising front would be admitted like any rear standalone, never crash-
        // gated by facing itself.
        assertFalse(admitted(standalone = true, advertised = false))
    }

    @Test
    fun `single failing axis defeats an otherwise-full grant`() {
        // Exhaustive 2^5 sweep: admission is exactly the conjunction, no hidden interaction.
        for (requested in booleanArrayOf(false, true))
            for (video in booleanArrayOf(false, true))
                for (fourThree in booleanArrayOf(false, true))
                    for (standalone in booleanArrayOf(false, true))
                        for (advertised in booleanArrayOf(false, true)) {
                            val aspect = if (fourThree) AspectRatio.W4_3 else AspectRatio.W16_9
                            val expected = requested && !video && fourThree && standalone && advertised
                            org.junit.Assert.assertEquals(
                                "requested=$requested video=$video aspect=$aspect " +
                                    "standalone=$standalone advertised=$advertised",
                                expected,
                                hiResAdmitted(requested, video, aspect, standalone, advertised),
                            )
                        }
    }
}
