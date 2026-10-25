package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.camera.AfSpotSize
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.AudioScene
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.ExposureStep
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.VideoStabMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlLabelBranchTest {
    private fun <T> assertLabels(expected: Map<T, String>, label: (T) -> String) {
        expected.forEach { (value, text) -> assertEquals(text, label(value)) }
    }

    @Test
    fun `soundtrack format labels stay Latin and each format names its own trade-off`() {
        assertLabels(
            mapOf(
                me.hletrd.telecampro.video.AudioCodec.AAC_LC to "AAC-LC",
                me.hletrd.telecampro.video.AudioCodec.HE_AAC to "HE-AAC",
                me.hletrd.telecampro.video.AudioCodec.HE_AAC_V2 to "HE-AAC v2",
                me.hletrd.telecampro.video.AudioCodec.XHE_AAC to "xHE-AAC",
            ),
            ::audioCodecLabel,
        )
        val captions = me.hletrd.telecampro.video.AudioCodec.entries.map(::audioCodecCaption)
        assertEquals(
            listOf(
                me.hletrd.telecampro.R.string.caption_audio_aac_lc,
                me.hletrd.telecampro.R.string.caption_audio_he_aac,
                me.hletrd.telecampro.R.string.caption_audio_he_aac_v2,
                me.hletrd.telecampro.R.string.caption_audio_xhe_aac,
            ),
            captions,
        )
        assertEquals(
            listOf(
                me.hletrd.telecampro.R.string.audio_channels_mono,
                me.hletrd.telecampro.R.string.audio_channels_stereo,
            ),
            me.hletrd.telecampro.video.AudioChannelMode.entries.map(::audioChannelLabel),
        )
    }

    @Test
    fun `exposure and autofocus option labels are exhaustive`() {
        assertLabels(
            mapOf(
                ExposureMode.PROGRAM to "P",
                ExposureMode.SHUTTER to "S",
                ExposureMode.ISO to "ISO",
                ExposureMode.MANUAL to "M",
            ),
            ::exposureModeLetter,
        )
        assertLabels(
            mapOf(
                ExposureStep.THIRD to "1/3",
                ExposureStep.HALF to "1/2",
                ExposureStep.FULL to "1",
            ),
            ::exposureStepLabel,
        )
        assertLabels(
            mapOf(
                AfSpotSize.SMALL to "S",
                AfSpotSize.MEDIUM to "M",
                AfSpotSize.LARGE to "L",
            ),
            ::afSpotSizeLabel,
        )
    }

    @Test
    fun `teleconverter host caption distinguishes optical measured and unknown hosts`() {
        assertEquals(
            TeleconverterHostCaption.ThreeTimes,
            teleconverterHostCaption(70f, teleIsOptical = true),
        )
        assertEquals(
            TeleconverterHostCaption.Measured("50 mm"),
            teleconverterHostCaption(50f, teleIsOptical = false),
        )
        assertEquals(
            TeleconverterHostCaption.Main,
            teleconverterHostCaption(0f, teleIsOptical = false),
        )
    }

    @Test
    fun `video audio and frame option labels are exhaustive`() {
        assertLabels(
            mapOf(
                VideoStabMode.OFF to "Off",
                VideoStabMode.STANDARD to "Standard",
                VideoStabMode.ENHANCED to "Active",
            ),
            ::videoStabModeLabel,
        )
        assertLabels(
            mapOf(
                AudioScene.STANDARD to "Standard",
                AudioScene.SOUND_FOCUS to "Sound Focus",
                AudioScene.SOUND_STAGE to "Sound Stage",
            ),
            ::audioSceneLabel,
        )
        assertLabels(
            mapOf(
                AudioInputPreference.AUTO to "Auto",
                AudioInputPreference.BUILT_IN to "Phone",
                AudioInputPreference.WIRED to "Wired",
                AudioInputPreference.USB to "USB",
                AudioInputPreference.BLUETOOTH to "BT",
            ),
            ::audioInputPreferenceLabel,
        )
        assertLabels(
            mapOf(
                FrameLineType.OFF to "Off",
                FrameLineType.CINEMA to "2.39:1",
                FrameLineType.SQUARE to "1:1",
                FrameLineType.VERTICAL to "9:16",
            ),
            ::frameLineTypeLabel,
        )
    }
}
