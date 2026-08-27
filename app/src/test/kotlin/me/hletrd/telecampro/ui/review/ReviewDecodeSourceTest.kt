package me.hletrd.telecampro.ui.review

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import me.hletrd.telecampro.storage.MediaProvenance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReviewDecodeSourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `trusted valid JPEG above unverified spool ceiling decodes from one frozen spool`() {
        val cache = temporaryFolder.newFolder("trusted-cache")
        val jpeg = temporaryFolder.newFile("trusted-large.jpg")
        val seed = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        FileOutputStream(jpeg).use { output ->
            assertTrue(seed.compress(Bitmap.CompressFormat.JPEG, 95, output))
        }
        seed.recycle()
        // Sparse trailing bytes keep the JPEG structurally valid while proving this path has no
        // whole-compressed-file 64 MiB admission. BitmapFactory stops at the JPEG EOI marker.
        RandomAccessFile(jpeg, "rw").use { file ->
            file.setLength(REVIEW_SOURCE_MAX_BYTES + 1L)
        }
        val budget = ReviewSourceByteBudget(REVIEW_SOURCE_MAX_BYTES + 1L)
        var opens = 0
        val source = openReviewDecodeSource(
            cacheDirectory = cache,
            provenance = MediaProvenance.APP_OWNED,
            openProviderInput = {
                opens++
                FileInputStream(jpeg)
            },
            budget = budget,
            spoolDirectoryOwner = ReviewSpoolDirectoryOwner("3333333333333333"),
        )

        val frozen = requireNotNull(source) as ReviewSourceSpool
        assertEquals(REVIEW_SOURCE_MAX_BYTES + 1L, frozen.sizeBytes)
        assertEquals(REVIEW_SOURCE_MAX_BYTES + 1L, budget.usedBytes())
        val decoded = frozen.use { decodeReviewBitmap(it, maxDim = 240) }

        assertNotNull(decoded)
        decoded?.recycle()
        assertEquals("provider must be consumed once", 1, opens)
        assertEquals(0L, budget.usedBytes())
        assertTrue(File(cache, REVIEW_SPOOL_DIRECTORY_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `trusted alternating provider freezes bounds pixels and EXIF from its first identity`() {
        val cache = temporaryFolder.newFolder("trusted-alternating-cache")
        val boundsSource = jpegFixture(
            name = "small-bounds.jpg",
            width = 16,
            height = 24,
            color = Color.BLUE,
            orientation = ExifInterface.ORIENTATION_ROTATE_90,
        )
        val pixelSource = jpegFixture(
            name = "large-pixels.jpg",
            width = 64,
            height = 32,
            color = Color.RED,
            orientation = ExifInterface.ORIENTATION_NORMAL,
        )
        val exifSource = jpegFixture(
            name = "changed-exif.jpg",
            width = 64,
            height = 32,
            color = Color.GREEN,
            orientation = ExifInterface.ORIENTATION_ROTATE_180,
        )
        val identities = listOf(boundsSource, pixelSource, exifSource)
        var opens = 0
        val source = requireNotNull(
            openReviewDecodeSource(
                cacheDirectory = cache,
                provenance = MediaProvenance.APP_OWNED,
                openProviderInput = {
                    FileInputStream(identities[opens.coerceAtMost(identities.lastIndex)]).also {
                        opens++
                    }
                },
                budget = ReviewSourceByteBudget(2L * 1024L * 1024L),
                spoolDirectoryOwner = ReviewSpoolDirectoryOwner("4444444444444444"),
            ),
        )

        val decoded = source.use { decodeReviewBitmap(it, maxDim = 128) }

        assertEquals("later mutable provider identities must never be opened", 1, opens)
        assertNotNull(decoded)
        requireNotNull(decoded)
        assertEquals(24, decoded.width)
        assertEquals(16, decoded.height)
        decoded.recycle()
    }

    @Test
    fun `fullscreen still uses one provider identity for metadata orientation bounds and pixels`() {
        val cache = temporaryFolder.newFolder("full-review-identity")
        val first = jpegFixture(
            name = "review-first.jpg",
            width = 16,
            height = 24,
            color = Color.BLUE,
            orientation = ExifInterface.ORIENTATION_ROTATE_90,
            iso = "125",
            exposureSeconds = "0.008",
            focal35 = "300",
        )
        val replacement = jpegFixture(
            name = "review-replacement.jpg",
            width = 80,
            height = 40,
            color = Color.RED,
            orientation = ExifInterface.ORIENTATION_NORMAL,
            iso = "6400",
            exposureSeconds = "1",
            focal35 = "23",
        )
        val identities = listOf(first, replacement)
        var opens = 0

        val loaded = loadFrozenStillReview(
            cacheDirectory = cache,
            provenance = MediaProvenance.APP_OWNED,
            columns = ReviewMediaColumns("request-bound.jpg", 123L, 16, 24),
            openProviderInput = {
                FileInputStream(identities[opens.coerceAtMost(identities.lastIndex)]).also { opens++ }
            },
            decodePixels = true,
            maxDim = 128,
            budget = ReviewSourceByteBudget(2L * 1024L * 1024L),
            spoolDirectoryOwner = ReviewSpoolDirectoryOwner("5555555555555555"),
            cleanupOwner = ReviewSpoolCleanupOwner(),
        )

        assertEquals("fullscreen review must open MediaProvider once", 1, opens)
        val ready = loaded.bitmap as ReviewBitmapLoad.Ready
        assertEquals(24, ready.bitmap.image.width)
        assertEquals(16, ready.bitmap.image.height)
        assertEquals("request-bound.jpg", loaded.metadata?.name)
        assertEquals(123L, loaded.metadata?.sizeBytes)
        assertEquals("ISO 125 · 1/125s · 300 mm", loaded.metadata?.exifLine)
        loaded.dispose()
        assertTrue(File(cache, REVIEW_SPOOL_DIRECTORY_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `over ceiling unverified source omits EXIF only and never reopens provider`() {
        val cache = temporaryFolder.newFolder("unverified-exif-ceiling")
        val budget = ReviewSourceByteBudget(32L)
        var opens = 0

        val loaded = loadFrozenStillReview(
            cacheDirectory = cache,
            provenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
            columns = ReviewMediaColumns("legacy.jpg", 9L, 2, 2),
            openProviderInput = {
                opens++
                ByteArrayInputStream(ByteArray(9))
            },
            decodePixels = true,
            unverifiedMaxBytes = 8L,
            budget = budget,
            spoolDirectoryOwner = ReviewSpoolDirectoryOwner("6666666666666666"),
            cleanupOwner = ReviewSpoolCleanupOwner(),
        )

        assertEquals(1, opens)
        assertEquals(ReviewBitmapLoad.Failed, loaded.bitmap)
        assertEquals("legacy.jpg", loaded.metadata?.name)
        assertEquals(9L, loaded.metadata?.sizeBytes)
        assertNull(loaded.metadata?.exifLine)
        assertEquals(0L, budget.usedBytes())
        assertTrue(File(cache, REVIEW_SPOOL_DIRECTORY_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `metadata keeps whichever independent facts are available`() {
        assertEquals(
            ReviewMetadata(null, null, null, null, "ISO 200"),
            reviewMetadataOf(columns = null, exifLine = "ISO 200"),
        )
        assertEquals(
            ReviewMetadata("capture.jpg", 42L, null, null, null),
            reviewMetadataOf(ReviewMediaColumns("capture.jpg", 42L), exifLine = null),
        )
        assertNull(reviewMetadataOf(columns = null, exifLine = null))
    }

    @Test
    fun `trusted disk policy admits hi res while retaining a bounded free space share`() {
        val justAboveUnverified = REVIEW_SOURCE_MAX_BYTES + 1L

        assertEquals(
            justAboveUnverified,
            trustedReviewSourceMaxBytes(justAboveUnverified * 4L),
        )
        assertEquals(
            REVIEW_TRUSTED_SOURCE_MAX_BYTES,
            trustedReviewSourceMaxBytes(Long.MAX_VALUE),
        )
        assertEquals(
            2L * REVIEW_TRUSTED_SOURCE_MAX_BYTES,
            REVIEW_SOURCE_PROCESS_MAX_BYTES,
        )
        assertEquals(0L, trustedReviewSourceMaxBytes(0L))
    }

    @Test
    fun `owner-unverified source keeps one immutable bounded spool`() {
        val cache = temporaryFolder.newFolder("unverified-cache")
        val providerBytes = byteArrayOf(1, 2, 3, 4, 5)
        val expected = providerBytes.copyOf()
        val budget = ReviewSourceByteBudget(16L)
        var opens = 0
        val source = requireNotNull(
            openReviewDecodeSource(
                cacheDirectory = cache,
                provenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
                openProviderInput = {
                    opens++
                    ByteArrayInputStream(providerBytes)
                },
                unverifiedMaxBytes = 8L,
                budget = budget,
                spoolDirectoryOwner = ReviewSpoolDirectoryOwner("1111111111111111"),
            ),
        )
        providerBytes.fill(99)

        assertArrayEquals(expected, source.openInputStream().use { it.readBytes() })
        assertArrayEquals(expected, source.openInputStream().use { it.readBytes() })
        assertEquals("provider is consumed once", 1, opens)
        source.close()
        assertEquals(0L, budget.usedBytes())
    }

    @Test
    fun `owner-unverified source over its bound is refused and cleaned exactly`() {
        val cache = temporaryFolder.newFolder("unverified-overflow")
        val budget = ReviewSourceByteBudget(16L)
        var opens = 0

        val source = openReviewDecodeSource(
            cacheDirectory = cache,
            provenance = MediaProvenance.LEGACY_FORMAT_UNVERIFIED,
            openProviderInput = {
                opens++
                ByteArrayInputStream(ByteArray(9))
            },
            unverifiedMaxBytes = 8L,
            budget = budget,
            spoolDirectoryOwner = ReviewSpoolDirectoryOwner("2222222222222222"),
        )

        assertNull(source)
        assertEquals(1, opens)
        assertEquals(0L, budget.usedBytes())
        assertTrue(File(cache, REVIEW_SPOOL_DIRECTORY_NAME).listFiles().orEmpty().isEmpty())
    }

    private fun jpegFixture(
        name: String,
        width: Int,
        height: Int,
        color: Int,
        orientation: Int,
        iso: String? = null,
        exposureSeconds: String? = null,
        focal35: String? = null,
    ): File {
        val file = temporaryFolder.newFile(name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        FileOutputStream(file).use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso)
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureSeconds)
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, focal35)
            saveAttributes()
        }
        return file
    }
}
