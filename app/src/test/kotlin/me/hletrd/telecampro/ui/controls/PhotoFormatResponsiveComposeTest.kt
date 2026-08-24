package me.hletrd.telecampro.ui.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp-xxhdpi")
class PhotoFormatResponsiveComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var formats: MutableState<PhotoFormats>

    private fun show(
        initial: PhotoFormats,
        viewportWidthDp: Int = 212,
    ) {
        formats = mutableStateOf(initial)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                TeleCamProTheme {
                    // 212 dp is the real 320 dp phone lane after rail and page insets. The narrower
                    // fixture below deterministically exercises actual overflow because Robolectric
                    // does not expand Material chip text with fontScale exactly like a device does.
                    Box(Modifier.requiredWidth(viewportWidthDp.dp).testTag(VIEWPORT_TAG)) {
                        PhotoFormatToggles(
                            formats = formats.value,
                            onSetPhotoFormats = { formats.value = it },
                            processedAvailable = true,
                            rawAvailable = true,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun formatNode(name: String) = compose.onNodeWithContentDescription(
        segmentedOptionName("Output", name),
    )

    private fun viewportBounds(): Rect = compose.onNodeWithTag(VIEWPORT_TAG)
        .fetchSemanticsNode().boundsInRoot

    private fun assertReachable(name: String) {
        val viewport = viewportBounds()
        val bounds = formatNode(name).fetchSemanticsNode().boundsInRoot
        assertTrue("$name escaped horizontal viewport: $bounds / $viewport", bounds.left >= viewport.left - 1f)
        assertTrue("$name escaped horizontal viewport: $bounds / $viewport", bounds.right <= viewport.right + 1f)
    }

    @Test
    fun `selected trailing format is brought into the narrow two-x RTL viewport`() {
        // Every selected chip carries a leading check, which is the real maximum-width state.
        show(
            PhotoFormats(heif = true, jpeg = true, dngRaw = true),
            // Below the three 48 dp touch floors plus two 6 dp gaps, so overflow is independent of
            // Robolectric's synthetic font metrics.
            viewportWidthDp = 120,
        )

        val scrollRange = compose.onNode(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                SemanticsProperties.HorizontalScrollAxisRange,
            ),
        ).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("fixture did not overflow", scrollRange.maxValue() > 0f)
        assertTrue("trailing selection did not move away from logical start", scrollRange.value() > 0f)
        assertReachable("DNG")
    }

    @Test
    fun `every multi-select format remains scrollable clickable and context-named at two-x font`() {
        show(PhotoFormats(heif = false, jpeg = false, dngRaw = false))

        listOf("HEIF", "JPEG", "DNG").forEach { name ->
            formatNode(name)
                .assertIsEnabled()
                .assertHasClickAction()
                .performScrollTo()
            compose.waitForIdle()
            assertReachable(name)
            formatNode(name).performClick()
            compose.waitForIdle()
        }

        assertEquals(PhotoFormats(heif = true, jpeg = true, dngRaw = true), formats.value)
        val scrollRange = compose.onNode(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                SemanticsProperties.HorizontalScrollAxisRange,
            ),
        ).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        // The exact 212 dp phone lane can fit under Robolectric's narrower synthetic glyph metrics,
        // but the horizontal range itself is the regression contract: the old plain Row exposed no
        // scroll semantics and could not adapt when real device/font metrics overflowed.
        assertTrue("format row exposed an invalid scroll range", scrollRange.maxValue().isFinite())
        formatNode("DNG").performScrollTo()
        compose.waitForIdle()
        assertReachable("DNG")
    }

    private companion object {
        const val VIEWPORT_TAG = "photo-format-viewport"
    }
}
