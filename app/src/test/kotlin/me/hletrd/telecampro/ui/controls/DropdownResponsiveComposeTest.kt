package me.hletrd.telecampro.ui.controls

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
class DropdownResponsiveComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val base: Context = ApplicationProvider.getApplicationContext()
    private val options = listOf("OPPO Find X9 Ultra", "OPPO Find X9 Pro")
    private var selected by mutableStateOf(options.first())

    private fun localized(locale: Locale): Context {
        val configuration = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(configuration)
    }

    private fun show(label: String, direction: LayoutDirection) {
        selected = options.first()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides direction,
            ) {
                TeleCamProTheme {
                    Box(Modifier.requiredWidth(212.dp).testTag(VIEWPORT_TAG)) {
                        DropdownRow(
                            label = label,
                            options = options,
                            selected = selected,
                            labelFor = { it },
                            onSelect = { selected = it },
                        )
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
        val layout = layouts.single()
        val ellipsized = (0 until layout.lineCount).filter(layout::isLineEllipsized)
        assertTrue(
            "text had visual overflow: size=${layout.size} lines=${layout.lineCount} " +
                "width=${layout.didOverflowWidth} height=${layout.didOverflowHeight} " +
                "ellipsized=$ellipsized",
            !layout.hasVisualOverflow,
        )
    }

    private fun assertStackedTrigger(label: String) {
        val trigger = compose.onNodeWithContentDescription(label)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, selected))
        val triggerBounds = trigger.fetchSemanticsNode().boundsInRoot
        val labelNode = compose.onNodeWithText(label, useUnmergedTree = true)
        val valueNode = compose.onNodeWithText(selected, useUnmergedTree = true)
        val labelBounds = labelNode.fetchSemanticsNode().boundsInRoot
        val valueBounds = valueNode.fetchSemanticsNode().boundsInRoot

        assertInside(triggerBounds, labelBounds, "label")
        assertInside(triggerBounds, valueBounds, "selected value")
        assertTrue("selected value did not stack below label", valueBounds.top >= labelBounds.bottom - 1f)
        assertCompleteText(valueNode)
    }

    @Test
    fun `compact two-x dropdown exposes its complete selected model in LTR`() {
        show(label = "Phone", direction = LayoutDirection.Ltr)

        assertStackedTrigger("Phone")
        assertInside(
            compose.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot,
            compose.onNodeWithContentDescription("Phone").fetchSemanticsNode().boundsInRoot,
            "trigger",
        )
    }

    @Test
    fun `compact Korean RTL dropdown keeps complete grouped popup choices`() {
        val label = localized(Locale.KOREAN).getString(R.string.label_phone)
        show(label = label, direction = LayoutDirection.Rtl)
        assertStackedTrigger(label)

        compose.onNodeWithContentDescription(label).performClick()
        compose.waitForIdle()
        val popup = compose.onNode(isPopup()).fetchSemanticsNode().boundsInRoot
        val selectableGroup = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
        compose.onAllNodes(selectableGroup.and(hasAnyAncestor(isPopup()))).assertCountEquals(1)
        options.forEach { value ->
            val option = compose.onNode(
                hasText(value).and(hasAnyAncestor(isPopup())),
                useUnmergedTree = true,
            )
            assertInside(popup, option.fetchSemanticsNode().boundsInRoot, "popup option $value")
            assertCompleteText(option)
            option.assert(hasAnyAncestor(selectableGroup))
        }
        compose.onAllNodes(hasClickAction().and(hasAnyAncestor(isPopup())))
            .assertCountEquals(options.size)
    }

    private companion object {
        const val VIEWPORT_TAG = "dropdown-viewport"
    }
}
