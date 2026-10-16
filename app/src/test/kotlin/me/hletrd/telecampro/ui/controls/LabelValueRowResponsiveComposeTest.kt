package me.hletrd.telecampro.ui.controls

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import me.hletrd.telecampro.R
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
class LabelValueRowResponsiveComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var activations = 0

    private fun localized(locale: Locale): Context {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration)
    }

    private fun show(
        label: String,
        value: String,
        enabled: Boolean = true,
        clickable: Boolean = true,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        activations = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides direction,
            ) {
                TeleCamProTheme {
                    Box(Modifier.requiredWidth(212.dp)) {
                        LabelValueRow(
                            label = label,
                            valueLabel = value,
                            enabled = enabled,
                            onClick = if (clickable) ({ activations++ }) else null,
                            modifier = Modifier.testTag(ROW_TAG),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun rowBounds(): Rect = compose.onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot

    private fun assertInsideRow(text: String) {
        val row = rowBounds()
        val textBounds = compose.onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("$text escaped row start: $textBounds / $row", textBounds.left >= row.left - 1f)
        assertTrue("$text escaped row end: $textBounds / $row", textBounds.right <= row.right + 1f)
    }

    @Test
    fun `Korean privacy action remains visible RTL aligned and activates at two-x font`() {
        val ko = localized(Locale.KOREAN)
        val label = ko.getString(R.string.action_privacy_policy)
        val action = ko.getString(R.string.action_view)
        show(label, action, direction = LayoutDirection.Rtl)

        assertInsideRow(label)
        assertInsideRow(action)
        val row = rowBounds()
        val actionBounds = compose.onNodeWithText(action, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("RTL trailing action was not placed at logical end: $actionBounds / $row", actionBounds.left <= row.left + 1f)

        compose.onNodeWithTag(ROW_TAG).assertHasClickAction().performClick()
        assertEquals(1, activations)
    }

    @Test
    fun `English long dynamic value wraps within compact row at two-x font`() {
        val value = "Bluetooth microphone unavailable"
        show(label = "Audio input", value = value, clickable = false)

        assertInsideRow("Audio input")
        assertInsideRow(value)
    }

    @Test
    fun `disabled Korean readout stays bounded and exposes disabled semantics`() {
        val ko = localized(Locale.KOREAN)
        val value = ko.getString(R.string.settings_locked)
        show(label = ko.getString(R.string.label_recording), value = value, enabled = false, clickable = false)

        compose.onNodeWithTag(ROW_TAG).assertIsNotEnabled()
        assertInsideRow(value)
    }

    private companion object {
        const val ROW_TAG = "label-value-row"
    }
}
