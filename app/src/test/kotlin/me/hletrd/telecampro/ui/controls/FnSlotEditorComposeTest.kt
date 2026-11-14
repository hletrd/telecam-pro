package me.hletrd.telecampro.ui.controls

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.lang.reflect.Proxy
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI review 2026-11-14: the Setup tab listed every unused slot as its own "Add" row under each of the
 * three editors (~74 rows), and under a full bar those rows were greyed with nothing saying why.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h3200dp-xxhdpi")
class FnSlotEditorComposeTest {
    @get:Rule val compose = createComposeRule()

    private val actions: CameraActions = Proxy.newProxyInstance(
        CameraActions::class.java.classLoader,
        arrayOf(CameraActions::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as CameraActions

    private fun addRows() = compose.onAllNodes(hasText("Add"), useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun `a full bar says so and a bar with room folds its candidates`() {
        val state = CameraUiState(
            photoFnSlots = FnSlot.PHOTO_DEFAULT,
            videoFnSlots = FnSlot.VIDEO_DEFAULT,
            myMenuSlots = listOf(FnSlot.PEAKING),
        )
        compose.setContent {
            TeleCamProTheme {
                ProSheet(state = state, actions = actions, onDismiss = {}, initialTab = ProSheetTab.ADVANCED)
            }
        }
        compose.waitForIdle()
        val full = compose.onAllNodes(hasText("Full (8/8). Remove one to add another."), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("full bars", 2, full.size)
        assertEquals("candidates folded", 0, addRows())
        compose.onNodeWithText("Add a Setting", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("My Menu candidates", FnSlot.entries.size - 1, addRows())
    }
}
