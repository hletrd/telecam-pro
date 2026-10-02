package me.hletrd.telecampro.ui.controls

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The rendered Output row follows [photoFormatChipModel] (AGG4-66 / AGG4-69). */
@RunWith(RobolectricTestRunner::class)
class PhotoFormatSessionTruthComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun chip(name: String) = compose.onNodeWithContentDescription(segmentedOptionName("Output", name))

    @Test fun `FRONT DNG-only request lights the HEIF the session writes`() {
        var edited: PhotoFormats? = null
        compose.setContent {
            TeleCamProTheme {
                PhotoFormatToggles(
                    formats = PhotoFormats(heif = false, jpeg = false, dngRaw = true),
                    onSetPhotoFormats = { edited = it },
                    sessionOutputs = PhotoSessionOutputs(processed = true),
                    cameraReady = true,
                    reopenInProgress = false,
                    rawAvailable = false,
                    heifStandIn = true,
                )
            }
        }
        chip("HEIF").assertIsSelected().assertIsNotEnabled()
        chip("JPEG").assertIsNotSelected().assertIsEnabled().performClick()
        compose.waitForIdle()
        // AGG5-54: a tap beside the lit stand-in adds JPEG to what the row shows (pick-many).
        assertEquals(PhotoFormats(heif = true, jpeg = true, dngRaw = true), edited)
        compose.onNodeWithText("RAW unavailable").fetchSemanticsNode()
    }

    @Test fun `a reopen shows the reconfiguring caption with live chips`() {
        compose.setContent {
            TeleCamProTheme {
                PhotoFormatToggles(
                    formats = PhotoFormats(heif = true, jpeg = false, dngRaw = true),
                    onSetPhotoFormats = {},
                    sessionOutputs = PhotoSessionOutputs(),
                    cameraReady = false,
                    reopenInProgress = true,
                    rawAvailable = true,
                    heifStandIn = true,
                )
            }
        }
        chip("HEIF").assertIsSelected().assertIsEnabled()
        chip("JPEG").assertIsEnabled()
        compose.onNodeWithText("Camera reconfiguring…").fetchSemanticsNode()
    }
}
