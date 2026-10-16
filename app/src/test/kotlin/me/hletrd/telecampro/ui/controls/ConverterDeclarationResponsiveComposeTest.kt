package me.hletrd.telecampro.ui.controls

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.PhoneModel
import me.hletrd.telecampro.camera.TeleconverterProfile
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
class ConverterDeclarationResponsiveComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun localized(locale: Locale): Context {
        val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(configuration)
    }

    private fun show(
        context: Context,
        direction: LayoutDirection,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides direction,
            ) {
                TeleCamProTheme {
                    Box(Modifier.requiredWidth(212.dp).testTag(VIEWPORT_TAG)) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertInside(outer: Rect, inner: Rect, name: String) {
        assertTrue("$name escaped start: $inner / $outer", inner.left >= outer.left - 1f)
        assertTrue("$name escaped end: $inner / $outer", inner.right <= outer.right + 1f)
        assertTrue("$name escaped top: $inner / $outer", inner.top >= outer.top - 1f)
        assertTrue("$name escaped bottom: $inner / $outer", inner.bottom <= outer.bottom + 1f)
    }

    private fun assertCompleteText(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("text layout action refused", action(layouts))
        }
        assertEquals(1, layouts.size)
        assertTrue("text had visual overflow", !layouts.single().hasVisualOverflow)
    }

    private fun assertDeclaration(
        context: Context,
        label: String,
        selected: String,
        options: List<String>,
    ) {
        val trigger = compose.onNodeWithContentDescription(label)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, selected))
        val triggerBounds = trigger.fetchSemanticsNode().boundsInRoot
        val viewportBounds = compose.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
        val labelNode = compose.onNodeWithText(label, useUnmergedTree = true)
        val selectedNode = compose.onNodeWithText(selected, useUnmergedTree = true)
        val labelBounds = labelNode.fetchSemanticsNode().boundsInRoot
        val selectedBounds = selectedNode.fetchSemanticsNode().boundsInRoot

        assertInside(viewportBounds, triggerBounds, "$label trigger")
        assertInside(triggerBounds, labelBounds, "$label label")
        assertInside(triggerBounds, selectedBounds, "$label selected value")
        assertTrue("$label did not use the compact stacked layout", selectedBounds.top >= labelBounds.bottom - 1f)
        assertCompleteText(selectedNode)

        trigger.performClick()
        compose.waitForIdle()

        val popupBounds = compose.onNode(isPopup()).fetchSemanticsNode().boundsInRoot
        assertTrue("$label popup exceeded the 212 dp trigger", popupBounds.width <= viewportBounds.width + 1f)
        val selectableGroup = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
        val inPopup = hasAnyAncestor(isPopup())
        compose.onAllNodes(selectableGroup.and(inPopup)).assertCountEquals(1)
        compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true).and(inPopup),
        ).assertCountEquals(1)

        options.forEach { value ->
            val item = compose.onNode(hasText(value).and(inPopup))
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, value == selected))
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        context.getString(
                            if (value == selected) R.string.a11y_selected else R.string.a11y_not_selected,
                        ),
                    ),
                )
                .assert(hasAnyAncestor(selectableGroup))
            assertInside(popupBounds, item.fetchSemanticsNode().boundsInRoot, "$label option $value")

            val text = compose.onNode(hasText(value).and(inPopup), useUnmergedTree = true)
            assertInside(item.fetchSemanticsNode().boundsInRoot, text.fetchSemanticsNode().boundsInRoot, "$value text")
            assertCompleteText(text)
        }
        compose.onAllNodes(hasClickAction().and(inPopup)).assertCountEquals(options.size)
    }

    @Test
    fun `real English Phone declaration fits 212 dp at two-x text in LTR`() {
        val context = localized(Locale.ENGLISH)
        show(context, LayoutDirection.Ltr) {
            PhoneModelDropdown(selected = PhoneModel.FIND_X9_ULTRA, onSelect = {})
        }

        assertDeclaration(
            context = context,
            label = "Phone",
            selected = "OPPO Find X9 Ultra",
            options = listOf("OPPO Find X9 Ultra", "OPPO Find X9 Pro", "vivo X200 Ultra", "vivo X300 Ultra", "Other"),
        )
    }

    @Test
    fun `real Korean Phone declaration fits 212 dp at two-x text in RTL`() {
        val context = localized(Locale.KOREAN)
        show(context, LayoutDirection.Rtl) {
            PhoneModelDropdown(selected = PhoneModel.OTHER, onSelect = {})
        }

        assertDeclaration(
            context = context,
            label = "휴대폰",
            selected = "기타",
            options = listOf("OPPO Find X9 Ultra", "OPPO Find X9 Pro", "vivo X200 Ultra", "vivo X300 Ultra", "기타"),
        )
    }

    @Test
    fun `real English generic and custom Converter declarations fit in LTR`() {
        val context = localized(Locale.ENGLISH)
        show(context, LayoutDirection.Ltr) {
            TeleconverterProfileDropdown(
                phone = PhoneModel.OTHER,
                selected = TeleconverterProfile.GENERIC_1_5,
                onSelect = {},
            )
        }

        assertDeclaration(
            context = context,
            label = "Converter",
            selected = "Generic 1.5×",
            options = listOf("Generic 1.5×", "Generic 2×", "Generic 3×", "Custom"),
        )
    }

    @Test
    fun `real Korean generic and custom Converter declarations fit in RTL`() {
        val context = localized(Locale.KOREAN)
        show(context, LayoutDirection.Rtl) {
            TeleconverterProfileDropdown(
                phone = PhoneModel.OTHER,
                selected = TeleconverterProfile.CUSTOM,
                onSelect = {},
            )
        }

        assertDeclaration(
            context = context,
            label = "컨버터",
            selected = "사용자 지정",
            options = listOf("일반 1.5×", "일반 2×", "일반 3×", "사용자 지정"),
        )
    }

    private companion object {
        const val VIEWPORT_TAG = "converter-declaration-viewport"
    }
}
