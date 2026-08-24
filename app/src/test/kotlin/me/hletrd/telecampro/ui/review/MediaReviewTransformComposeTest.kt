package me.hletrd.telecampro.ui.review

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaReviewTransformComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `composed transform clamps one four and twelve x recompositions`() {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).asImageBitmap()
        val scale = mutableFloatStateOf(1f)
        val offset = mutableStateOf(Offset(99_999f, 99_999f))
        val geometry = reviewStillGeometry(300, 200, bitmap.width, bitmap.height)
        var applied: ReviewStillTransform? = null

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(300.dp, 200.dp)) {
                    ReviewStillImage(
                        bitmap = bitmap,
                        contentDescription = "review still",
                        scale = scale.floatValue,
                        offset = offset.value,
                        dismissDrag = 0f,
                        geometry = geometry,
                        onTransformApplied = { applied = it },
                    )
                }
            }
        }

        compose.runOnIdle {
            assertTransform(1f, 0f, 0f, applied)
            scale.floatValue = 4f
        }
        compose.runOnIdle {
            assertTransform(4f, 50f, 300f, applied)
            scale.floatValue = 12f
            offset.value = Offset(-99_999f, -99_999f)
        }
        compose.runOnIdle {
            assertTransform(12f, -450f, -1100f, applied)
        }
    }

    private fun assertTransform(
        scale: Float,
        x: Float,
        y: Float,
        actual: ReviewStillTransform?,
    ) {
        requireNotNull(actual)
        assertEquals(scale, actual.scale, 0.001f)
        assertEquals(x, actual.translation.x, 0.001f)
        assertEquals(y, actual.translation.y, 0.001f)
        assertEquals(1f, actual.alpha, 0.001f)
    }
}
