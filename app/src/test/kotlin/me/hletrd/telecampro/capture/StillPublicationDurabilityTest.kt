package me.hletrd.telecampro.capture

import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.storage.CompletedOutputPublication
import me.hletrd.telecampro.storage.OrphanDisposition
import me.hletrd.telecampro.storage.PendingJournalState
import me.hletrd.telecampro.storage.PendingProbe
import me.hletrd.telecampro.storage.markCompletionWithRetry
import me.hletrd.telecampro.storage.orphanDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StillPublicationDurabilityTest {

    @Test
    fun `exhausted COMPLETE retains HEIF JPEG and DNG without publish or saved callbacks`() {
        listOf("HEIF", "JPEG", "DNG").forEach { kind ->
            val events = mutableListOf<String>()
            val marker = markCompletionWithRetry(
                maxAttempts = 3,
                commit = {
                    events += "marker"
                    false
                },
            )

            val publication = completeStillPublication(
                kind = kind,
                output = "$kind-row",
                captureId = 17,
                markerDurable = marker.durable,
                effects = StillPublicationEffects(
                    publish = { events += "publish"; true },
                    emitSaved = { _, _ -> events += "saved" },
                    emitRetained = { output, id -> events += "retained:$output:$id" },
                    emitStatus = { events += "status:${it.message}" },
                ),
            )

            assertEquals(kind, CompletedOutputPublication.RETAINED_MARKER_UNAVAILABLE, publication)
            assertEquals(
                kind,
                listOf(
                    "marker",
                    "marker",
                    "marker",
                    "status:${CameraStatusMessage.OUTPUT_SAVED_PENDING_RECOVERY}",
                    "retained:$kind-row:17",
                ),
                events,
            )
            assertFalse(kind, events.contains("publish"))
            assertFalse(kind, events.contains("saved"))
            // Structurally complete REGISTERED rows are adopted on launch even though COMPLETE
            // could not be committed in the originating process.
            assertEquals(
                kind,
                OrphanDisposition.ADOPT,
                orphanDisposition(PendingJournalState.REGISTERED, PendingProbe.VALID),
            )
        }
    }

    @Test
    fun `durable COMPLETE preserves publish-failure retained outcome`() {
        val events = mutableListOf<String>()
        val publication = completeStillPublication(
            kind = "JPEG",
            output = "jpeg-row",
            captureId = 9,
            markerDurable = true,
            effects = StillPublicationEffects(
                publish = { events += "publish"; false },
                emitSaved = { _, _ -> events += "saved" },
                emitRetained = { _, _ -> events += "retained" },
                emitStatus = { events += "status:${it.message}" },
            ),
        )

        assertEquals(CompletedOutputPublication.RETAINED_PUBLICATION_UNAVAILABLE, publication)
        assertEquals(
            listOf(
                "publish",
                "status:${CameraStatusMessage.OUTPUT_SAVED_PENDING}",
                "retained",
            ),
            events,
        )
    }
}
