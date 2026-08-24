package me.hletrd.telecampro.ui.review

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import me.hletrd.telecampro.R
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h480dp-mdpi")
class GalleryThumbComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `production gallery surface paints and names every thumbnail state truthfully`() {
        val readyPixels = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        }
        val states = listOf(
            Entry("empty", GalleryThumbState.Empty, R.string.a11y_no_capture_to_review),
            Entry(
                "still-loading",
                GalleryThumbState.Loading(ReviewMediaKind.STILL),
                R.string.a11y_review_last_photo,
            ),
            Entry(
                "video-loading",
                GalleryThumbState.Loading(ReviewMediaKind.VIDEO),
                R.string.a11y_review_last_video,
            ),
            Entry(
                "raw-loading",
                GalleryThumbState.Loading(ReviewMediaKind.RAW),
                R.string.a11y_review_last_raw,
            ),
            Entry(
                "still-failed",
                GalleryThumbState.Failed(ReviewMediaKind.STILL),
                R.string.a11y_review_last_photo,
            ),
            Entry(
                "video-failed",
                GalleryThumbState.Failed(ReviewMediaKind.VIDEO),
                R.string.a11y_review_last_video,
            ),
            Entry(
                "raw-ready",
                GalleryThumbState.Ready(ReviewMediaKind.RAW),
                R.string.a11y_review_last_raw,
            ),
            Entry(
                "still-ready",
                GalleryThumbState.Ready(ReviewMediaKind.STILL, ReviewBitmap(readyPixels)),
                R.string.a11y_review_last_photo,
            ),
        )

        compose.setContent {
            TeleCamProTheme {
                Column {
                    states.chunked(4).forEach { row ->
                        Row {
                            row.forEach { entry ->
                                GalleryThumbSurface(
                                    state = entry.state,
                                    onClick = {},
                                    modifier = Modifier.testTag(entry.tag),
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val context = RuntimeEnvironment.getApplication()
        states.forEach { entry ->
            compose.onNodeWithTag(entry.tag)
                .assertContentDescriptionEquals(context.getString(entry.descriptionRes))
        }

        val signatures = states.associate { entry ->
            val pixels = compose.onNodeWithTag(entry.tag).captureToImage().toPixelMap()
            entry.tag to buildList {
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) add(pixels[x, y].toArgb())
                }
            }.hashCode()
        }
        assertEquals("every visual branch must have distinct pixels: $signatures", states.size, signatures.values.toSet().size)

        val ready = compose.onNodeWithTag("still-ready").captureToImage().toPixelMap()
        assertTrue(
            "ready bitmap pixels must replace the placeholder",
            ready[ready.width / 2, ready.height / 2] == Color.Magenta,
        )
    }

    private data class Entry(
        val tag: String,
        val state: GalleryThumbState,
        val descriptionRes: Int,
    )
}
