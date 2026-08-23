package me.hletrd.telecampro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import me.hletrd.telecampro.camera.CameraStatusLivePriority
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h340dp-xxhdpi")
class CriticalStatusRotationTest {

    @get:Rule
    val compose = createComposeRule()

    private val rotation = mutableFloatStateOf(0f)

    @Test
    fun `critical central status stays bounded at held angles and two-x font`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TeleCamProTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.testTag("critical-status")) {
                            CriticalCameraStatusPlate(
                                message = "Unable to save capture to storage",
                                livePriority = CameraStatusLivePriority.ASSERTIVE,
                                overlayRotation = rotation.floatValue,
                            )
                        }
                    }
                }
            }
        }

        listOf(0f, 90f, 270f).forEach { degrees ->
            rotation.floatValue = degrees
            compose.waitForIdle()
            compose.onNodeWithTag("critical-status").assertIsDisplayed()
            val bounds = compose.onNodeWithTag("critical-status").fetchSemanticsNode().boundsInRoot
            assertTrue("status exceeded compact width at $degrees: $bounds", bounds.left >= 0f && bounds.right <= 320f + 1f)
            assertTrue("status exceeded compact height at $degrees: $bounds", bounds.top >= 0f && bounds.bottom <= 340f + 1f)
        }
    }
}
