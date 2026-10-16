package me.hletrd.telecampro.ui.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h340dp-xxhdpi")
class FnSlotOrderLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `compact two-x RTL action targets remain distinct and in bounds`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                TeleCamProTheme {
                    Box(Modifier.width(212.dp).testTag("row-bounds")) {
                        FnSlotOrderRow(
                            slot = FnSlot.TELECONVERTER,
                            index = 3,
                            count = 8,
                            onMoveUp = {},
                            onMoveDown = {},
                            onRemove = {},
                        )
                    }
                }
            }
        }

        val actions = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .sortedBy { it.left }
        assertEquals(3, actions.size)
        val root = compose.onNodeWithTag("row-bounds").fetchSemanticsNode().boundsInRoot
        actions.forEach { bounds ->
            assertTrue("action escaped root: $bounds / $root", bounds.left >= root.left && bounds.right <= root.right)
        }
        actions.zipWithNext().forEach { (left, right) ->
            assertTrue("actions overlap: $left / $right", left.right <= right.left + 0.5f)
        }
    }
}
