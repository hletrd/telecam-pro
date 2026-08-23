package me.hletrd.telecampro.ui.controls

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.util.Locale
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1056dp-xxhdpi")
class ManualDialIsoAccessibilityComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val base: Context = ApplicationProvider.getApplicationContext()
    private val actions = Proxy.newProxyInstance(
        CameraActions::class.java.classLoader,
        arrayOf(CameraActions::class.java),
    ) { _, method, _ -> if (method.returnType == java.lang.Boolean.TYPE) false else null } as CameraActions

    private fun localizedContext(language: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    private fun showIsoRuler(language: String, exposureMode: ExposureMode, iso: Int = 9100) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext(language)) {
                TeleCamProTheme {
                    ManualDialCluster(
                        state = CameraUiState(
                            controls = ManualControls(exposureMode = exposureMode, iso = iso),
                        ),
                        actions = actions,
                        openDial = DialType.ISO,
                        onSelectDial = {},
                        onCloseDial = {},
                        glyphRotation = 0f,
                        onOpenFnMenu = {},
                        compact = true,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertAdjustableIsoState(expected: String) {
        compose.onNode(
            hasContentDescription("ISO").and(hasStateDescription(expected)),
            useUnmergedTree = true,
        ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    }

    @Test
    fun `Korean automatic ISO ruler localizes the adjustable state`() {
        showIsoRuler(language = "ko", exposureMode = ExposureMode.SHUTTER)

        assertAdjustableIsoState("자동 ISO 9100")
    }

    @Test
    fun `English automatic ISO ruler keeps the camera abbreviation`() {
        showIsoRuler(language = "en", exposureMode = ExposureMode.SHUTTER)

        assertAdjustableIsoState("Auto ISO 9100")
    }

    @Test
    fun `manual ISO ruler keeps the ordinary non-auto state`() {
        showIsoRuler(language = "ko", exposureMode = ExposureMode.MANUAL)

        assertAdjustableIsoState("ISO 9100")
    }
}
