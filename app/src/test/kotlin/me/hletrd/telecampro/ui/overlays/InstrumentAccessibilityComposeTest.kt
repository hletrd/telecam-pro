package me.hletrd.telecampro.ui.overlays

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import me.hletrd.telecampro.camera.HistogramData
import me.hletrd.telecampro.camera.WaveformData
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import me.hletrd.telecampro.video.AudioOverloadState
import me.hletrd.telecampro.video.audioOverloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InstrumentAccessibilityComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val base = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `coarse instrument reducers cover pending silence clipping and luma bounds`() {
        assertEquals(
            listOf(
                AudioAccessibilityState.SILENT,
                AudioAccessibilityState.SIGNAL,
                AudioAccessibilityState.NEAR_CLIPPING,
                AudioAccessibilityState.PENDING,
            ),
            audioAccessibilityStates(
                levels = listOf(0f, 0.5f, 0.9f, Float.NaN),
                overloads = listOf(
                    AudioOverloadState.NORMAL,
                    AudioOverloadState.NORMAL,
                    AudioOverloadState.NEAR_CLIPPING,
                    AudioOverloadState.NORMAL,
                ),
            ),
        )
        assertEquals(
            listOf(AudioAccessibilityState.HIGH, AudioAccessibilityState.CLIPPING),
            audioAccessibilityStates(
                listOf(0.7f, 0.7f),
                listOf(AudioOverloadState.NORMAL, AudioOverloadState.CLIPPING),
            ),
        )
        assertEquals(
            audioAccessibilityStates(listOf(0.20f, 0.86f)),
            audioAccessibilityStates(listOf(0.55f, 0.98f)),
        )
        assertEquals(
            listOf(
                AudioAccessibilityState.SILENT,
                AudioAccessibilityState.HIGH,
                AudioAccessibilityState.NEAR_CLIPPING,
                AudioAccessibilityState.CLIPPING,
            ),
            audioAccessibilityStates(
                levels = listOf(0.02f, 0.60f, 0.85f, 0.85f),
                overloads = listOf(
                    AudioOverloadState.NORMAL,
                    AudioOverloadState.NORMAL,
                    AudioOverloadState.NEAR_CLIPPING,
                    AudioOverloadState.CLIPPING,
                ),
            ),
        )

        val clippedSine = me.hletrd.telecampro.video.channelLevelFrame(
            shortArrayOf(0, Short.MAX_VALUE, 0, Short.MIN_VALUE),
            readCount = 4,
            channelCount = 1,
        )
        assertEquals(
            listOf(AudioAccessibilityState.CLIPPING),
            audioAccessibilityStates(
                clippedSine.rms.toList(),
                clippedSine.peaks.map(::audioOverloadState),
            ),
        )

        assertEquals(HistogramAccessibilityState.PENDING, histogramAccessibilityState(null))
        assertEquals(
            HistogramAccessibilityState.SHADOWS_CLIPPED,
            histogramAccessibilityState(histogram(shadows = 1, midtones = 999, highlights = 0)),
        )
        assertEquals(
            HistogramAccessibilityState.HIGHLIGHTS_CLIPPED,
            histogramAccessibilityState(histogram(shadows = 0, midtones = 999, highlights = 1)),
        )
        assertEquals(
            HistogramAccessibilityState.BOTH_CLIPPED,
            histogramAccessibilityState(histogram(shadows = 10, midtones = 980, highlights = 10)),
        )
        assertEquals(
            HistogramAccessibilityState.NO_EDGE_CLIPPING,
            histogramAccessibilityState(histogram(shadows = 0, midtones = 1000, highlights = 0)),
        )

        val waveform = WaveformData(columns = 2, rows = 11, bins = IntArray(22).apply {
            this[2] = 1 // 80%
            this[11 + 8] = 1 // 20%
        })
        assertEquals(WaveformAccessibilityRange(20, 80), waveformAccessibilityRange(waveform))
        assertEquals(null, waveformAccessibilityRange(WaveformData(1, 4, IntArray(4))))
    }

    @Test
    fun `empty instruments expose English pending states without live announcements`() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("en")) {
                TeleCamProTheme {
                    Column {
                        AudioMeter(emptyList())
                        HistogramOverlay(null)
                        WaveformOverlay(null)
                    }
                }
            }
        }
        compose.waitForIdle()

        assertNonLive("Audio level meter", "Waiting for audio levels")
        assertNonLive("Histogram", "Waiting for histogram data")
        assertNonLive("Waveform", "Waiting for waveform data")
    }

    @Test
    fun `English live instruments expose coarse non-live readings`() {
        show("en")

        assertNonLive("Audio level meter", "Channel 1 silent, Channel 2 near clipping")
        assertNonLive("Histogram", "Shadows and highlights clipped")
        assertNonLive("Waveform", "Luma range 20 to 80 percent")
    }

    @Test
    fun `Korean live instruments expose localized coarse non-live readings`() {
        show("ko")

        assertNonLive("오디오 레벨 미터", "채널 1 무음, 채널 2 클리핑에 가까움")
        assertNonLive("히스토그램", "암부 및 명부 클리핑")
        assertNonLive("파형", "휘도 범위 20~80퍼센트")
    }

    private fun show(language: String) {
        val waveform = WaveformData(columns = 2, rows = 11, bins = IntArray(22).apply {
            this[2] = 1
            this[11 + 8] = 1
        })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext(language)) {
                TeleCamProTheme {
                    Column {
                        AudioMeter(
                            levels = listOf(0f, 0.9f),
                            overloads = listOf(
                                AudioOverloadState.NORMAL,
                                AudioOverloadState.NEAR_CLIPPING,
                            ),
                        )
                        HistogramOverlay(histogram(shadows = 10, midtones = 980, highlights = 10))
                        WaveformOverlay(waveform)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertNonLive(label: String, state: String) {
        val node = compose.onNodeWithContentDescription(label).assert(hasStateDescription(state))
        assertFalse(
            "frequent instrument $label became a live region",
            node.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion),
        )
    }

    private fun localizedContext(language: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    private fun histogram(shadows: Int, midtones: Int, highlights: Int): HistogramData {
        val luma = IntArray(256).apply {
            this[0] = shadows
            this[128] = midtones
            this[255] = highlights
        }
        return HistogramData(luma, IntArray(256), IntArray(256), IntArray(256))
    }
}
