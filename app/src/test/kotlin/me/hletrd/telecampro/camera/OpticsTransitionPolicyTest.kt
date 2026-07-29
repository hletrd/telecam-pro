package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticsTransitionPolicyTest {
    @Test
    fun `lens transition drains the newest pending controls`() {
        val newest = ManualControls(iso = 1600, zoomRatio = 8f)

        assertEquals(
            newest,
            pendingControlsForTransition(newest, PendingControlsDisposition.DRAIN_BEFORE_OPTICS),
        )
    }

    @Test
    fun `settings replacement cancels the outgoing pending controls`() {
        assertNull(
            pendingControlsForTransition(
                ManualControls(iso = 3200),
                PendingControlsDisposition.CANCEL_FOR_REPLACEMENT,
            ),
        )
    }

    @Test
    fun `failed tele exit can retain return framing and raw until acceptance`() {
        val formats = PhotoFormats(heif = true, jpeg = false, dngRaw = true)

        val rolledBack = acceptedOpticsAuxState(
            teleconverter = true,
            photoOutputs = PhotoSessionOutputs(processed = true, raw = true),
            preTeleUnifiedZoom = 8f,
            photoFormats = formats,
        )

        assertEquals(8f, rolledBack.preTeleUnifiedZoom)
        assertTrue(rolledBack.photoFormats.dngRaw)
    }

    @Test
    fun `accepted non tele camera clears return framing and unavailable raw`() {
        val accepted = acceptedOpticsAuxState(
            teleconverter = false,
            photoOutputs = PhotoSessionOutputs(processed = true),
            preTeleUnifiedZoom = 8f,
            photoFormats = PhotoFormats(heif = true, jpeg = false, dngRaw = true),
        )

        assertTrue(accepted.preTeleUnifiedZoom.isNaN())
        assertFalse(accepted.photoFormats.dngRaw)
    }

    @Test
    fun `accepted preview-only session neither invents heif nor erases the request`() {
        // The original contract here was only half right. Inventing HEIF for a session with no
        // processed reader would be a lie, and still is. But ERASING the request was the mirror-image
        // lie: a still-less session (preview-only, or the 10-bit video trade that drops both readers
        // by design) says nothing about which formats the operator wants, and zeroing it edited their
        // selection — which then persisted on background and returned as HEIF-only next launch.
        val accepted = acceptedOpticsAuxState(
            teleconverter = false,
            photoOutputs = PhotoSessionOutputs(),
            preTeleUnifiedZoom = 8f,
            photoFormats = PhotoFormats(heif = false, jpeg = false, dngRaw = true),
        )

        assertEquals(PhotoFormats(heif = false, jpeg = false, dngRaw = true), accepted.photoFormats)
    }

    @Test
    fun `photo recall on same logical route uses request fast path`() {
        assertFalse(
            resolvedOpticsRequiresReconfigure(
                beforeVideo = false,
                targetVideo = false,
                beforeTeleconverter = false,
                targetTeleconverter = false,
                beforeCameraId = "logical-back",
                targetCameraId = "logical-back",
                controllerAvailable = true,
                beforeReady = true,
                readyControllerMatches = true,
            ),
        )
    }

    @Test
    fun `resolved recall reconfigures every structural or unready route`() {
        fun requires(
            beforeVideo: Boolean = false,
            targetVideo: Boolean = false,
            beforeTele: Boolean = false,
            targetTele: Boolean = false,
            beforeId: String? = "logical-back",
            targetId: String? = "logical-back",
            controller: Boolean = true,
            ready: Boolean = true,
            owner: Boolean = true,
        ) = resolvedOpticsRequiresReconfigure(
            beforeVideo,
            targetVideo,
            beforeTele,
            targetTele,
            beforeId,
            targetId,
            controller,
            ready,
            owner,
        )

        assertTrue(requires(targetVideo = true))
        assertTrue(requires(targetTele = true))
        assertTrue(requires(targetId = "standalone-tele"))
        assertTrue(requires(beforeId = null))
        assertTrue(requires(controller = false))
        assertTrue(requires(ready = false))
        assertTrue(requires(owner = false))
    }
}
