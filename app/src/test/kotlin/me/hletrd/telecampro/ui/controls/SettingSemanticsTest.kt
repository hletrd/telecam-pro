package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.ProcessingLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The accessibility builders behind the settings rows. Each one exists so the row's VISIBLE label and
 * its spoken name cannot drift apart, and each is pinned here — the previously untested
 * [dropdownSettingSemantics] and [segmentedOptionName] included, since an unpinned builder is
 * exactly how the segmented row shipped with no binding at all.
 *
 * The three [SettingSemantics] builders return a name AND a state because their rows publish both
 * (`contentDescription` + `stateDescription`, ProControls.kt); the segmented chip returns a name
 * alone because that is all its node sets. Assert what production reads: pinning a field no call site
 * consumes reports coverage of something no user can hear.
 *
 * (Layout policy lives in CameraUiPolicyTest; this file is accessibility only.)
 */
class SettingSemanticsTest {
    @Test
    fun `slider and switch helpers bind visible names to interactive state`() {
        assertEquals(SettingSemantics("ISO", "1250"), sliderSettingSemantics("ISO", "1250"))
        assertEquals(SettingSemantics("Peaking", "On"), toggleSettingSemantics("Peaking", "On"))
        assertEquals(SettingSemantics("Peaking", "Off"), toggleSettingSemantics("Peaking", "Off"))
    }

    @Test
    fun `a dropdown row is findable by its label alone and states its selection`() {
        val row = dropdownSettingSemantics("Camera Override", "Default")
        assertEquals("Camera Override", row.label)
        assertEquals("Default", row.state)
    }

    @Test
    fun `every segmented chip names its own row instead of announcing a bare value`() {
        // The failure this closes: the Image tab draws "Sharpness" and "NR" consecutively and BOTH
        // label their chips with processingLevelLabel, so two rows of chips announced "Off, Fast, HQ"
        // with nothing naming which control the user was on. The row label must appear in the name.
        //
        // Exhaustive exact strings, and that is the whole of it: the name is a pure function of the
        // row label and the chip's OWN option (never the row's current selection, which would rename
        // every chip on each tap and cost TalkBack its focus), so pinning each pair pins both the
        // "names its row" and the "distinct per option" properties outright. A second test asserting
        // those consequences would restate these lines, not check them.
        listOf("Sharpness", "NR").forEach { row ->
            ProcessingLevel.entries.forEach { level ->
                val option = processingLevelLabel(level)
                assertEquals("$row $option", segmentedOptionName(row, option))
            }
        }
        // Two rows sharing an option value stay distinguishable by name alone.
        assertEquals("Sharpness Off", segmentedOptionName("Sharpness", "Off"))
        assertEquals("NR Off", segmentedOptionName("NR", "Off"))

        // TransferSelector is SegmentedSelector hand-rolled for one enum and uses the same builder
        // with the literal row label it draws.
        ColorTransfer.entries.forEach { transfer ->
            assertEquals(
                "Transfer ${transferLabel(transfer)}",
                segmentedOptionName("Transfer", transferLabel(transfer)),
            )
        }
    }
}
