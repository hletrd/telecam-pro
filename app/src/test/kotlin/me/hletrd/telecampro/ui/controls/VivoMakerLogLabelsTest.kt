package me.hletrd.telecampro.ui.controls

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.MakerLogVendor
import me.hletrd.telecampro.camera.VideoColorMode
import me.hletrd.telecampro.gl.MakerLogSource
import me.hletrd.telecampro.gl.ShaderBindings
import me.hletrd.telecampro.gl.Shaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The experimental vivo OEM Log's user-facing text: EN + KO for every new string (CLAUDE.md), the
 * maker name kept Latin in both (trademark rule), and the OPPO labels unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class VivoMakerLogLabelsTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun context(language: String): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    @Test fun `every vivo string exists in English and Korean and says it is unverified`() {
        val en = context("en")
        val ko = context("ko")
        for (id in listOf(R.string.label_vivo_maker_log, R.string.hint_vivo_maker_log, R.string.caption_maker_log_vivo)) {
            val english = en.getString(id)
            val korean = ko.getString(id)
            assertNotEquals("Korean must be translated: $english", english, korean)
        }
        assertTrue(en.getString(R.string.label_vivo_maker_log).startsWith("vivo "))
        assertTrue(ko.getString(R.string.label_vivo_maker_log).startsWith("vivo "))
        assertTrue(en.getString(R.string.hint_vivo_maker_log).contains("Unverified"))
        assertTrue(ko.getString(R.string.hint_vivo_maker_log).contains("검증되지"))
        assertTrue(en.getString(R.string.caption_maker_log_vivo).contains("unverified"))
        assertTrue(ko.getString(R.string.caption_maker_log_vivo).contains("검증 안 됨"))
        // The vivo caption claims no bit depth: nothing measured what that stream carries.
        assertTrue(!en.getString(R.string.caption_maker_log_vivo).contains("bit"))
    }

    @Test fun `labels name vivo only for a vivo vendor and keep O-Log2 otherwise`() {
        assertEquals("O-Log2", transferLabel(ColorTransfer.MAKER_LOG))
        assertEquals("O-Log2", transferLabel(ColorTransfer.MAKER_LOG, MakerLogVendor.OPPO))
        assertEquals("vivo Log", transferLabel(ColorTransfer.MAKER_LOG, MakerLogVendor.VIVO))
        assertEquals("O-Log2", transferLabelShort(ColorTransfer.MAKER_LOG))
        assertEquals("vivo Log", transferLabelShort(ColorTransfer.MAKER_LOG, MakerLogVendor.VIVO))
        assertEquals("O-Log2", videoColorModeChipValue(VideoColorMode.MAKER_LOG, ColorTransfer.MAKER_LOG))
        assertEquals("vivo Log",
            videoColorModeChipValue(VideoColorMode.MAKER_LOG, ColorTransfer.MAKER_LOG, MakerLogVendor.VIVO))
        assertEquals("vivo Log", videoLookLabel(ColorTransfer.MAKER_LOG, rawLog = false, makerVendor = MakerLogVendor.VIVO))
        // Non-maker transfers ignore the vendor entirely.
        assertEquals("S-Log3", transferLabel(ColorTransfer.SLOG3, MakerLogVendor.VIVO))
        assertEquals("SLOG3", transferLabelShort(ColorTransfer.SLOG3, MakerLogVendor.VIVO))
    }

    @Test fun `the shader declares the curve selector and inverts only the OPPO default`() {
        assertTrue(ShaderBindings.U_SOURCE_MAKER_CURVE in ShaderBindings.uniforms)
        assertTrue(Shaders.FRAGMENT.contains("uniform int ${ShaderBindings.U_SOURCE_MAKER_CURVE};"))
        assertTrue(Shaders.FRAGMENT.contains(
            "if (uSourceMaker == 1) return uSourceMakerCurve == ${MakerLogSource.CURVE_AS_DELIVERED} ? c : makerDisplaySample(c);",
        ))
        // Recording passthrough is untouched by the curve selector.
        assertTrue(Shaders.FRAGMENT.contains("((uTransfer == 9 && uSourceMaker == 1) || (uTransfer == 1 && uSourceHlg == 1))"))
    }
}
