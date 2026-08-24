package me.hletrd.telecampro.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import me.hletrd.telecampro.camera.AfIndication
import me.hletrd.telecampro.camera.ViewfinderFocusActionAvailability
import me.hletrd.telecampro.ui.overlays.FocusResultLiveRegion
import me.hletrd.telecampro.ui.overlays.FocusReticle
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class ViewfinderAccessibilityComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun localizedContext(language: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun `horizon state is coarsened localized and never a live region`() {
        compose.setContent {
            TeleCamProTheme {
                Column {
                    HorizonNode("disabled", localizedContext("en"), enabled = false, roll = 6f)
                    HorizonNode("invalid", localizedContext("en"), enabled = true, roll = Float.NaN)
                    HorizonNode("level-en", localizedContext("en"), enabled = true, roll = 0f)
                    HorizonNode("left-en", localizedContext("en"), enabled = true, roll = -6f)
                    HorizonNode("right-en", localizedContext("en"), enabled = true, roll = 6f)
                    HorizonNode("level-ko", localizedContext("ko"), enabled = true, roll = 0f)
                    HorizonNode("left-ko", localizedContext("ko"), enabled = true, roll = -6f)
                    HorizonNode("right-ko", localizedContext("ko"), enabled = true, roll = 6f)
                    HorizonNode(
                        "combined-en",
                        localizedContext("en"),
                        enabled = true,
                        roll = 0f,
                        afIndication = AfIndication.FOCUSED,
                        afActive = true,
                    )
                }
            }
        }

        val disabled = compose.onNodeWithTag("disabled").fetchSemanticsNode().config
        assertFalse(disabled.contains(SemanticsProperties.StateDescription))
        val invalid = compose.onNodeWithTag("invalid").fetchSemanticsNode().config
        assertFalse(invalid.contains(SemanticsProperties.StateDescription))
        assertState("level-en", "Horizon level")
        assertState("left-en", "Horizon tilted left 5°")
        assertState("right-en", "Horizon tilted right 5°")
        assertState("level-ko", "수평 맞음")
        assertState("left-ko", "왼쪽으로 5° 기울어짐")
        assertState("right-ko", "오른쪽으로 5° 기울어짐")
        assertState("combined-en", "Focus locked, Horizon level")
        listOf(
            "level-en", "left-en", "right-en", "level-ko", "left-ko", "right-ko", "combined-en",
        )
            .forEach { tag ->
                assertFalse(
                    compose.onNodeWithTag(tag).fetchSemanticsNode().config
                        .contains(SemanticsProperties.LiveRegion),
                )
            }
    }

    @Test
    fun `AF state keeps exactly one durable viewfinder identity`() {
        val indication = mutableStateOf(AfIndication.IDLE)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("en")) {
                TeleCamProTheme {
                    Box {
                        val state = localizedViewfinderStateDescription(
                            afIndication = indication.value,
                            afActive = true,
                            levelEnabled = false,
                            levelRollDegrees = 0f,
                            deviceOrientation = 0,
                        )
                        Box(
                            Modifier
                                .size(200.dp)
                                .testTag("viewfinder")
                                .viewfinderFocusSemantics(
                                    contentDescription = "Camera viewfinder",
                                    stateDescription = state,
                                    availability = ViewfinderFocusActionAvailability(false, false),
                                    focusAtCenterLabel = "Focus at center",
                                    resetFocusPointLabel = "Reset focus point",
                                    onFocusAtCenter = {},
                                    onResetFocusPoint = {},
                                ),
                        )
                        FocusReticle(
                            point = 0.5f to 0.5f,
                            indication = indication.value,
                            modifier = Modifier.size(200.dp),
                        )
                        FocusResultLiveRegion(
                            indication = indication.value,
                            active = true,
                            modifier = Modifier.size(1.dp),
                        )
                    }
                }
            }
        }

        val expected = linkedMapOf(
            AfIndication.IDLE to "Focus point",
            AfIndication.SCANNING to "Autofocus searching",
            AfIndication.FOCUSED to "Focus locked",
            AfIndication.FAILED to "Autofocus failed",
        )
        expected.forEach { (state, description) ->
            indication.value = state
            compose.waitForIdle()
            assertEquals(
                1,
                compose.onAllNodesWithContentDescription("Camera viewfinder")
                    .fetchSemanticsNodes().size,
            )
            assertState("viewfinder", description)
            val terminalAnnouncements = compose.onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes().size
            assertEquals(if (state == AfIndication.FOCUSED || state == AfIndication.FAILED) 1 else 0,
                terminalAnnouncements)
        }
    }

    @Composable
    private fun HorizonNode(
        tag: String,
        context: Context,
        enabled: Boolean,
        roll: Float,
        afIndication: AfIndication = AfIndication.IDLE,
        afActive: Boolean = false,
    ) {
        CompositionLocalProvider(LocalContext provides context) {
            val state = localizedViewfinderStateDescription(
                afIndication = afIndication,
                afActive = afActive,
                levelEnabled = enabled,
                levelRollDegrees = roll,
                deviceOrientation = 0,
            )
            Box(
                Modifier
                    .size(48.dp)
                    .testTag(tag)
                    .viewfinderFocusSemantics(
                        contentDescription = "Camera viewfinder",
                        stateDescription = state,
                        availability = ViewfinderFocusActionAvailability(false, false),
                        focusAtCenterLabel = "Focus at center",
                        resetFocusPointLabel = "Reset focus point",
                        onFocusAtCenter = {},
                        onResetFocusPoint = {},
                    ),
            )
        }
    }

    private fun assertState(tag: String, expected: String) {
        compose.onNodeWithTag(tag).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected),
        )
    }
}
