package me.hletrd.telecampro.ui.controls

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.lang.reflect.Proxy
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every settings tab opens at its TOP (UI review 2026-11-14). Each chip selector used to ask a
 * `BringIntoViewRequester` for its selected chip, the page scrolled for every request, and a tab
 * came to rest on its LAST selector: Exposure opened on the WB row with Mode and Shutter above the
 * edge, Setup at its bottom. The selectors now scroll only their own row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h905dp-xxhdpi")
class SheetOpenScrollComposeTest {
    @get:Rule val compose = createComposeRule()

    private val actions: CameraActions = Proxy.newProxyInstance(
        CameraActions::class.java.classLoader,
        arrayOf(CameraActions::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as CameraActions

    private var shown by mutableStateOf(ProSheetTab.SHOOTING)

    private fun pageScroll(tab: ProSheetTab): Float {
        shown = tab
        compose.waitForIdle()
        return compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes()
            .maxOf { it.config[SemanticsProperties.VerticalScrollAxisRange].value() }
    }

    @Test fun `every tab opens at the top of its page`() {
        compose.setContent {
            TeleCamProTheme {
                // A fresh sheet per tab: key() disposes the previous one, as closing and reopening does.
                key(shown) {
                    ProSheet(state = CameraUiState(), actions = actions, onDismiss = {}, initialTab = shown)
                }
            }
        }
        val moved = ProSheetTab.entries.associateWith(::pageScroll).filterValues { it != 0f }
        assertEquals("tabs that opened scrolled down", emptyMap<ProSheetTab, Float>(), moved)
    }

    @Test fun `a selected chip past the row edge scrolls its row, not the page`() {
        compose.setContent {
            TeleCamProTheme {
                Column(Modifier.width(220.dp).height(400.dp).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(600.dp))
                    SegmentedSelector(
                        label = "Probe",
                        options = (1..8).map { "Option $it" },
                        selected = "Option 8",
                        labelFor = { it },
                        onSelect = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val page = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes().single().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("page scroll", 0f, page)
        val row = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNodes().single()
        assertTrue("row did not scroll", row.config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        val chip = compose.onNodeWithContentDescription("Probe Option 8", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(
            "selected chip ${chip.boundsInRoot} outside its row ${row.boundsInRoot}",
            chip.boundsInRoot.left >= row.boundsInRoot.left - 0.5f && chip.boundsInRoot.right <= row.boundsInRoot.right + 0.5f,
        )
    }
}
