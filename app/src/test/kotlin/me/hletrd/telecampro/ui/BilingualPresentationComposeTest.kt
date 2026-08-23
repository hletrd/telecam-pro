package me.hletrd.telecampro.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.util.Locale
import me.hletrd.telecampro.camera.AfIndication
import me.hletrd.telecampro.camera.BitrateLevel
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.MemoryPresetPresentation
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.ui.controls.ProSheet
import me.hletrd.telecampro.ui.controls.ProSheetTab
import me.hletrd.telecampro.ui.controls.focusDialStateDescription
import me.hletrd.telecampro.ui.controls.PhotoFormatToggles
import me.hletrd.telecampro.ui.controls.SpeedAngleToggle
import me.hletrd.telecampro.ui.overlays.FocusResultLiveRegion
import me.hletrd.telecampro.ui.review.GalleryThumb
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

    private fun assertDescription(tag: String, expected: String) {
        compose.onNodeWithTag(tag).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(expected)),
        )
    }

    @Test
    fun `empty gallery is an enabled Korean restore action`() {
        var requests = 0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("ko")) {
                TeleCamProTheme {
                    GalleryThumb(uri = null, onClick = { requests++ })
                }
            }
        }

        compose.onNodeWithContentDescription("이전 촬영 찾기")
            .assertIsEnabled()
            .performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `Korean Lens caption and Macro focus state stay localized`() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("ko")) {
                TeleCamProTheme {
                    androidx.compose.foundation.layout.Column {
                        ProSheet(
                            CameraUiState(),
                            actions,
                            onDismiss = {},
                            initialTab = ProSheetTab.LENS,
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("3× 렌즈를 사용합니다").fetchSemanticsNode()
        assertEquals(
            "매크로, ∞",
            focusDialStateDescription(FocusMode.MACRO, "∞", "매크로"),
        )
    }

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

    @Test
    fun `English production status formatter owns complete capacity and countdown semantics`() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("en")) {
                Column {
                    val countdown = localizedTimerCountdownDescription(1)
                    Box(
                        Modifier.testTag("countdown").clearAndSetSemantics {
                            contentDescription = "Self-timer"
                            stateDescription = countdown
                        },
                    )
                    listOf(
                        "shots" to localizedStatusInfoDescription(72, "1234", video = false),
                        "shots-saturated" to localizedStatusInfoDescription(5, "9999+", video = false),
                        "minutes" to localizedStatusInfoDescription(72, "45m", video = true),
                        "minutes-saturated" to localizedStatusInfoDescription(72, "45m+", video = true),
                        "duration" to localizedStatusInfoDescription(72, "9h30m", video = true),
                        "hours-saturated" to localizedStatusInfoDescription(72, "9h+", video = true),
                        "duration-saturated" to localizedStatusInfoDescription(72, "9h30m+", video = true),
                        "media-only" to localizedStatusInfoDescription(-1, "42", video = false),
                        "malformed" to localizedStatusInfoDescription(42, "??", video = true),
                        "battery-only" to localizedStatusInfoDescription(42, null, video = false),
                    ).forEach { (tag, description) ->
                        Box(Modifier.testTag(tag).clearAndSetSemantics { contentDescription = description })
                    }
                }
            }
        }

        compose.onNodeWithTag("countdown").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1 second remaining"),
        )
        assertDescription("shots", "Battery 72 percent, 1234 shots remaining")
        assertDescription("shots-saturated", "Battery 5 percent, Over 9999 shots remaining")
        assertDescription("minutes", "Battery 72 percent, 45 minutes remaining")
        assertDescription("minutes-saturated", "Battery 72 percent, Over 45 minutes remaining")
        assertDescription("duration", "Battery 72 percent, 9 hours 30 minutes remaining")
        assertDescription("hours-saturated", "Battery 72 percent, Over 9 hours remaining")
        assertDescription("duration-saturated", "Battery 72 percent, Over 9 hours 30 minutes remaining")
        assertDescription("media-only", "42 shots remaining")
        assertDescription("malformed", "Battery 42 percent")
        assertDescription("battery-only", "Battery 42 percent")
    }

    @Test
    fun `Korean production status formatter owns natural saturation and countdown semantics`() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("ko")) {
                Column {
                    val countdown = localizedTimerCountdownDescription(1)
                    Box(
                        Modifier.testTag("countdown-ko").clearAndSetSemantics {
                            contentDescription = "셀프타이머"
                            stateDescription = countdown
                        },
                    )
                    listOf(
                        "shots-ko" to localizedStatusInfoDescription(72, "1234", video = false),
                        "shots-saturated-ko" to localizedStatusInfoDescription(5, "9999+", video = false),
                        "minutes-ko" to localizedStatusInfoDescription(72, "45m", video = true),
                        "minutes-saturated-ko" to localizedStatusInfoDescription(72, "45m+", video = true),
                        "duration-ko" to localizedStatusInfoDescription(72, "9h30m", video = true),
                        "hours-saturated-ko" to localizedStatusInfoDescription(72, "9h+", video = true),
                        "duration-saturated-ko" to localizedStatusInfoDescription(72, "9h30m+", video = true),
                        "media-only-ko" to localizedStatusInfoDescription(-1, "42", video = false),
                        "malformed-ko" to localizedStatusInfoDescription(42, "??", video = true),
                        "battery-only-ko" to localizedStatusInfoDescription(42, null, video = false),
                    ).forEach { (tag, description) ->
                        Box(Modifier.testTag(tag).clearAndSetSemantics { contentDescription = description })
                    }
                }
            }
        }

        compose.onNodeWithTag("countdown-ko").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1초 남음"),
        )
        assertDescription("shots-ko", "배터리 72퍼센트, 1234장 촬영 가능")
        assertDescription("shots-saturated-ko", "배터리 5퍼센트, 9999장 이상 촬영 가능")
        assertDescription("minutes-ko", "배터리 72퍼센트, 45분 남음")
        assertDescription("minutes-saturated-ko", "배터리 72퍼센트, 45분 이상 남음")
        assertDescription("duration-ko", "배터리 72퍼센트, 9시간 30분 남음")
        assertDescription("hours-saturated-ko", "배터리 72퍼센트, 9시간 이상 남음")
        assertDescription("duration-saturated-ko", "배터리 72퍼센트, 9시간 30분 이상 남음")
        assertDescription("media-only-ko", "42장 촬영 가능")
        assertDescription("malformed-ko", "배터리 42퍼센트")
        assertDescription("battery-only-ko", "배터리 42퍼센트")
    }
}
