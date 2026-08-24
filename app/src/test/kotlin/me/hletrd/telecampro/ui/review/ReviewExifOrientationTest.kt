package me.hletrd.telecampro.ui.review

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewExifOrientationTest {
    @Test
    fun `all mirrored and rotated EXIF orientations preserve asymmetric pixel truth`() {
        val expected = mapOf(
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(2, 1, 4, 3, 6, 5),
            ExifInterface.ORIENTATION_ROTATE_180 to listOf(6, 5, 4, 3, 2, 1),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(5, 6, 3, 4, 1, 2),
            ExifInterface.ORIENTATION_TRANSPOSE to listOf(1, 3, 5, 2, 4, 6),
            ExifInterface.ORIENTATION_ROTATE_90 to listOf(5, 3, 1, 6, 4, 2),
            ExifInterface.ORIENTATION_TRANSVERSE to listOf(6, 4, 2, 5, 3, 1),
            ExifInterface.ORIENTATION_ROTATE_270 to listOf(2, 4, 6, 1, 3, 5),
        )

        expected.forEach { (orientation, pixels) ->
            val source = asymmetricBitmap()
            assertEquals(listOf(1, 2, 3, 4, 5, 6), source.pixelIds())
            val transformed = applyReviewExifTransform(source, requireNotNull(reviewExifTransform(orientation)))

            assertEquals("orientation=$orientation", pixels, transformed.pixelIds())
            val swapsDimensions = orientation in setOf(
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSVERSE,
                ExifInterface.ORIENTATION_ROTATE_270,
            )
            assertEquals(if (swapsDimensions) 3 else 2, transformed.width)
            assertEquals(if (swapsDimensions) 2 else 3, transformed.height)
            source.recycle()
            transformed.recycle()
        }
    }

    @Test
    fun `normal undefined and unknown orientations are identity`() {
        assertNull(reviewExifTransform(ExifInterface.ORIENTATION_NORMAL))
        assertNull(reviewExifTransform(ExifInterface.ORIENTATION_UNDEFINED))
        assertNull(reviewExifTransform(99))
    }

    @Test
    fun `review transform preserves source bitmap config and rotated dimensions`() {
        val source = Bitmap.createBitmap(2, 3, Bitmap.Config.RGB_565)

        val transformed = applyReviewExifTransform(
            source,
            requireNotNull(reviewExifTransform(ExifInterface.ORIENTATION_ROTATE_90)),
        )

        assertEquals(3, transformed.width)
        assertEquals(2, transformed.height)
        assertEquals(Bitmap.Config.RGB_565, transformed.config)
        source.recycle()
        transformed.recycle()
    }

    private fun asymmetricBitmap(): Bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(6) { index -> color(index + 1) }, 0, 2, 0, 0, 2, 3)
    }

    private fun Bitmap.pixelIds(): List<Int> = List(width * height) { index ->
        val pixel = getPixel(index % width, index / width)
        android.graphics.Color.red(pixel)
    }

    private fun color(id: Int): Int = android.graphics.Color.rgb(id, 0, 0)
}
