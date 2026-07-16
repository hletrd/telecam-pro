package com.hletrd.findx9tele.camera

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
            rawAvailable = true,
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
            rawAvailable = false,
            preTeleUnifiedZoom = 8f,
            photoFormats = PhotoFormats(heif = true, jpeg = false, dngRaw = true),
        )

        assertTrue(accepted.preTeleUnifiedZoom.isNaN())
        assertFalse(accepted.photoFormats.dngRaw)
    }

    @Test
    fun `recalled size waits for target caps while interactive size validates now`() {
        assertFalse(validatesVideoSizeAgainstCurrentCaps(VideoSizeRequestSource.RECALL))
        assertTrue(validatesVideoSizeAgainstCurrentCaps(VideoSizeRequestSource.INTERACTIVE))
    }
}
