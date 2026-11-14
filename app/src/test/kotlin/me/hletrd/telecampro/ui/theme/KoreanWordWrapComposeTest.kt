package me.hletrd.telecampro.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Korean captions wrap between words (UI review 2026-11-14): with Compose's default word break every
 * Hangul syllable pair was a break opportunity, and the settings captions split "늘/어납니다".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ko-w411dp-h905dp-xxhdpi")
class KoreanWordWrapComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `theme text breaks Korean only at spaces`() {
        // A settings caption that split mid-word in the sheet, laid out at a sweep of widths so the
        // greedy wrap is forced to choose inside a word somewhere if it is allowed to.
        val caption = "ISO 및 M 모드의 감도입니다. 높을수록 밝지만 노이즈가 늘어납니다."
        val widths = (120..320 step 9).toList()
        val layouts = mutableMapOf<Int, TextLayoutResult>()
        compose.setContent {
            TeleCamProTheme {
                Column {
                    widths.forEach { w ->
                        Text(
                            caption,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(w.dp),
                            onTextLayout = { layouts[w] = it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val splits = widths.flatMap { w ->
            val layout = layouts.getValue(w)
            (0 until layout.lineCount - 1).mapNotNull { line ->
                val end = layout.getLineEnd(line)
                if (caption[end - 1] == ' ') null else "$w dp: ${caption.substring(layout.getLineStart(line), end)}/"
            }
        }
        assertTrue("lines broken inside a word: $splits", splits.isEmpty())
    }
}
