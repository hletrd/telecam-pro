package me.hletrd.telecampro.capture

import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.DeletedStillPublication
import me.hletrd.telecampro.camera.RetainedStillDisposition
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
    fun `durable COMPLETE and successful publish emits one saved callback`() {
        val events = mutableListOf<String>()
        val publication = completeStillPublication(
            kind = "HEIF",
            output = "heif-row",
            captureId = 4,
            markerDurable = true,
            effects = StillPublicationEffects(
                publishOwned = { output, _ ->
                    events += "publish:$output"
                    DeletedStillPublication.LIVE_PUBLISHED
                },
                finishPublished = { output, id -> events += "finish:$output:$id" },
                emitSaved = { output, id -> events += "saved:$output:$id" },
                emitRetained = { _, _ ->
                    events += "retained"
                    RetainedStillDisposition.RETAIN_FOR_RECOVERY
                },
                emitStatus = { events += "status" },
            ),
        )

        assertEquals(StillOutputPublication.PUBLISHED, publication)
        assertEquals(
            listOf("publish:heif-row", "saved:heif-row:4", "finish:heif-row:4"),
            events,
        )
    }

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
                    publishOwned = { _, _ ->
                        events += "publish"
                        DeletedStillPublication.LIVE_PUBLISHED
                    },
                    finishPublished = { _, _ -> events += "finish" },
                    emitSaved = { _, _ -> events += "saved" },
                    emitRetained = { output, id ->
                        events += "retained:$output:$id"
                        RetainedStillDisposition.RETAIN_FOR_RECOVERY
                    },
                    emitStatus = { events += "status:${it.message}" },
                ),
            )

            assertEquals(kind, StillOutputPublication.RETAINED_MARKER_UNAVAILABLE, publication)
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
                publishOwned = { _, _ ->
                    events += "publish"
                    DeletedStillPublication.LIVE_PUBLICATION_FAILED
                },
                finishPublished = { _, _ -> events += "finish" },
                emitSaved = { _, _ -> events += "saved" },
                emitRetained = { _, _ ->
                    events += "retained"
                    RetainedStillDisposition.RETAIN_FOR_RECOVERY
                },
                emitStatus = { events += "status:${it.message}" },
            ),
        )

        assertEquals(StillOutputPublication.RETAINED_PUBLICATION_UNAVAILABLE, publication)
        assertEquals(
            listOf(
                "publish",
                "status:${CameraStatusMessage.OUTPUT_SAVED_PENDING}",
                "retained",
            ),
            events,
        )
    }

    @Test
    fun `deleted output never emits saved even when provider publication would succeed`() {
        val events = mutableListOf<String>()
        val publication = completeStillPublication(
            kind = "DNG",
            output = "dng-row",
            captureId = 77,
            markerDurable = true,
            effects = StillPublicationEffects(
                publishOwned = { _, _ ->
                    events += "owned-publish"
                    DeletedStillPublication.DISCARD_DELETED_CAPTURE
                },
                finishPublished = { _, _ -> events += "finish" },
                emitSaved = { _, _ -> events += "saved" },
                emitRetained = { _, _ ->
                    events += "retained"
                    RetainedStillDisposition.RETAIN_FOR_RECOVERY
                },
                emitStatus = { events += "status" },
            ),
        )

        assertEquals(StillOutputPublication.DISCARDED_DELETED_CAPTURE, publication)
        assertEquals(listOf("owned-publish"), events)
    }

    @Test
    fun `failed discard remains a nonterminal typed result`() {
        val statuses = mutableListOf<CameraStatusMessage>()
        val publication = completeStillPublication(
            kind = "JPEG",
            output = "jpeg-row",
            captureId = 78,
            markerDurable = true,
            effects = StillPublicationEffects(
                publishOwned = { _, _ -> DeletedStillPublication.DISCARD_RETRY_PENDING },
                finishPublished = { _, _ -> error("not published") },
                emitSaved = { _, _ -> error("not saved") },
                emitRetained = { _, _ -> error("already owned") },
                emitStatus = { statuses += it.message },
            ),
        )

        assertEquals(StillOutputPublication.DISCARD_RETRY_PENDING, publication)
        assertEquals(listOf(CameraStatusMessage.COULD_NOT_DELETE_FILE), statuses)
    }

    @Test
    fun `marker failure still honors a tombstone acquired before retained routing`() {
        val cases = listOf(
            RetainedStillDisposition.DISCARD_DELETED_CAPTURE to
                StillOutputPublication.DISCARDED_DELETED_CAPTURE,
            RetainedStillDisposition.DISCARD_RETRY_PENDING to
                StillOutputPublication.DISCARD_RETRY_PENDING,
        )
        cases.forEach { (retained, expected) ->
            val result = completeStillPublication(
                kind = "HEIF",
                output = "heif-row",
                captureId = 79,
                markerDurable = false,
                effects = StillPublicationEffects(
                    publishOwned = { _, _ -> error("marker must gate publish") },
                    finishPublished = { _, _ -> error("not published") },
                    emitSaved = { _, _ -> error("not saved") },
                    emitRetained = { _, _ -> retained },
                    emitStatus = {},
                ),
            )
            assertEquals(expected, result)
        }
    }

    @Test
    fun `durable family veto wins when exact discard and provider delete both fail`() {
        val events = mutableListOf<String>()
        val result = completeStillPublication(
            kind = "JPEG",
            output = "late-jpeg-row",
            captureId = 90,
            markerDurable = true,
            effects = StillPublicationEffects(
                familyDeleted = {
                    events += "family-veto"
                    true
                },
                discardDeletedFamily = { output ->
                    events += "discard:$output"
                    me.hletrd.telecampro.storage.PendingOutputDiscardResult.UNRESOLVED
                },
                publishOwned = { _, _ ->
                    events += "publish"
                    DeletedStillPublication.LIVE_PUBLISHED
                },
                finishPublished = { _, _ -> events += "finish" },
                emitSaved = { _, _ -> events += "saved" },
                emitRetained = { _, _ ->
                    events += "retained"
                    RetainedStillDisposition.RETAIN_FOR_RECOVERY
                },
                emitStatus = { events += "status" },
            ),
        )

        assertEquals(StillOutputPublication.DISCARDED_DELETED_CAPTURE, result)
        assertEquals(listOf("family-veto", "discard:late-jpeg-row"), events)
    }

    @Test
    fun `default family cleanup still fail-closes a deleted output`() {
        val result = completeStillPublication(
            kind = "JPEG",
            output = "deleted-row",
            captureId = 92,
            markerDurable = true,
            effects = StillPublicationEffects(
                familyDeleted = { true },
                publishOwned = { _, _ -> error("deleted family must not publish") },
                finishPublished = { _, _ -> error("not published") },
                emitSaved = { _, _ -> error("not saved") },
                emitRetained = { _, _ -> error("not retained") },
                emitStatus = {},
            ),
        )

        assertEquals(StillOutputPublication.DISCARDED_DELETED_CAPTURE, result)
    }
}
