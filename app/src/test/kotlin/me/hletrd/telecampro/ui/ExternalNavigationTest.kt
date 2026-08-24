package me.hletrd.telecampro.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.util.Locale
import me.hletrd.telecampro.PermissionGate
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.ui.controls.ProSheet
import me.hletrd.telecampro.ui.controls.ProSheetTab
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class ExternalNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = Proxy.newProxyInstance(
        CameraActions::class.java.classLoader,
        arrayOf(CameraActions::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as CameraActions

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        lateinit var launchedIntent: Intent

        override fun startActivity(intent: Intent) {
            launchedIntent = intent
        }
    }

    @Test
    fun `external launcher distinguishes unresolved blocked and successful attempts`() {
        assertEquals(
            ExternalLaunchOutcome.UNRESOLVED,
            attemptExternalLaunch { throw ActivityNotFoundException("no handler") },
        )
        assertEquals(
            ExternalLaunchOutcome.SECURITY_BLOCKED,
            attemptExternalLaunch { throw SecurityException("managed profile") },
        )
        assertEquals(ExternalLaunchOutcome.LAUNCHED, attemptExternalLaunch {})
        assertNull(
            externalNavigationFailure(
                ExternalNavigationTarget.PRIVACY_POLICY,
                ExternalLaunchOutcome.LAUNCHED,
            ),
        )
        assertEquals(
            R.string.external_settings_unavailable,
            checkNotNull(
                externalNavigationFailure(
                    ExternalNavigationTarget.APP_SETTINGS,
                    ExternalLaunchOutcome.UNRESOLVED,
                ),
            ).messageRes(),
        )
        assertEquals(
            R.string.external_settings_blocked,
            checkNotNull(
                externalNavigationFailure(
                    ExternalNavigationTarget.APP_SETTINGS,
                    ExternalLaunchOutcome.SECURITY_BLOCKED,
                ),
            ).messageRes(),
        )
        assertEquals(
            R.string.external_privacy_unavailable,
            checkNotNull(
                externalNavigationFailure(
                    ExternalNavigationTarget.PRIVACY_POLICY,
                    ExternalLaunchOutcome.UNRESOLVED,
                ),
            ).messageRes(),
        )
        assertEquals(
            R.string.external_privacy_blocked,
            checkNotNull(
                externalNavigationFailure(
                    ExternalNavigationTarget.PRIVACY_POLICY,
                    ExternalLaunchOutcome.SECURITY_BLOCKED,
                ),
            ).messageRes(),
        )
    }

    @Test
    fun `external launcher builds exact settings and privacy intents`() {
        val recording = RecordingContext(context)

        assertEquals(
            ExternalLaunchOutcome.LAUNCHED,
            launchExternal(recording, ExternalNavigationTarget.APP_SETTINGS),
        )
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, recording.launchedIntent.action)
        assertEquals("package:${context.packageName}", recording.launchedIntent.data.toString())
        assertTrue(recording.launchedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        assertEquals(
            ExternalLaunchOutcome.LAUNCHED,
            launchExternal(recording, ExternalNavigationTarget.PRIVACY_POLICY),
        )
        assertEquals(Intent.ACTION_VIEW, recording.launchedIntent.action)
        assertEquals(PRIVACY_POLICY_URL, recording.launchedIntent.data.toString())
        assertTrue(recording.launchedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `privacy URL follows Korean and English context locale`() {
        assertEquals(PRIVACY_POLICY_URL, privacyPolicyUrl(Locale.ENGLISH))
        assertEquals(PRIVACY_POLICY_URL, privacyPolicyUrl(Locale.JAPANESE))
        assertEquals(PRIVACY_POLICY_KO_URL, privacyPolicyUrl(Locale.KOREAN))

        val koConfig = Configuration(context.resources.configuration).apply {
            setLocale(Locale.KOREAN)
        }
        val koContext = RecordingContext(context.createConfigurationContext(koConfig))
        assertEquals(
            ExternalLaunchOutcome.LAUNCHED,
            launchExternal(koContext, ExternalNavigationTarget.PRIVACY_POLICY),
        )
        assertEquals(PRIVACY_POLICY_KO_URL, koContext.launchedIntent.data.toString())
    }

    @Test
    fun `security exception on policy-blocked permission gate is assertive and retains Settings focus`() {
        val failure = mutableStateOf<ExternalNavigationFailure?>(null)
        compose.setContent {
            TeleCamProTheme {
                PermissionGate(
                    permanentlyDenied = true,
                    policyBlocked = true,
                    onRequest = {},
                    onOpenSettings = {
                        failure.value = externalNavigationFailure(
                            ExternalNavigationTarget.APP_SETTINGS,
                            attemptExternalLaunch { throw SecurityException("policy") },
                        )
                    },
                    onOpenPrivacy = {},
                    externalFailure = failure.value,
                )
            }
        }

        val settings = compose.onNode(hasText(context.getString(R.string.action_settings)).and(hasClickAction()))
        settings.requestFocus().assertIsFocused().performClick()
        compose.waitForIdle()

        settings.assertIsFocused()
        compose.onNodeWithText(context.getString(R.string.external_settings_blocked))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test
    fun `unresolved Pro settings policy offers bundled fallback without stealing row focus`() {
        compose.setContent {
            TeleCamProTheme {
                ProSheet(
                    state = CameraUiState(),
                    actions = actions,
                    onDismiss = {},
                    initialTab = ProSheetTab.ADVANCED,
                    externalLauncher = { ExternalLaunchOutcome.UNRESOLVED },
                )
            }
        }
        compose.waitForIdle()

        val policy = compose.onNode(
            hasText(context.getString(R.string.action_privacy_policy)).and(hasClickAction()),
        )
        policy.requestFocus().assertIsFocused().performClick()
        compose.waitForIdle()

        policy.assertIsFocused()
        compose.onNodeWithText(context.getString(R.string.external_privacy_unavailable))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        compose.onNodeWithText(context.getString(R.string.action_view_in_app)).performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText(context.getString(R.string.privacy_fallback_body)).assertExists()
    }

    @Test
    fun `external recovery and bundled policy are localized in Korean`() {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.KOREAN)
        }
        val ko = context.createConfigurationContext(configuration)

        assertEquals("기기 정책으로 앱 설정을 열 수 없습니다.", ko.getString(R.string.external_settings_blocked))
        assertEquals("앱에서 보기", ko.getString(R.string.action_view_in_app))
        assertTrue(ko.getString(R.string.privacy_fallback_body).contains("개인정보를 수집하거나 공유하지 않으며"))
    }
}
