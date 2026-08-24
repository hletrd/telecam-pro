package me.hletrd.telecampro.ui.controls

import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
class ToggleRowResponsiveComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var checked: MutableState<Boolean>
    private var activations = 0

    private fun localized(locale: Locale): Context {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration)
    }

    private fun show(
        locale: Locale,
        initial: Boolean,
        enabled: Boolean,
        direction: LayoutDirection,
    ): String {
        val strings = localized(locale)
        val label = strings.getString(R.string.label_remember_teleconverter)
        checked = mutableStateOf(initial)
        activations = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides direction,
            ) {
                TeleCamProTheme {
                    Box(Modifier.requiredWidth(212.dp).testTag(VIEWPORT_TAG)) {
                        ToggleRow(
                            label = label,
                            checked = checked.value,
                            onCheckedChange = {
                                activations++
                                checked.value = it
                            },
                            enabled = enabled,
                            modifier = Modifier.testTag(ROW_TAG),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return label
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun switchBounds(): Rect = compose.onNode(
        SemanticsMatcher.keyIsDefined(ToggleRowVisualState),
        useUnmergedTree = true,
    ).fetchSemanticsNode().boundsInRoot

    private fun assertVisualsInside(label: String, direction: LayoutDirection) {
        val viewport = bounds(VIEWPORT_TAG)
        val row = bounds(ROW_TAG)
        val labelBounds = compose.onNodeWithText(label, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val toggle = switchBounds()
        for ((name, child) in listOf("label" to labelBounds, "switch" to toggle)) {
            assertTrue("$name escaped left: $child / $viewport", child.left >= viewport.left - 1f)
            assertTrue("$name escaped right: $child / $viewport", child.right <= viewport.right + 1f)
            assertTrue("$name escaped row top: $child / $row", child.top >= row.top - 1f)
            assertTrue("$name escaped row bottom: $child / $row", child.bottom <= row.bottom + 1f)
        }
        if (direction == LayoutDirection.Rtl) {
            assertTrue("RTL switch was not at logical end: $toggle / $row", toggle.left <= row.left + 1f)
        } else {
            assertTrue("LTR switch was not at logical end: $toggle / $row", toggle.right >= row.right - 1f)
        }
    }

    @Test
    fun `English checked switch stays visible and activates in compact two-x LTR`() {
        val label = show(Locale.ENGLISH, initial = true, enabled = true, direction = LayoutDirection.Ltr)
        assertVisualsInside(label, LayoutDirection.Ltr)
        compose.onNodeWithTag(ROW_TAG).assertIsEnabled().performClick()
        assertEquals(false, checked.value)
        assertEquals(1, activations)
    }

    @Test
    fun `Korean unchecked switch stays visible and activates in compact two-x RTL`() {
        val label = show(Locale.KOREAN, initial = false, enabled = true, direction = LayoutDirection.Rtl)
        assertVisualsInside(label, LayoutDirection.Rtl)
        compose.onNodeWithTag(ROW_TAG).assertIsEnabled().performClick()
        assertEquals(true, checked.value)
        assertEquals(1, activations)
    }

    @Test
    fun `English disabled checked switch stays bounded and inert`() {
        val label = show(Locale.ENGLISH, initial = true, enabled = false, direction = LayoutDirection.Ltr)
        assertVisualsInside(label, LayoutDirection.Ltr)
        compose.onNodeWithTag(ROW_TAG).assertIsNotEnabled().performTouchInput { click() }
        assertEquals(true, checked.value)
        assertEquals(0, activations)
    }

    @Test
    fun `Korean disabled unchecked switch stays bounded and inert in RTL`() {
        val label = show(Locale.KOREAN, initial = false, enabled = false, direction = LayoutDirection.Rtl)
        assertVisualsInside(label, LayoutDirection.Rtl)
        compose.onNodeWithTag(ROW_TAG).assertIsNotEnabled().performTouchInput { click() }
        assertEquals(false, checked.value)
        assertEquals(0, activations)
    }

    private companion object {
        const val VIEWPORT_TAG = "toggle-row-viewport"
        const val ROW_TAG = "toggle-row"
    }
}
