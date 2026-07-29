package me.hletrd.telecampro

import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import me.hletrd.telecampro.camera.acceptedOpticsAuxState
import me.hletrd.telecampro.camera.rawSelectable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DNG is a ROUTE INPUT, not just a save option, and the two facts that used to share one flag —
 * "this device can do RAW" and "this session carries RAW" — answer different questions.
 */
class RawSelectableTest {

    @Test
    fun `photo on the logical route offers DNG even though the session has no RAW`() {
        // The regression that started this: gating on session truth made the chip unreachable,
        // because the route only moves to a RAW-capable lens BECAUSE the operator chooses DNG.
        assertTrue(
            rawSelectable(
                deviceSupportsRaw = true,
                rawInSession = false,
                videoMode = false,
                hiResSession = false,
                frontFacing = false,
            ),
        )
    }

    @Test
    fun `ten-bit video refuses DNG - no route change can bring the dropped still readers back`() {
        assertFalse(
            rawSelectable(
                deviceSupportsRaw = true,
                rawInSession = false,
                videoMode = true,
                hiResSession = false,
                frontFacing = false,
            ),
        )
    }

    @Test
    fun `eight-bit video keeps DNG while its standalone session really carries RAW`() {
        assertTrue(
            rawSelectable(
                deviceSupportsRaw = true,
                rawInSession = true,
                videoMode = true,
                hiResSession = false,
                frontFacing = false,
            ),
        )
    }

    @Test
    fun `the hi-res rung force-drops RAW, so DNG is refused there`() {
        assertFalse(
            rawSelectable(
                deviceSupportsRaw = true,
                rawInSession = false,
                videoMode = false,
                hiResSession = true,
                frontFacing = false,
            ),
        )
    }

    @Test
    fun `the front route refuses DNG even when the front camera advertises RAW`() {
        // The session plan force-drops RAW on the front route, so a live chip there would promise a
        // DNG that never arrives.
        assertFalse(
            rawSelectable(
                deviceSupportsRaw = true,
                rawInSession = false,
                videoMode = false,
                hiResSession = false,
                frontFacing = true,
            ),
        )
    }

    @Test
    fun `a device without RAW never offers DNG, whatever the mode`() {
        for (video in listOf(false, true)) {
            for (hiRes in listOf(false, true)) {
                assertFalse(
                    "video=$video hiRes=$hiRes",
                    rawSelectable(
                        deviceSupportsRaw = false,
                        rawInSession = false,
                        videoMode = video,
                        hiResSession = hiRes,
                        frontFacing = false,
                    ),
                )
            }
        }
    }
}

/**
 * A session with no still lane at all is a session STATE, not an answer about formats: normalising
 * against it edited the operator's own request.
 */
class AcceptedPhotoFormatsTest {

    private fun accepted(formats: PhotoFormats, outputs: PhotoSessionOutputs) =
        acceptedOpticsAuxState(
            teleconverter = false,
            photoOutputs = outputs,
            preTeleUnifiedZoom = Float.NaN,
            photoFormats = formats,
        ).photoFormats

    @Test
    fun `a still-less session leaves the request untouched`() {
        // The 10-bit video session drops both still readers by design. Before this, the empty set
        // was written over the request, persisted on background, and came back as HEIF-only.
        val request = PhotoFormats(heif = true, jpeg = true, dngRaw = true)
        assertEquals(request, accepted(request, PhotoSessionOutputs(processed = false, raw = false)))
    }

    @Test
    fun `a processed-only session normalises the processed axis but keeps the RAW request`() {
        // dngRaw SURVIVES: on the logical photo route that request is exactly what moves the session
        // to a lens that can serve it. Clearing it here diverged the UI from the engine's rawWanted
        // and the change gate then froze that divergence.
        assertEquals(
            PhotoFormats(heif = true, jpeg = true, dngRaw = true),
            accepted(
                PhotoFormats(heif = true, jpeg = true, dngRaw = true),
                PhotoSessionOutputs(processed = true, raw = false),
            ),
        )
    }

    @Test
    fun `a hi-res session still collapses the processed axis to passthrough JPEG`() {
        assertEquals(
            false,
            accepted(
                PhotoFormats(heif = true, jpeg = true, dngRaw = false),
                PhotoSessionOutputs(processed = true, raw = false, hiRes = true),
            ).heif,
        )
    }

    @Test
    fun `a RAW-only session keeps DNG and drops the processed request`() {
        assertEquals(
            PhotoFormats(heif = false, jpeg = false, dngRaw = true),
            accepted(
                PhotoFormats(heif = true, jpeg = true, dngRaw = true),
                PhotoSessionOutputs(processed = false, raw = true),
            ),
        )
    }
}
