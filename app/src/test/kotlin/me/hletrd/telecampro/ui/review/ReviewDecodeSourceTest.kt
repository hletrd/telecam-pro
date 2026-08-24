package me.hletrd.telecampro.ui.review

import android.graphics.Bitmap
import android.graphics.Color
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
    fun `trusted valid JPEG above unverified spool ceiling decodes through fresh handles`() {
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
        var opens = 0
        val source = openReviewDecodeSource(
            cacheDirectory = cache,
            provenance = MediaProvenance.APP_OWNED,
            openProviderInput = {
                opens++
                FileInputStream(jpeg)
            },
        )

        val decoded = requireNotNull(source).use { decodeReviewBitmap(it, maxDim = 240) }

        assertNotNull(decoded)
        decoded?.recycle()
        assertTrue("bounds, pixels, and EXIF need fresh provider handles", opens >= 3)
        assertFalse(cache.listFiles().orEmpty().any { it.name.startsWith("review-source-") })
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
}
