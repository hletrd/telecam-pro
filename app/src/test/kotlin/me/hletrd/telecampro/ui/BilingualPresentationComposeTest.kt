package me.hletrd.telecampro.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.util.Locale
import me.hletrd.telecampro.camera.AfIndication
import me.hletrd.telecampro.camera.BitrateLevel
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.MemoryPresetPresentation
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.ui.controls.ProSheet
import me.hletrd.telecampro.ui.controls.ProSheetTab
import me.hletrd.telecampro.ui.controls.PhotoFormatToggles
import me.hletrd.telecampro.ui.controls.SpeedAngleToggle
import me.hletrd.telecampro.ui.overlays.FocusResultLiveRegion
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class BilingualPresentationComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun localizedContext(language: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    private val actions = Proxy.newProxyInstance(
        CameraActions::class.java.classLoader,
        arrayOf(CameraActions::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as CameraActions

    @Test
    fun `Korean MR rows derive generated prose at composition while custom names survive`() {
        val generated = MemoryPresetPresentation(
            mode = CaptureMode.PHOTO,
            focalMm = 300f,
            exposureMode = ExposureMode.MANUAL,
            photoFormats = PhotoFormats(heif = true, jpeg = false, dngRaw = true),
            videoWidth = 3840,
            videoHeight = 2160,
            videoFrameRate = VideoFrameRate.FPS_30,
            transfer = ColorTransfer.HLG,
            bitrateLevel = BitrateLevel.HIGH,
        )
        val custom = generated.copy(customName = "Birds")
        val state = CameraUiState(
            savedMemorySlots = setOf(MemorySlot.MR1, MemorySlot.MR2),
            memorySlotPresentations = mapOf(MemorySlot.MR1 to generated, MemorySlot.MR2 to custom),
        )

        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("ko")) {
                TeleCamProTheme {
                    ProSheet(state, actions, onDismiss = {}, initialTab = ProSheetTab.SHOOTING)
                }
            }
        }

        compose.onNodeWithText("사진 300 mm").fetchSemanticsNode()
        compose.onNodeWithText("Birds").fetchSemanticsNode()
        assertEquals(2, compose.onAllNodesWithText("업데이트", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("저장", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `tap AF announces only localized terminal outcomes through a polite live region`() {
        val indication = mutableStateOf(AfIndication.SCANNING)
        val active = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("ko")) {
                FocusResultLiveRegion(indication.value, active.value)
            }
        }

        assertTrue(compose.onAllNodesWithContentDescription("자동 초점 맞추는 중").fetchSemanticsNodes().isEmpty())
        indication.value = AfIndication.FOCUSED
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithContentDescription("초점 고정됨").fetchSemanticsNodes().isEmpty())

        active.value = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription("초점 고정됨")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        indication.value = AfIndication.SCANNING
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithContentDescription("초점 고정됨").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("자동 초점 맞추는 중").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `Korean output trade and shutter selector render visual and spoken branches`() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("ko")) {
                TeleCamProTheme {
                    androidx.compose.foundation.layout.Column {
                        PhotoFormatToggles(
                            formats = PhotoFormats(),
                            onSetPhotoFormats = {},
                            processedAvailable = false,
                            rawAvailable = false,
                            videoMode = true,
                        )
                        SpeedAngleToggle(
                            mode = ShutterMode.SPEED,
                            enabled = true,
                            onSelect = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("10비트 동영상 · 사진 꺼짐").fetchSemanticsNode()
        compose.onNodeWithText("속도", useUnmergedTree = true).fetchSemanticsNode()
        compose.onNodeWithText("각도", useUnmergedTree = true).fetchSemanticsNode()
        compose.onNodeWithContentDescription("셔터 속도")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "선택됨"))
        compose.onNodeWithContentDescription("셔터 각도")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "선택되지 않음"))
    }
}
