package me.hletrd.telecampro.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import me.hletrd.telecampro.camera.AfSpotSize
import me.hletrd.telecampro.camera.Antibanding
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.AudioScene
import me.hletrd.telecampro.camera.BitrateLevel
import me.hletrd.telecampro.camera.CameraStatusArgument
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.ColorEffect
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.HardwareKeyAction
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.PeakingColor
import me.hletrd.telecampro.camera.PeakingLevel
import me.hletrd.telecampro.camera.ProcessingLevel
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.camera.status
import me.hletrd.telecampro.ui.controls.localizedLabel
import me.hletrd.telecampro.video.AudioPortKind
import me.hletrd.telecampro.video.AudioRouteAvailability
import me.hletrd.telecampro.video.AudioRouteStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalizedMappingsRobolectricTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun context(language: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    private fun assertRendered(render: (Context) -> String) {
        for (language in listOf("en", "ko")) {
            assertTrue("$language label must not be blank", render(context(language)).isNotBlank())
        }
    }

    @Test
    fun `every control enum maps to a localized nonblank label`() {
        FocusMode.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        Antibanding.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        ProcessingLevel.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        ColorEffect.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        FlashMode.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        GridType.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        ShutterTimer.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        ShutterMode.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        WbMode.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        MeteringMode.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        DriveMode.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        VideoStabMode.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        AudioScene.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        AudioInputPreference.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        HardwareKeyAction.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        BitrateLevel.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        FnSlot.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        FrameLineType.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        AfSpotSize.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        PeakingLevel.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
        PeakingColor.entries.forEach { value -> assertRendered { it.localizedLabel(value) } }
    }

    @Test
    fun `every audio preference and port kind resolves in both locales`() {
        AudioInputPreference.entries.forEach { preference ->
            assertRendered { preference.resolve(it) }
        }
        AudioPortKind.entries.forEach { portKind ->
            val route = AudioRouteStatus(
                preference = AudioInputPreference.AUTO,
                availability = AudioRouteAvailability.READY,
                portKind = portKind,
            )
            assertRendered { route.resolve(it) }
        }
    }

    @Test
    fun `every audio route state resolves with and without optional device facts`() {
        val routes = listOf(
            AudioRouteStatus(AudioInputPreference.AUTO, AudioRouteAvailability.AUTO),
            AudioRouteStatus(
                AudioInputPreference.AUTO,
                AudioRouteAvailability.AUTO,
                AudioPortKind.USB,
                "Studio Mic",
            ),
            AudioRouteStatus(AudioInputPreference.AUTO, AudioRouteAvailability.AUTO_NO_MIC),
            AudioRouteStatus(AudioInputPreference.WIRED, AudioRouteAvailability.READY),
            AudioRouteStatus(
                AudioInputPreference.USB,
                AudioRouteAvailability.READY,
                AudioPortKind.USB,
                "Studio Mic",
            ),
            AudioRouteStatus(AudioInputPreference.BLUETOOTH, AudioRouteAvailability.UNAVAILABLE),
            AudioRouteStatus(AudioInputPreference.AUTO, AudioRouteAvailability.STARTING),
            AudioRouteStatus(AudioInputPreference.AUTO, AudioRouteAvailability.OFF),
        )
        routes.forEach { route -> assertRendered { route.resolve(it) } }
    }

    @Test
    fun `numeric status arguments remain typed until resource formatting`() {
        val status = CameraStatusMessage.OUTPUT_SAVED_PENDING.status(
            CameraStatusArgument.Number(42),
        )

        assertRendered { status.resolve(it) }
    }
}
