package me.hletrd.telecampro.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestCaptureReducerTest {

    @Test
    fun `newer video wins over an older photo across collections`() {
        val oldPhoto = familyRow("old.heic", stillKey(at = 1_000L, sequence = 1L), "heic", id = 80L)
        val newVideo = familyRow(
            output = "new.mp4",
            key = videoKey(at = 2_000L, sequence = 2L),
            extension = "mp4",
            id = 2L,
            mime = "video/mp4",
        )

        val restored = restoreLatestCapture(listOf(newVideo, oldPhoto))

        assertEquals("new.mp4", restored?.preferred?.output)
        assertEquals(CaptureFamilyMedia.VIDEO, restored?.familyKey?.media)
    }

    @Test
    fun `newer raw-only capture wins over an older displayable photo`() {
        val oldPhoto = familyRow("old.heic", stillKey(at = 1_000L, sequence = 1L), "heic", id = 1L)
        val newRaw = familyRow(
            output = "new.dng",
            key = stillKey(at = 2_000L, sequence = 2L),
            extension = "dng",
            id = 2L,
            mime = "image/x-adobe-dng",
        )

        val restored = restoreLatestCapture(listOf(oldPhoto, newRaw))

        assertEquals("new.dng", restored?.preferred?.output)
        assertEquals(StoredMediaOutputKind.RAW, restored?.preferred?.kind)
    }

    @Test
    fun `displayable output is preferred only within the winning exact family`() {
        val key = stillKey(at = 3_000L, sequence = 9L)
        val rows = listOf(
            familyRow("raw", key, "dng", id = 13L, mime = "image/dng"),
            familyRow("jpeg", key, "jpg", id = 12L, mime = "image/jpeg"),
            familyRow("heic", key, "heic", id = 11L, mime = "image/heic"),
        )

        val restored = restoreLatestCapture(rows)

        assertEquals("heic", restored?.preferred?.output)
        assertEquals(listOf("heic", "jpeg", "raw"), restored?.outputs?.map { it.output })
        assertEquals(key, restored?.familyKey)
        assertEquals(RestoredDeleteScope.CAPTURE_FAMILY, restored?.deleteScope)
    }

    @Test
    fun `timestamp ties use the admission sequence before displayability`() {
        val olderDisplayable = familyRow(
            "photo.heic",
            stillKey(at = 4_000L, sequence = 20L),
            "heic",
            id = 99L,
        )
        val newerRaw = familyRow(
            "raw.dng",
            stillKey(at = 4_000L, sequence = 21L),
            "dng",
            id = 1L,
            mime = "image/x-adobe-dng",
        )

        val restored = restoreLatestCapture(listOf(olderDisplayable, newerRaw))

        assertEquals("raw.dng", restored?.preferred?.output)
    }

    @Test
    fun `every review-supported DNG MIME alias restores as raw`() {
        val aliases = listOf(
            "image/x-adobe-dng",
            "IMAGE/DNG",
            "application/x-adobe-dng; version=1",
        )

        aliases.forEachIndexed { index, mime ->
            val row = familyRow(
                output = "raw-$index",
                key = stillKey(at = 5_000L + index, sequence = index.toLong()),
                extension = "dng",
                id = index.toLong(),
                mime = mime,
            )
            assertEquals(StoredMediaOutputKind.RAW, restoreLatestCapture(listOf(row))?.preferred?.kind)
        }
    }

    @Test
    fun `pending and disappeared rows cannot own restored review`() {
        val visible = familyRow("visible.heic", stillKey(6_000L, 1L), "heic", id = 1L)
        val pending = familyRow(
            "pending.mp4",
            videoKey(9_000L, 2L),
            "mp4",
            id = 2L,
            mime = "video/mp4",
            pending = true,
        )
        val deleted = familyRow(
            "deleted.dng",
            stillKey(10_000L, 3L),
            "dng",
            id = 3L,
            mime = "image/x-adobe-dng",
            present = false,
        )

        val restored = restoreLatestCapture(listOf(pending, deleted, visible))

        assertEquals("visible.heic", restored?.preferred?.output)
        assertNull(restoreLatestCapture(listOf(pending, deleted)))
    }

    @Test
    fun `legacy burst files never group by timestamp proximity`() {
        val first = legacyRow(
            output = "burst-a",
            name = "IMG_TELECAM_20260716_120000_000_001.heic",
            id = 41L,
            takenAt = 7_000L,
        )
        val second = legacyRow(
            output = "burst-b",
            name = "IMG_TELECAM_20260716_120000_001_002.dng",
            id = 42L,
            takenAt = 7_000L,
            mime = "image/x-adobe-dng",
        )

        val restored = restoreLatestCapture(listOf(first, second))

        assertEquals("burst-b", restored?.preferred?.output)
        assertEquals(listOf("burst-b"), restored?.outputs?.map { it.output })
        assertEquals(RestoredDeleteScope.FILE_ONLY, restored?.deleteScope)
        assertNull(restored?.familyKey)
    }

    @Test
    fun `a proven family with only one extant sibling retains capture scope`() {
        val row = familyRow(
            output = "only.dng",
            key = stillKey(8_000L, 1L),
            extension = "dng",
            id = 1L,
            mime = "application/x-adobe-dng",
        )

        val restored = restoreLatestCapture(listOf(row))

        assertEquals(listOf("only.dng"), restored?.outputs?.map { it.output })
        assertEquals(RestoredDeleteScope.CAPTURE_FAMILY, restored?.deleteScope)
    }

    @Test
    fun `collection-mismatched canonical name falls back to one file`() {
        val imageNameOnVideoRow = StoredMediaRow(
            output = "odd-row",
            collection = StoredMediaCollection.VIDEO,
            rowId = 7L,
            displayName = stillKey(9_000L, 1L).displayName("heic"),
            mimeType = "video/mp4",
            dateTakenEpochMillis = 9_000L,
            dateAddedEpochSeconds = 9L,
            dateModifiedEpochSeconds = 9L,
            isPending = false,
        )

        assertEquals(RestoredDeleteScope.FILE_ONLY, restoreLatestCapture(listOf(imageNameOnVideoRow))?.deleteScope)
    }

    @Test
    fun `empty store has no restored owner`() {
        assertNull(restoreLatestCapture<String>(emptyList()))
    }

    @Test
    fun `successful image and video query rows are merged before ordering`() {
        val oldPhoto = familyRow("old.heic", stillKey(at = 1_000L, sequence = 1L), "heic", id = 80L)
        val newVideo = familyRow(
            output = "new.mp4",
            key = videoKey(at = 2_000L, sequence = 2L),
            extension = "mp4",
            id = 2L,
            mime = "video/mp4",
        )

        val restored = restoreLatestCaptureFromQueryResults(
            imageRows = Result.success(listOf(oldPhoto)),
            videoRows = Result.success(listOf(newVideo)),
        )

        assertEquals("new.mp4", restored?.preferred?.output)
    }

    @Test
    fun `image rows survive a video collection query failure`() {
        val photo = familyRow("photo.heic", stillKey(at = 3_000L, sequence = 3L), "heic", id = 3L)

        val restored = restoreLatestCaptureFromQueryResults(
            imageRows = Result.success(listOf(photo)),
            videoRows = Result.failure(IllegalStateException("video provider unavailable")),
        )

        assertEquals("photo.heic", restored?.preferred?.output)
    }

    @Test
    fun `video rows survive an image collection query failure`() {
        val video = familyRow(
            output = "video.mp4",
            key = videoKey(at = 4_000L, sequence = 4L),
            extension = "mp4",
            id = 4L,
            mime = "video/mp4",
        )

        val restored = restoreLatestCaptureFromQueryResults(
            imageRows = Result.failure(IllegalStateException("image provider unavailable")),
            videoRows = Result.success(listOf(video)),
        )

        assertEquals("video.mp4", restored?.preferred?.output)
    }

    @Test
    fun `failed or empty collection queries have no restored owner`() {
        val failure = Result.failure<List<StoredMediaRow<String>>>(IllegalStateException("provider unavailable"))

        assertNull(restoreLatestCaptureFromQueryResults(failure, failure))
        assertNull(restoreLatestCaptureFromQueryResults(Result.success(emptyList()), failure))
    }

    @Test
    fun `merged query results retain canonical winning family`() {
        val key = stillKey(at = 5_000L, sequence = 5L)
        val olderVideo = familyRow(
            output = "older.mp4",
            key = videoKey(at = 4_000L, sequence = 9L),
            extension = "mp4",
            id = 9L,
            mime = "video/mp4",
        )
        val imageRows = listOf(
            familyRow("raw.dng", key, "dng", id = 6L, mime = "image/dng"),
            familyRow("photo.heic", key, "heic", id = 5L),
        )

        val restored = restoreLatestCaptureFromQueryResults(
            imageRows = Result.success(imageRows),
            videoRows = Result.success(listOf(olderVideo)),
        )

        assertEquals(key, restored?.familyKey)
        assertEquals("photo.heic", restored?.preferred?.output)
        assertEquals(RestoredDeleteScope.CAPTURE_FAMILY, restored?.deleteScope)
    }

    @Test
    fun `legacy rows without a taken timestamp rank by added seconds then modified seconds`() {
        // fallbackCaptureMillis ladder: dateTaken (null/0 is absent) -> dateAdded×1000 -> modified.
        val addedWins = plainRow("added-only", id = 1L, taken = null, added = 5L, modified = 1L)
        val takenLoses = plainRow("has-taken", id = 9L, taken = 4_000L, added = 3L, modified = 9L)
        // If the dateAdded rung were skipped, added-only would rank by modified (1 000 ms) and lose.
        assertEquals(
            "added-only",
            restoreLatestCapture(listOf(takenLoses, addedWins))?.preferred?.output,
        )

        // Zero taken AND zero added fall through to modified seconds.
        val modifiedWins = plainRow("modified-only", id = 2L, taken = 0L, added = 0L, modified = 7L)
        val modifiedLoses = plainRow("older-modified", id = 99L, taken = null, added = 0L, modified = 3L)
        assertEquals(
            "modified-only",
            restoreLatestCapture(listOf(modifiedLoses, modifiedWins))?.preferred?.output,
        )
    }

    @Test
    fun `unknown processed extensions rank below known stills within a family`() {
        val key = stillKey(at = 11_000L, sequence = 1L)
        val webp = familyRow("odd.webp", key, "webp", id = 9L, mime = "image/webp")
        val heic = familyRow("photo.heic", key, "heic", id = 1L)

        val restored = restoreLatestCapture(listOf(webp, heic))

        assertEquals("photo.heic", restored?.preferred?.output)
        assertEquals(listOf("photo.heic", "odd.webp"), restored?.outputs?.map { it.output })
    }

    @Test
    fun `duplicate video family rows order by row identity`() {
        // Two rows carrying the SAME versioned video name model a provider double-index; both are
        // plain VIDEO display candidates and the newer row id owns review.
        val key = videoKey(at = 12_000L, sequence = 2L)
        val older = familyRow("older-row", key, "mp4", id = 3L, mime = "video/mp4")
        val newer = familyRow("newer-row", key, "mp4", id = 8L, mime = "video/mp4")

        val restored = restoreLatestCapture(listOf(older, newer))

        assertEquals("newer-row", restored?.preferred?.output)
        assertEquals(listOf("newer-row", "older-row"), restored?.outputs?.map { it.output })
    }

    @Test
    fun `rows identical through row id still resolve deterministically`() {
        // Exercises the deepest newest-row tie-breakers (collection ordinal + display name): two
        // siblings sharing every date AND the row id can still elect one canonical newest row.
        // A second, older family forces the group comparison that ranks the tied candidates
        // (a lone group is returned without ever computing its rank).
        val key = stillKey(at = 13_000L, sequence = 3L)
        val heic = familyRow("photo.heic", key, "heic", id = 5L)
        val jpeg = familyRow("photo.jpg", key, "jpg", id = 5L, mime = "image/jpeg")
        val older = familyRow("older.heic", stillKey(at = 1_000L, sequence = 1L), "heic", id = 1L)

        val restored = restoreLatestCapture(listOf(heic, jpeg, older))

        assertEquals("photo.heic", restored?.preferred?.output)
        assertEquals(2, restored?.outputs?.size)
    }

    private fun plainRow(
        output: String,
        id: Long,
        taken: Long?,
        added: Long,
        modified: Long,
    ) = StoredMediaRow(
        output = output,
        collection = StoredMediaCollection.IMAGE,
        rowId = id,
        displayName = "$output.jpg", // legacy name: never parses to a family
        mimeType = "image/jpeg",
        dateTakenEpochMillis = taken,
        dateAddedEpochSeconds = added,
        dateModifiedEpochSeconds = modified,
        isPending = false,
    )

    private fun stillKey(at: Long, sequence: Long) =
        CaptureFamilyKey(CaptureFamilyMedia.STILL, at, sequence)

    private fun videoKey(at: Long, sequence: Long) =
        CaptureFamilyKey(CaptureFamilyMedia.VIDEO, at, sequence)

    @Test
    fun `a capture whose ownership was cleared still restores, but only file-only`() {
        // A previous install is one legitimate source of an owner-null row, but public naming
        // syntax cannot distinguish it from an imported lookalike. Restore recognizes the format
        // without claiming authorship, and the delete scope degrades with that uncertainty.
        val key = stillKey(at = 1_700_000_000_000L, sequence = 7L)
        val restored = restoreLatestCapture(
            listOf(
                familyRow("photo.heic", key, "heic", id = 40L, owned = false),
                familyRow("photo.dng", key, "dng", id = 41L, mime = "image/x-adobe-dng", owned = false),
            ),
        )

        assertEquals("photo.heic", restored?.preferred?.output)
        assertEquals(2, restored?.outputs?.size)
        assertEquals(key, restored?.familyKey)
        assertEquals(RestoredDeleteScope.FILE_ONLY, restored?.deleteScope)
        assertTrue(restored?.outputs?.all {
            it.provenance == MediaProvenance.LEGACY_FORMAT_UNVERIFIED
        } == true)
    }

    @Test
    fun `one unowned sibling is enough to drop the whole family's delete promise`() {
        // Partial ownership is reachable: a reinstall clears the rows that existed then, while a
        // later sibling of that same capture is written by the new install and IS owned. Deleting
        // "the capture" would leave the orphaned sibling behind, so the promise must not be made.
        val key = stillKey(at = 1_700_000_500_000L, sequence = 3L)
        val restored = restoreLatestCapture(
            listOf(
                familyRow("photo.heic", key, "heic", id = 50L, owned = true),
                familyRow("photo.dng", key, "dng", id = 51L, mime = "image/x-adobe-dng", owned = false),
            ),
        )

        assertEquals(key, restored?.familyKey)
        assertEquals(RestoredDeleteScope.FILE_ONLY, restored?.deleteScope)
    }

    @Test
    fun `a fully owned family still earns capture-level deletion`() {
        // The regression guard for the two above: degrading unowned rows must not degrade everyone.
        val key = stillKey(at = 1_700_000_900_000L, sequence = 1L)
        val restored = restoreLatestCapture(
            listOf(
                familyRow("photo.heic", key, "heic", id = 60L),
                familyRow("photo.dng", key, "dng", id = 61L, mime = "image/x-adobe-dng"),
            ),
        )

        assertEquals(RestoredDeleteScope.CAPTURE_FAMILY, restored?.deleteScope)
        assertEquals(MediaProvenance.APP_OWNED, restored?.preferred?.provenance)
    }

    @Test
    fun `owner-null lookalike is recognized as format but never verified as app-authored`() {
        val lookalike = contractRow(
            output = "imported-lookalike",
            name = stillKey(1_700_001_000_000L, 2L).displayName("jpg"),
            collection = StoredMediaCollection.IMAGE,
            mime = "image/jpeg",
            owned = false,
        )

        val restored = restoreLatestCapture(listOf(lookalike))

        assertEquals("imported-lookalike", restored?.preferred?.output)
        assertEquals(MediaProvenance.LEGACY_FORMAT_UNVERIFIED, restored?.preferred?.provenance)
        assertEquals(RestoredDeleteScope.FILE_ONLY, restored?.deleteScope)
    }

    @Test
    fun `current-package ownership admits names outside the historical grammar`() {
        val owned = contractRow(
            output = "owned",
            name = "operator-renamed-export.webp",
            collection = StoredMediaCollection.IMAGE,
            mime = "image/webp",
            owned = true,
        )

        assertTrue(isRestorableStoredMediaRow(owned))
        assertEquals("owned", restoreLatestCapture(listOf(owned))?.preferred?.output)
    }

    @Test
    fun `unowned arbitrary directory occupants cannot replace a real capture`() {
        val real = familyRow("real.heic", stillKey(1_000L, 1L), "heic", id = 1L)
        val foreign = contractRow(
            output = "foreign",
            name = "holiday.jpg",
            collection = StoredMediaCollection.IMAGE,
            mime = "image/jpeg",
            owned = false,
            takenAt = 9_000L,
        )

        assertFalse(isRestorableStoredMediaRow(foreign))
        assertEquals("real.heic", restoreLatestCapture(listOf(real, foreign))?.preferred?.output)
        assertNull(restoreLatestCapture(listOf(foreign)))
    }

    @Test
    fun `owner-cleared current names require exact collection extension and MIME`() {
        val stillKey = stillKey(1_700_000_000_000L, 7L)
        val videoKey = videoKey(1_700_000_000_001L, 8L)
        val accepted = listOf(
            contractRow("heic", stillKey.displayName("heic"), StoredMediaCollection.IMAGE, "image/heic", false),
            contractRow("heif", stillKey.displayName("heif"), StoredMediaCollection.IMAGE, "image/heif", false),
            contractRow("jpg", stillKey.displayName("jpg"), StoredMediaCollection.IMAGE, "image/jpeg", false),
            contractRow("jpeg", stillKey.displayName("jpeg"), StoredMediaCollection.IMAGE, "image/jpeg", false),
            contractRow("dng", stillKey.displayName("dng"), StoredMediaCollection.IMAGE, "image/dng", false),
            contractRow("mp4", videoKey.displayName("mp4"), StoredMediaCollection.VIDEO, "video/mp4", false),
        )
        accepted.forEach { assertTrue(it.displayName, isRestorableStoredMediaRow(it)) }

        val rejected = listOf(
            contractRow("wrong mime", stillKey.displayName("heic"), StoredMediaCollection.IMAGE, "image/jpeg", false),
            contractRow("wrong extension", stillKey.displayName("webp"), StoredMediaCollection.IMAGE, "image/webp", false),
            contractRow("wrong collection", stillKey.displayName("heic"), StoredMediaCollection.VIDEO, "video/mp4", false),
            contractRow("video mime", videoKey.displayName("mp4"), StoredMediaCollection.VIDEO, "video/quicktime", false),
            contractRow("missing mime", stillKey.displayName("jpg"), StoredMediaCollection.IMAGE, null, false),
            contractRow(
                "malformed current",
                "IMG_TELECAM_F1_1700000000000_7.heic",
                StoredMediaCollection.IMAGE,
                "image/heic",
                false,
            ),
        )
        rejected.forEach { assertFalse(it.displayName, isRestorableStoredMediaRow(it)) }
    }

    @Test
    fun `owner-cleared historical names require an emitted timestamp and media lane`() {
        val accepted = listOf(
            contractRow(
                "old still",
                "IMG_TELECAM_20260716_120000_123_007.heic",
                StoredMediaCollection.IMAGE,
                "image/heic",
                false,
            ),
            contractRow(
                "old raw",
                "IMG_TELECAM_20260716_120000_123_008.dng",
                StoredMediaCollection.IMAGE,
                "application/x-adobe-dng",
                false,
            ),
            contractRow(
                "old video",
                "VID_TELECAM_20260716_120000_123_009.mp4",
                StoredMediaCollection.VIDEO,
                "video/mp4",
                false,
            ),
        )
        accepted.forEach { assertTrue(it.displayName, isRestorableStoredMediaRow(it)) }

        val rejected = listOf(
            contractRow(
                "invalid calendar",
                "IMG_TELECAM_20261340_256199_999_001.heic",
                StoredMediaCollection.IMAGE,
                "image/heic",
                false,
            ),
            contractRow(
                "still in video",
                "IMG_TELECAM_20260716_120000_123_001.mp4",
                StoredMediaCollection.VIDEO,
                "video/mp4",
                false,
            ),
            contractRow(
                "video in images",
                "VID_TELECAM_20260716_120000_123_001.jpg",
                StoredMediaCollection.IMAGE,
                "image/jpeg",
                false,
            ),
        )
        rejected.forEach { assertFalse(it.displayName, isRestorableStoredMediaRow(it)) }
    }

    @Test
    fun `provider null-owner rules are collection-scoped and MIME paired`() {
        val imageRules = nullOwnerRestoreQueryRules(StoredMediaCollection.IMAGE)
        val videoRules = nullOwnerRestoreQueryRules(StoredMediaCollection.VIDEO)

        assertEquals(18, imageRules.size)
        assertTrue(imageRules.all { it.displayNameGlob.startsWith("IMG_TELECAM_") })
        assertTrue(imageRules.all { "?" !in it.displayNameGlob && "[0-9]" in it.displayNameGlob })
        assertTrue(imageRules.none { it.displayNameGlob.endsWith(".mp4") || it.mimeType.startsWith("video/") })
        assertEquals(
            setOf("video/mp4"),
            videoRules.map { it.mimeType }.toSet(),
        )
        assertEquals(2, videoRules.size)
        assertTrue(videoRules.all { it.displayNameGlob.startsWith("VID_TELECAM_") && it.displayNameGlob.endsWith(".mp4") })
    }

    @Test
    fun `provider owner policy keeps unrestricted names exclusive to the current package`() {
        val policy = restoreOwnerQueryPolicy(
            collection = StoredMediaCollection.VIDEO,
            packageName = "me.hletrd.telecampro",
            ownerColumn = "owner",
            displayNameColumn = "name",
            mimeTypeColumn = "mime",
        )

        assertTrue(policy.selection.startsWith("(owner = ? OR (owner IS NULL AND ("))
        assertTrue(policy.selection.contains("name GLOB ? AND mime = ?"))
        assertFalse(policy.selection.contains("owner IS NULL)"))
        assertEquals("me.hletrd.telecampro", policy.selectionArgs.first())
        assertEquals(5, policy.selectionArgs.size)
        assertEquals(
            listOf("video/mp4", "video/mp4"),
            policy.selectionArgs.drop(1).chunked(2).map { it[1] },
        )
    }

    private fun familyRow(
        output: String,
        key: CaptureFamilyKey,
        extension: String,
        id: Long,
        mime: String = "image/heic",
        pending: Boolean = false,
        present: Boolean = true,
        owned: Boolean = true,
    ) = StoredMediaRow(
        output = output,
        collection = if (key.media == CaptureFamilyMedia.STILL) {
            StoredMediaCollection.IMAGE
        } else {
            StoredMediaCollection.VIDEO
        },
        rowId = id,
        displayName = key.displayName(extension),
        mimeType = mime,
        // Deliberately noisy: canonical family order must come from its durable key.
        dateTakenEpochMillis = key.capturedAtEpochMillis + 100_000L,
        dateAddedEpochSeconds = key.capturedAtEpochMillis / 1_000L,
        dateModifiedEpochSeconds = key.capturedAtEpochMillis / 1_000L,
        isPending = pending,
        isPresent = present,
        isOwned = owned,
    )

    private fun contractRow(
        output: String,
        name: String,
        collection: StoredMediaCollection,
        mime: String?,
        owned: Boolean,
        takenAt: Long = 2_000L,
    ) = StoredMediaRow(
        output = output,
        collection = collection,
        rowId = takenAt,
        displayName = name,
        mimeType = mime,
        dateTakenEpochMillis = takenAt,
        dateAddedEpochSeconds = takenAt / 1_000L,
        dateModifiedEpochSeconds = takenAt / 1_000L,
        isPending = false,
        isOwned = owned,
    )

    private fun legacyRow(
        output: String,
        name: String,
        id: Long,
        takenAt: Long,
        mime: String = "image/heic",
    ) = StoredMediaRow(
        output = output,
        collection = StoredMediaCollection.IMAGE,
        rowId = id,
        displayName = name,
        mimeType = mime,
        dateTakenEpochMillis = takenAt,
        dateAddedEpochSeconds = takenAt / 1_000L,
        dateModifiedEpochSeconds = takenAt / 1_000L,
        isPending = false,
    )
}
