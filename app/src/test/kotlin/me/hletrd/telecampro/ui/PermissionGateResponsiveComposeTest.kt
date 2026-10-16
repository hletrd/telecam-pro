package me.hletrd.telecampro.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import me.hletrd.telecampro.PermissionGate
import me.hletrd.telecampro.R
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
class PermissionGateResponsiveComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var strings: Context
    private lateinit var failure: MutableState<ExternalNavigationFailure?>
    private var inAppOpens = 0

    private fun show(locale: Locale) {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        strings = context.createConfigurationContext(configuration)
        failure = mutableStateOf(null)
        inAppOpens = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides strings,
                LocalConfiguration provides strings.resources.configuration,
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                TeleCamProTheme {
                    Box(
                        Modifier
                            .requiredSize(width = 320.dp, height = 340.dp)
                            .testTag(VIEWPORT_TAG),
                    ) {
                        PermissionGate(
                            permanentlyDenied = true,
                            onRequest = {},
                            onOpenSettings = {
                                failure.value = ExternalNavigationFailure(
                                    ExternalNavigationTarget.APP_SETTINGS,
                                    ExternalNavigationFailureReason.SECURITY_BLOCKED,
                                )
                            },
                            onOpenPrivacy = {
                                failure.value = ExternalNavigationFailure(
                                    ExternalNavigationTarget.PRIVACY_POLICY,
                                    ExternalNavigationFailureReason.UNRESOLVED,
                                )
                            },
                            externalFailure = failure.value,
                            onOpenPrivacyInApp = { inAppOpens++ },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun action(@androidx.annotation.StringRes id: Int) = compose.onNode(
        hasText(strings.getString(id)).and(hasClickAction()),
    )

    private fun assertReachable(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        node.performScrollTo()
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertInside(viewport, bounds)
    }

    private fun assertInside(viewport: Rect, bounds: Rect) {
        assertTrue("node escaped viewport start: $bounds / $viewport", bounds.top >= viewport.top - 1f)
        assertTrue("node escaped viewport end: $bounds / $viewport", bounds.bottom <= viewport.bottom + 1f)
        assertTrue("node escaped viewport left: $bounds / $viewport", bounds.left >= viewport.left - 1f)
        assertTrue("node escaped viewport right: $bounds / $viewport", bounds.right <= viewport.right + 1f)
    }

    private fun verifyPrivacyFailure(locale: Locale) {
        show(locale)
        val privacy = action(R.string.action_privacy_policy)
        privacy.requestFocus().assertIsFocused().performClick()
        compose.waitForIdle()

        privacy.assertIsFocused()
        compose.onNodeWithText(strings.getString(R.string.external_privacy_unavailable))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        assertReachable(action(R.string.action_settings))
        assertReachable(privacy)
        val inApp = action(R.string.action_view_in_app)
        assertReachable(inApp)
        inApp.performClick()
        assertEquals(1, inAppOpens)
    }

    private fun verifySettingsFailure(locale: Locale) {
        show(locale)
        val settings = action(R.string.action_settings)
        settings.requestFocus().assertIsFocused().performClick()
        compose.waitForIdle()

        settings.assertIsFocused()
        val message = compose.onNodeWithText(strings.getString(R.string.external_settings_blocked))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        assertReachable(message)
        assertReachable(settings)
        assertReachable(action(R.string.action_privacy_policy))
    }

    @Test
    fun `English compact privacy failure keeps every recovery action reachable`() {
        verifyPrivacyFailure(Locale.ENGLISH)
    }

    @Test
    fun `Korean compact privacy failure keeps every recovery action reachable`() {
        verifyPrivacyFailure(Locale.KOREAN)
    }

    @Test
    fun `English compact settings failure keeps focus live region and actions stable`() {
        verifySettingsFailure(Locale.ENGLISH)
    }

    @Test
    fun `Korean compact settings failure keeps focus live region and actions stable`() {
        verifySettingsFailure(Locale.KOREAN)
    }

    private companion object {
        const val VIEWPORT_TAG = "permission-gate-viewport"
    }
}
