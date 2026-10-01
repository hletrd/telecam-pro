package me.hletrd.telecampro.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import me.hletrd.telecampro.camera.ExposureMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** AGG4-71: the exposure meter speaks a name and a metered value, in English and Korean. */
@RunWith(RobolectricTestRunner::class)
class ExposureMeterSpeechTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun context(language: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    private fun spoken(language: String, speech: ExposureMeterSpeech): Pair<String, String> {
        val ctx = context(language)
        val state = speech.value?.let { ctx.getString(speech.stateText, it) } ?: ctx.getString(speech.stateText)
        return ctx.getString(speech.name) to state
    }

    @Test fun `manual mode speaks as a light meter`() {
        val metered = exposureMeterSpeech(ExposureMode.MANUAL, manualEv = 0.33f, compensationEv = 0f)
        assertEquals("Exposure meter" to "Metered +0.3 EV", spoken("en", metered))
        assertEquals("노출계" to "측광값 +0.3 EV", spoken("ko", metered))
        val pending = exposureMeterSpeech(ExposureMode.MANUAL, manualEv = null, compensationEv = 0f)
        assertEquals("Exposure meter" to "Metering…", spoken("en", pending))
        assertEquals("노출계" to "측광 중…", spoken("ko", pending))
        assertEquals("-1.0", exposureMeterSpeech(ExposureMode.MANUAL, -1f, 0f).value)
    }

    @Test fun `priority modes speak the compensation dial`() {
        ExposureMode.entries.filter { it != ExposureMode.MANUAL }.forEach { mode ->
            val speech = exposureMeterSpeech(mode, manualEv = 2f, compensationEv = -0.7f)
            assertEquals(mode.name, "Exposure compensation" to "-0.7 EV", spoken("en", speech))
            assertEquals(mode.name, "노출 보정" to "-0.7 EV", spoken("ko", speech))
        }
        assertEquals("±0.0", exposureMeterSpeech(ExposureMode.PROGRAM, null, 0f).value)
    }
}
