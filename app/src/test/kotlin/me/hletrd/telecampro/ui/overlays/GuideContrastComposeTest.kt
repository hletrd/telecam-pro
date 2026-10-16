package me.hletrd.telecampro.ui.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.GridType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w800dp-h800dp-mdpi")
class GuideContrastComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun `guides retain contrast on a bright frame`() = assertBackground("bright", Color.White)

    @Test fun `guides retain contrast on a dark frame`() = assertBackground("dark", Color.Black)

    @Test fun `guides retain contrast on a midtone frame`() =
        assertBackground("midtone", Color(0xFF777777))

    private fun assertBackground(
        prefix: String,
        background: Color,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(TEST_DENSITY)) {
                Column {
                    GridType.entries.filter { it != GridType.NONE }.chunked(2).forEach { types ->
                        Row {
                            types.forEach { type ->
                                Fixture("$prefix-grid-${type.name}", background) {
                                    GridOverlay(type, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    Row {
                        Fixture("$prefix-frame", background) {
                            FrameLinesOverlay(FrameLineType.SQUARE, Modifier.fillMaxSize())
                        }
                        Fixture("$prefix-level", background) {
                            LevelOverlay(Modifier.fillMaxSize(), rollDegrees = 10f, deviceOrientation = 0)
                        }
                        Fixture("$prefix-level-terminal", background) {
                            LevelOverlay(Modifier.fillMaxSize(), rollDegrees = 0f, deviceOrientation = 0)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertRenderedEdge("$prefix-grid-THIRDS", background, 37..43, 30..90)
        assertRenderedEdge("$prefix-grid-GOLDEN", background, 43..49, 30..90)
        assertRenderedEdge("$prefix-grid-SQUARE", background, 22..28, 30..90)
        assertRenderedEdge("$prefix-grid-CENTER", background, 57..63, 43..57)
        assertRenderedEdge("$prefix-frame", background, 7..13, 30..70)
        assertRenderedEdge("$prefix-level", background, 43..55, 46..54)
        assertRenderedEdge("$prefix-level-terminal", background, 43..55, 46..54)
    }

    @Composable
    private fun Fixture(tag: String, background: Color, content: @Composable () -> Unit) {
        Box(
            Modifier
                .size(120.dp, 100.dp)
                .background(background)
                .testTag(tag),
        ) { content() }
    }

    private fun assertRenderedEdge(
        tag: String,
        background: Color,
        xRange: IntRange,
        yRange: IntRange,
    ) {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        assertTrue(
            "$tag painted outside its guide geometry",
            colorDistance(pixels[(5 * TEST_DENSITY).toInt(), (5 * TEST_DENSITY).toInt()], background) < 0.02f,
        )
        val pixelX = (xRange.first * TEST_DENSITY).toInt()..(xRange.last * TEST_DENSITY).toInt()
        val pixelY = (yRange.first * TEST_DENSITY).toInt()..(yRange.last * TEST_DENSITY).toInt()
        val best = maxContrast(pixels, background, pixelX, pixelY)
        assertTrue("$tag best rendered contrast was $best", best >= 3.0)
    }

    private fun maxContrast(
        pixels: PixelMap,
        background: Color,
        xRange: IntRange,
        yRange: IntRange,
    ): Double = xRange.flatMap { x -> yRange.map { y -> contrast(pixels[x, y], background) } }.max()

    private fun contrast(first: Color, second: Color): Double {
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    private fun colorDistance(first: Color, second: Color): Float =
        kotlin.math.abs(first.red - second.red) + kotlin.math.abs(first.green - second.green) +
            kotlin.math.abs(first.blue - second.blue)

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double =
            if (value <= 0.04045f) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private companion object {
        const val TEST_DENSITY = 2f
    }
}
