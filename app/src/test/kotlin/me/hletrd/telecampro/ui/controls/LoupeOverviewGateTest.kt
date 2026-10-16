package me.hletrd.telecampro.ui.controls

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import java.lang.reflect.Proxy
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the Assist tab's Loupe Overview gate by RENDERING the sheet, not by re-reading the flag.
 *
 * This class owns only the settings-row dependency: the child switch is disabled unless its parent
 * punch-in Loupe is enabled. It does not re-test the runtime route/visibility matrix, which is owned
 * by `TeleFinderVisibilityTest`: active loupe + (TELE or unified zoom >= 3x), with Photo requiring
 * 4:3 and Video ignoring still aspect. A state-flag assertion would not have caught the original row
 * defect, so this test drives the real composable through the real semantics tree.
 *
 * These are the first Compose UI tests in the project. They are host tests (Robolectric) on purpose:
 * the whole gate is Compose-side, so it needs no camera, and pinning it here keeps it inside the
 * ordinary `testDebugUnitTest` gate instead of the device tier.
 */
@RunWith(RobolectricTestRunner::class)
// Roughly the PMA110's own 1440×3168 at xxhdpi. The sheet picks between a stacked and a side layout
// from its measured constraints, so a default tiny qualifier would exercise a layout the device
// never shows.
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class LoupeOverviewGateTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * `CameraActions` is a ~90-method interface with no defaults and these tests never click, so a
     * reflection proxy is the honest double: 90 hand-written stubs would be 90 chances for a future
     * method to be stubbed WRONG, and a mocking framework is not a dependency here. The one
     * non-Unit member (`onReviewOpenChange: Boolean`) must not return null into a primitive.
     */
    private val actions: CameraActions = Proxy.newProxyInstance(
        CameraActions::class.java.classLoader,
        arrayOf(CameraActions::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null }
        as CameraActions

    private fun showAssistTab(state: CameraUiState) {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            TeleCamProTheme {
                ProSheet(
                    state = state,
                    actions = actions,
                    onDismiss = {},
                    initialTab = ProSheetTab.ASSISTS,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `loupe overview cannot be switched on while the loupe is off`() {
        showAssistTab(CameraUiState(punchIn = false))
        compose.onNodeWithContentDescription("Loupe Overview").assertIsNotEnabled()
    }

    @Test
    fun `loupe overview becomes available once the loupe is on`() {
        showAssistTab(CameraUiState(punchIn = true))
        compose.onNodeWithContentDescription("Loupe Overview").assertIsEnabled()
    }

    @Test
    fun `the gate is targeted - the loupe's own row never disables itself`() {
        // Guards against gating the wrong row, or gating the section: Loupe is the PARENT and must
        // stay reachable in exactly the state where its child is blocked, or the dependency becomes
        // unresolvable from the UI.
        showAssistTab(CameraUiState(punchIn = false))
        compose.onNodeWithContentDescription("Loupe").assertIsEnabled()
    }
}
