package me.hletrd.telecampro.ui.controls

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.util.Range
import android.util.Rational
import java.lang.reflect.Proxy
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.ViewModelTestAccess
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI review 2026-11-14: the Exposure tab had an ISO slider but no shutter speed or EV, and in P that
 * slider showed the stored ISO while the camera ran another. The tab now lists the three values, as
 * the camera is using them, and each opens its viewfinder ruler.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h905dp-xxhdpi")
class ExposureTabValuesComposeTest {
    @get:Rule val compose = createComposeRule()

    private val actions: CameraActions = Proxy.newProxyInstance(
        CameraActions::class.java.classLoader,
        arrayOf(CameraActions::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as CameraActions

    @Test fun `shutter, ISO and EV rows read the live values and open their rulers`() {
        val opened = mutableListOf<DialType>()
        var dismissed = 0
        val state = CameraUiState(
            cameraReady = true,
            cameraReadyEstablished = true,
            caps = ViewModelTestAccess.caps(isoRange = Range(100, 6400)).copy(
                exposureTimeRange = Range(125_000L, 1_000_000_000L),
                maxFrameDurationNs = 1_000_000_000L,
                evRange = Range(-12, 12),
                evStep = Rational(1, 3),
                supportsManualSensor = true,
                aeModes = intArrayOf(0, 1, 2, 3),
            ),
            controls = ManualControls(exposureMode = ExposureMode.SHUTTER, exposureTimeNs = 4_000_000L, exposureCompensation = 3),
            liveIso = 640,
        )
        compose.setContent {
            TeleCamProTheme {
                ProSheet(
                    state = state,
                    actions = actions,
                    onDismiss = { dismissed++ },
                    initialTab = ProSheetTab.EXPOSURE,
                    onSelectManualDial = { opened += it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Shutter Type").assertExists()
        compose.onNodeWithText("1/250s", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("+1.0", useUnmergedTree = true).assertExists()
        listOf("1/250s", "+1.0").forEach { value ->
            compose.onNode(hasClickAction() and hasText(value, substring = true), useUnmergedTree = false).performClick()
            compose.waitForIdle()
        }
        assertEquals(listOf(DialType.SHUTTER, DialType.EV), opened)
        assertEquals(2, dismissed)
    }
}
