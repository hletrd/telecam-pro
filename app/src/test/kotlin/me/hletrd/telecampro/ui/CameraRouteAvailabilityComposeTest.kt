package me.hletrd.telecampro.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import me.hletrd.telecampro.camera.CameraRouteInventory
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class CameraRouteAvailabilityComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `camera switch exists only when both front and non-front routes exist`() {
        val routes = mutableStateOf(
            CameraRouteInventory(back = true, front = false, external = false),
        )
        compose.setContent {
            TeleCamProTheme {
                CameraSwitchButton(
                    available = routes.value.switchAvailable,
                    onClick = {},
                )
            }
        }

        compose.onAllNodesWithContentDescription("Switch camera").assertCountEquals(0)
        routes.value = CameraRouteInventory(back = true, front = true, external = false)
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Switch camera").assertCountEquals(1)
    }
}
