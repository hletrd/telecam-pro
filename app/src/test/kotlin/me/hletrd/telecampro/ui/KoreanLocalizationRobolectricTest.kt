package me.hletrd.telecampro.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.CameraStatusArgument
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.status
import me.hletrd.telecampro.video.AudioPortKind
import me.hletrd.telecampro.video.AudioRouteAvailability
import me.hletrd.telecampro.video.AudioRouteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KoreanLocalizationRobolectricTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun context(language: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun `every typed status resolves in English and Korean without controlling metadata`() {
        val english = context("en")
        val korean = context("ko")
        CameraStatusMessage.entries.forEach { id ->
            val argument = when (id) {
                CameraStatusMessage.OUTPUT_SAVED_PENDING,
                CameraStatusMessage.OUTPUT_SAVED_PENDING_RECOVERY,
                CameraStatusMessage.MEMORY_SLOT_SAVED,
                CameraStatusMessage.MEMORY_SLOT_EMPTY,
                CameraStatusMessage.MEMORY_SLOT_LOADED,
                -> arrayOf(CameraStatusArgument.Text("MR1"))
                CameraStatusMessage.LENS_UNAVAILABLE_UNCHANGED ->
                    arrayOf(CameraStatusArgument.Lens(LensChoice.TELE3X))
                CameraStatusMessage.AUDIO_INPUT_USING_DEFAULT ->
                    arrayOf(CameraStatusArgument.AudioInput(AudioInputPreference.AUTO))
                else -> emptyArray()
            }
            val status = id.status(*argument)
            assertTrue(status.resolve(english).isNotBlank())
            assertTrue(status.resolve(korean).isNotBlank())
            assertEquals(status.durationMs, id.status(*argument).durationMs)
        }
    }

    @Test
    fun `Korean covers permission settings status focus review delete timer and storage`() {
        val ko = context("ko")
        val ids = listOf(
            R.string.camera_permission_required,
            R.string.settings_tab_shoot,
            R.string.section_recording_format,
            R.string.status_camera_unavailable_reopen,
            R.string.focus_confidence_too_close,
            R.string.a11y_start_recording,
            R.string.review_error_open_image,
            R.string.review_delete_family_body,
            R.string.a11y_unavailable_while_recording,
            R.string.a11y_selected_teleconverter_on,
            R.string.a11y_raw_capture_review,
            R.string.a11y_media_review,
            R.string.a11y_focus_locked,
            R.string.a11y_autofocus_failed,
            R.string.output_10_bit_video_stills_off,
            R.string.video_tone_mapped_source,
            R.string.mr_default_photo_name,
            R.string.mr_default_video_name,
        )
        ids.forEach { id ->
            val rendered = ko.getString(id)
            assertTrue(rendered.isNotBlank())
            assertFalse("resource $id unexpectedly fell back to English: $rendered", rendered.matches(Regex("[A-Za-z ]+[.!?]?")))
        }
        assertEquals("3초 남음", ko.resources.getQuantityString(R.plurals.a11y_seconds_remaining, 3, 3))
        assertEquals("42장 촬영 가능", ko.resources.getQuantityString(R.plurals.a11y_shots_remaining, 42, 42))
        assertEquals("배터리 72퍼센트", ko.resources.getQuantityString(R.plurals.a11y_battery_percent, 72, 72))
        assertEquals(
            "MR2: 비어 있음",
            CameraStatusMessage.MEMORY_SLOT_EMPTY
                .status(CameraStatusArgument.Text("MR2"))
                .resolve(ko),
        )
        assertEquals(
            "MR2 불러오기 완료",
            CameraStatusMessage.MEMORY_SLOT_LOADED
                .status(CameraStatusArgument.Text("MR2"))
                .resolve(ko),
        )
    }

    @Test
    fun `audio routes localize while hardware product identity remains verbatim`() {
        val ko = context("ko")
        val route = AudioRouteStatus(
            AudioInputPreference.USB,
            AudioRouteAvailability.READY,
            AudioPortKind.USB,
            "Rode VideoMic",
        )
        val rendered = route.resolve(ko)
        assertTrue(rendered.contains("USB 마이크"))
        assertTrue(rendered.contains("Rode VideoMic"))
        assertTrue(rendered.endsWith("준비됨"))
    }

    @Test
    fun `documented camera abbreviation allow list remains stable`() {
        val en = context("en")
        val ko = context("ko")
        val ids = listOf(
            R.string.label_iso,
            R.string.label_wb,
            R.string.label_ss,
            R.string.label_ev,
            R.string.label_af,
            R.string.label_nr,
            R.string.label_fps,
            R.string.label_fn,
            R.string.label_open_gate,
        )
        ids.forEach { assertEquals(en.getString(it), ko.getString(it)) }
    }
}
