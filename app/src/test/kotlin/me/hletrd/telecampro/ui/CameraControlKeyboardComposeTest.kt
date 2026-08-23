package me.hletrd.telecampro.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isNotFocusable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.ui.controls.DialChip
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class CameraControlKeyboardComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `shared camera controls skip disabled focus targets and retain disabled actions`() {
        val activations = IntArray(8)
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            TeleCamProTheme {
                Column {
                    ChromeIconButton(
                        onClick = { activations[0]++ },
                        contentDescription = "enabled chrome",
                        modifier = Modifier.testTag("enabled-chrome"),
                    ) {}
                    ChromeIconButton(
                        onClick = { activations[0] += 100 },
                        contentDescription = "disabled chrome",
                        modifier = Modifier.testTag("disabled-chrome"),
                        enabled = false,
                    ) {}
                    TeleChip(
                        active = false,
                        enabled = true,
                        onClick = { activations[1]++ },
                        modifier = Modifier.testTag("enabled-tele"),
                    )
                    TeleChip(
                        active = false,
                        enabled = false,
                        onClick = { activations[1] += 100 },
                        modifier = Modifier.testTag("disabled-tele"),
                    )
                    FnOverlayTile(
                        slot = FnSlot.ISO,
                        value = "100",
                        enabled = true,
                        onClick = { activations[2]++ },
                        modifier = Modifier.testTag("enabled-fn"),
                    )
                    FnOverlayTile(
                        slot = FnSlot.WB,
                        value = "Auto",
                        enabled = false,
                        onClick = { activations[2] += 100 },
                        modifier = Modifier.testTag("disabled-fn"),
                    )
                    RailChip(
                        label = "1×",
                        contentDescription = "enabled rail",
                        presentation = railState(enabled = true),
                        onClick = { activations[3]++ },
                        glyphRotation = 0f,
                        modifier = Modifier.testTag("enabled-rail"),
                    )
                    RailChip(
                        label = "3×",
                        contentDescription = "disabled rail",
                        presentation = railState(enabled = false),
                        onClick = { activations[3] += 100 },
                        glyphRotation = 0f,
                        modifier = Modifier.testTag("disabled-rail"),
                    )
                    ModeLabel(
                        text = "Photo",
                        active = true,
                        enabled = true,
                        onClick = { activations[4]++ },
                        modifier = Modifier.testTag("enabled-mode"),
                    )
                    ModeLabel(
                        text = "Video",
                        active = false,
                        enabled = false,
                        onClick = { activations[4] += 100 },
                        modifier = Modifier.testTag("disabled-mode"),
                    )
                    ShutterButton(
                        mode = CaptureMode.PHOTO,
                        isRecording = false,
                        timerCountdownSec = 0,
                        enabled = true,
                        onClick = { activations[5]++ },
                        modifier = Modifier.testTag("enabled-shutter"),
                    )
                    ShutterButton(
                        mode = CaptureMode.PHOTO,
                        isRecording = false,
                        timerCountdownSec = 0,
                        enabled = false,
                        onClick = { activations[5] += 100 },
                        modifier = Modifier.testTag("disabled-shutter"),
                    )
                    SnapshotButton(
                        enabled = true,
                        onClick = { activations[6]++ },
                        modifier = Modifier.testTag("enabled-snapshot"),
                    )
                    SnapshotButton(
                        enabled = false,
                        onClick = { activations[6] += 100 },
                        modifier = Modifier.testTag("disabled-snapshot"),
                    )
                    DialChip(
                        label = "ISO",
                        value = "100",
                        active = false,
                        enabled = true,
                        onClick = { activations[7]++ },
                        onLongClick = {},
                        modifier = Modifier.testTag("enabled-dial"),
                    )
                    DialChip(
                        label = "WB",
                        value = "Auto",
                        active = false,
                        enabled = false,
                        onClick = { activations[7] += 100 },
                        onLongClick = {},
                        modifier = Modifier.testTag("disabled-dial"),
                    )
                }
            }
        }
        compose.waitForIdle()

        val enabledTags = listOf(
            "enabled-chrome",
            "enabled-tele",
            "enabled-fn",
            "enabled-rail",
            "enabled-mode",
            "enabled-shutter",
            "enabled-snapshot",
            "enabled-dial",
        )
        val disabledTags = enabledTags.map { it.replace("enabled-", "disabled-") }

        enabledTags.forEachIndexed { index, tag ->
            compose.onNodeWithTag(tag)
                .assert(isFocusable())
                .requestFocus()
                .assertIsFocused()
                .performKeyInput { pressKey(Key.Enter) }
            compose.waitForIdle()
            assertEquals("$tag did not activate exactly once", 1, activations[index])
        }

        disabledTags.forEach { tag ->
            compose.onNodeWithTag(tag)
                .assertIsNotEnabled()
                .assert(isNotFocusable())
                .assertHasClickAction()
                .performClick()
        }
        assertEquals(List(8) { 1 }, activations.toList())
    }

    @Test
    fun `Tab reaches one rail owner and D-pad center activates only enabled chip`() {
        var firstActivations = 0
        var railActivations = 0
        compose.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            TeleCamProTheme {
                Column {
                    ChromeIconButton(
                        onClick = { firstActivations++ },
                        contentDescription = "first",
                        modifier = Modifier.testTag("first"),
                    ) {}
                    RailChip(
                        label = "off",
                        contentDescription = "disabled rail",
                        presentation = railState(enabled = false),
                        onClick = { railActivations += 100 },
                        glyphRotation = 0f,
                        modifier = Modifier.testTag("disabled-rail"),
                    )
                    RailChip(
                        label = "on",
                        contentDescription = "enabled rail",
                        presentation = railState(enabled = true),
                        onClick = { railActivations++ },
                        glyphRotation = 0f,
                        modifier = Modifier.testTag("enabled-rail"),
                    )
                }
            }
        }

        compose.onNodeWithTag("first")
            .requestFocus()
            .performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("enabled-rail")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals(0, firstActivations)
        assertEquals(1, railActivations)
        compose.onNodeWithTag("disabled-rail")
            .assertIsNotEnabled()
            .assert(isNotFocusable())
            .performClick()
        assertEquals(1, railActivations)
    }

    private fun railState(enabled: Boolean) = FocalRailState(
        selected = false,
        enabled = enabled,
        state = if (enabled) {
            CameraControlSelectionState.NOT_SELECTED
        } else {
            CameraControlSelectionState.CAMERA_RECONFIGURING
        },
        accessibilityRole = Role.RadioButton,
    )
}
