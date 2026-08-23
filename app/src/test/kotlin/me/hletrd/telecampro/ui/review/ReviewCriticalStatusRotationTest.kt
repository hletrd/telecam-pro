package me.hletrd.telecampro.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.R
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h340dp-xxhdpi")
class ReviewCriticalStatusRotationTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = mutableStateOf<ReviewCriticalUiState>(
        ReviewCriticalUiState.Error(R.string.review_error_open_image),
    )
    private val rotation = mutableFloatStateOf(0f)

    @Test
    fun `error loading and RAW copy remain rendered through held rotations at two-x font`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TeleCamProTheme {
                    Box(Modifier.size(320.dp, 340.dp)) {
                        ReviewCriticalStatus(
                            state = state.value,
                            overlayRotation = rotation.floatValue,
                            onRetry = {},
                            modifier = Modifier.size(320.dp, 340.dp),
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Unable to open this image.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()

        compose.runOnIdle {
            rotation.floatValue = 90f
            state.value = ReviewCriticalUiState.Loading
        }
        compose.onNodeWithText("Loading review…").assertIsDisplayed()

        compose.runOnIdle {
            rotation.floatValue = 270f
            state.value = ReviewCriticalUiState.Raw
        }
        compose.onNodeWithText("RAW").assertIsDisplayed()
        compose.onNodeWithText("DNG").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ko-rKR-w320dp-h340dp-xxhdpi")
    fun `capacity exhaustion renders assertive Korean restart guidance`() {
        compose.setContent {
            TeleCamProTheme {
                ReviewCriticalStatus(
                    state = ReviewCriticalUiState.RestartRequired,
                    overlayRotation = 90f,
                    onRetry = {},
                    modifier = Modifier.size(320.dp, 340.dp),
                )
            }
        }

        compose.onNodeWithText("미디어를 확인할 수 없습니다. 앱을 다시 열어 주세요.")
            .assertIsDisplayed()
    }
}
