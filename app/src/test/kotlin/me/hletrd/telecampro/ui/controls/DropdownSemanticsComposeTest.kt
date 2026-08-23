package me.hletrd.telecampro.ui.controls

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.R
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class DropdownSemanticsComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val selectedDescription = context.getString(R.string.a11y_selected)
    private val notSelectedDescription = context.getString(R.string.a11y_not_selected)

    private enum class Option { FIRST, SECOND, THIRD }

    private val labels = mapOf(
        Option.FIRST to "First",
        Option.SECOND to "Second",
        Option.THIRD to "Third",
    )

    private var selected by mutableStateOf(Option.FIRST)

    private fun showDropdown() {
        selected = Option.FIRST
        compose.setContent {
            TeleCamProTheme {
                DropdownRow(
                    label = "Phone",
                    options = Option.entries,
                    selected = selected,
                    labelFor = labels::getValue,
                    onSelect = { selected = it },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun option(label: String) = compose.onNode(
        hasText(label).and(hasAnyAncestor(isPopup())),
    )

    private fun assertOpenMenuSelection(expected: String) {
        val selectedMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
            .and(hasAnyAncestor(isPopup()))
        compose.onAllNodes(selectedMatcher).assertCountEquals(1)
        Option.entries.forEach { value ->
            val isSelected = labels.getValue(value) == expected
            option(labels.getValue(value))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, isSelected))
                .assert(hasStateDescription(if (isSelected) selectedDescription else notSelectedDescription))
                .assert(hasClickAction())
        }
        compose.onAllNodes(hasClickAction().and(hasAnyAncestor(isPopup())))
            .assertCountEquals(Option.entries.size)
    }

    @Test
    fun `open dropdown exposes exactly one radio selection before and after change`() {
        showDropdown()
        val trigger = compose.onNodeWithContentDescription("Phone")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .assert(hasStateDescription("First"))
            .assert(hasClickAction())

        trigger.performClick()
        assertOpenMenuSelection("First")
        option("Third").performClick()

        compose.onNodeWithContentDescription("Phone")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .assert(hasStateDescription("Third"))
            .assert(hasClickAction())
            .performClick()
        assertOpenMenuSelection("Third")
    }
}
