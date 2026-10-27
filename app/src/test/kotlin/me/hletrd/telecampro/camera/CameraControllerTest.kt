package me.hletrd.telecampro.camera

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Extended-raster configure failures bypass every transfer/session fallback before the legacy ladder. */
@RunWith(RobolectricTestRunner::class)
class CameraControllerTest {
    private val eightK = Size(7680, 4320)

    @Test
    fun `a soft extended-raster rejection is one refusal instead of another session shape`() {
        val decision = classifyExtendedVideoRasterConfigureRejection(
            routeKey = "0//tc",
            extendedSize = eightK,
            opticsGeneration = 17L,
            failure = IllegalArgumentException("unsupported 8K combination"),
        )

        assertEquals(
            ExtendedVideoRasterConfigureRejection.Soft("0//tc", eightK, 17L),
            decision,
        )
    }

    @Test
    fun `an asynchronous extended-raster configure failure is also a soft refusal`() {
        val decision = classifyExtendedVideoRasterConfigureRejection(
            routeKey = "4//tc",
            extendedSize = eightK,
            opticsGeneration = 9L,
            failure = null,
        )

        assertEquals(ExtendedVideoRasterConfigureRejection.Soft("4//tc", eightK, 9L), decision)
    }

    @Test
    fun `a fatal extended-raster rejection carries its exact route and size to recovery`() {
        val cause = IllegalStateException("camera device error")

        val decision = classifyExtendedVideoRasterConfigureRejection(
            routeKey = "0//",
            extendedSize = eightK,
            opticsGeneration = 23L,
            failure = cause,
            deviceFatal = true,
        )

        val fatal = decision as ExtendedVideoRasterConfigureRejection.Fatal
        assertEquals("0//", fatal.failure.routeKey)
        assertEquals(eightK, fatal.failure.size)
        assertSame(cause, fatal.failure.cause)
    }

    @Test
    fun `legacy sizes leave the established maker RAW P010 and ordinary ladders untouched`() {
        assertNull(
            classifyExtendedVideoRasterConfigureRejection(
                routeKey = "4//tc",
                extendedSize = null,
                opticsGeneration = 3L,
                failure = null,
            ),
        )
        assertNull(
            classifyExtendedVideoRasterConfigureRejection(
                routeKey = "4//tc",
                extendedSize = null,
                opticsGeneration = 3L,
                failure = IllegalStateException("camera device error"),
                deviceFatal = true,
            ),
        )
    }

    @Test
    fun `an extended marker identifies rasters beyond the legacy envelope`() {
        assertTrue(requiresExtendedVideoProof(eightK.width, eightK.height))
    }
}
