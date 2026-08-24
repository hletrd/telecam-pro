package me.hletrd.telecampro.ui.controls

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.Color
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.ui.theme.CameraColors
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class SelectorRoleSemanticsComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val role = SemanticsProperties.Role

    private enum class Option { FIRST, SECOND }

    @Test
    fun `exclusive segmented and transfer options expose radio roles`() {
        val gamma = context.getString(R.string.label_gamma)
        compose.setContent {
            TeleCamProTheme {
                Column {
                    SegmentedSelector(
                        label = "Mode",
                        options = Option.entries,
                        selected = Option.FIRST,
                        labelFor = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                        onSelect = {},
                    )
                    TransferSelector(
                        transfer = ColorTransfer.SDR,
                        onTransfer = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNode(hasContentDescription("Mode First"))
            .assert(SemanticsMatcher.expectValue(role, Role.RadioButton))
        compose.onNode(hasContentDescription("$gamma ${transferLabel(ColorTransfer.SDR)}"))
            .assert(SemanticsMatcher.expectValue(role, Role.RadioButton))
        compose.onAllNodes(SemanticsMatcher.expectValue(role, Role.RadioButton))
            .assertCountEquals(Option.entries.size + ColorTransfer.entries.size)
    }

    @Test
    fun `multi-select outputs stay checkboxes while immediate commands are click-only buttons`() {
        val output = context.getString(R.string.label_output)
        val save = context.getString(R.string.action_save)
        val update = context.getString(R.string.action_update)
        val customWb = context.getString(R.string.action_capture_custom_wb)
        val clicks = AtomicInteger()
        compose.setContent {
            TeleCamProTheme {
                Column {
                    PhotoFormatToggles(
                        formats = PhotoFormats(heif = true, jpeg = true, dngRaw = true),
                        onSetPhotoFormats = {},
                        processedAvailable = true,
                        rawAvailable = true,
                    )
                    MemoryPresetAction(saved = false, enabled = true, onClick = clicks::incrementAndGet)
                    MemoryPresetAction(saved = true, enabled = false, onClick = clicks::incrementAndGet)
                    ImmediateActionChip(
                        label = customWb,
                        active = true,
                        enabled = false,
                        onClick = clicks::incrementAndGet,
                    )
                }
            }
        }
        compose.waitForIdle()

        listOf("HEIF", "JPEG", "DNG").forEach { format ->
            compose.onNode(hasContentDescription(segmentedOptionName(output, format)))
                .assert(SemanticsMatcher.expectValue(role, Role.Checkbox))
        }
        listOf(save, update, customWb).forEach { action ->
            compose.onNode(hasText(action).and(SemanticsMatcher.expectValue(role, Role.Button)))
                .assert(SemanticsMatcher.expectValue(role, Role.Button))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
                .assertHasClickAction()
        }
        compose.onNode(hasText(save)).assertIsEnabled().performClick()
        compose.onNode(hasText(update)).assertIsNotEnabled()
        compose.onNode(hasText(customWb)).assertIsNotEnabled()
        assert(clicks.get() == 1)
    }

    @Test
    fun `immediate command paint covers every reachable active and enabled state`() {
        val inactiveEnabled = immediateActionChipColors(active = false, enabled = true)
        val inactiveDisabled = immediateActionChipColors(active = false, enabled = false)
        val activeEnabled = immediateActionChipColors(active = true, enabled = true)
        val activeDisabled = immediateActionChipColors(active = true, enabled = false)

        assertEquals(Color.Transparent, inactiveEnabled.container)
        assertEquals(CameraColors.TextPrimary, inactiveEnabled.content)
        assertEquals(CameraColors.AffordanceEdge, inactiveEnabled.border)

        assertEquals(Color.Transparent, inactiveDisabled.container)
        assertEquals(CameraColors.TextPrimary.copy(alpha = DISABLED_ROW_ALPHA), inactiveDisabled.content)
        assertEquals(CameraColors.TextPrimary.copy(alpha = 0.12f), inactiveDisabled.border)

        assertEquals(CameraColors.TextPrimary, activeEnabled.container)
        assertEquals(Color.Black, activeEnabled.content)
        assertEquals(null, activeEnabled.border)

        assertEquals(CameraColors.TextPrimary.copy(alpha = DISABLED_ROW_ALPHA), activeDisabled.container)
        assertEquals(Color.Black.copy(alpha = DISABLED_ROW_ALPHA), activeDisabled.content)
        assertEquals(null, activeDisabled.border)
        assertNotEquals(activeEnabled, activeDisabled)
    }
}
