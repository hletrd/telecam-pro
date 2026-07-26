package me.hletrd.findx9tele.ui.controls

import me.hletrd.findx9tele.camera.ColorTransfer
import me.hletrd.findx9tele.camera.ProcessingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility builders behind the settings rows. Each one exists so the row's VISIBLE label and
 * its spoken name cannot drift apart, and each is pinned here — the previously untested
 * [dropdownSettingSemantics] and [segmentedOptionSemantics] included, since an unpinned builder is
 * exactly how the segmented row shipped with no binding at all.
 *
 * (Layout policy lives in CameraUiPolicyTest; this file is accessibility only.)
 */
class SettingSemanticsTest {
    @Test
    fun `slider and switch helpers bind visible names to interactive state`() {
        assertEquals(SettingSemantics("ISO", "1250"), sliderSettingSemantics("ISO", "1250"))
        assertEquals(SettingSemantics("Peaking", "On"), toggleSettingSemantics("Peaking", true))
        assertEquals(SettingSemantics("Peaking", "Off"), toggleSettingSemantics("Peaking", false))
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
        listOf("Sharpness", "NR").forEach { row ->
            ProcessingLevel.entries.forEach { level ->
                val option = processingLevelLabel(level)
                val chip = segmentedOptionSemantics(row, option)
                assertEquals("$row $option", chip.label)
                assertEquals(option, chip.state)
                assertTrue("the row label must survive into the chip name", chip.label.startsWith(row))
            }
        }
        // Two rows sharing an option value stay distinguishable by name alone.
        assertEquals("Sharpness Off", segmentedOptionSemantics("Sharpness", "Off").label)
        assertEquals("NR Off", segmentedOptionSemantics("NR", "Off").label)

        // TransferSelector is SegmentedSelector hand-rolled for one enum and uses the same builder
        // with the literal row label it draws.
        ColorTransfer.entries.forEach { transfer ->
            val chip = segmentedOptionSemantics("Transfer", transferLabel(transfer))
            assertEquals("Transfer ${transferLabel(transfer)}", chip.label)
            assertEquals(transferLabel(transfer), chip.state)
        }
    }

    @Test
    fun `the option state is the value alone so a name stays stable per chip`() {
        // Name and state are deliberately NOT the same string: the name identifies the chip (constant
        // for the life of the row) while the state is what the chip's value is. A name that carried
        // the row's CURRENT selection would rename every chip on each tap.
        val fast = segmentedOptionSemantics("Sharpness", "Fast")
        val hq = segmentedOptionSemantics("Sharpness", "HQ")
        assertEquals("Fast", fast.state)
        assertEquals("HQ", hq.state)
        assertTrue(fast.label != hq.label)
    }
}
