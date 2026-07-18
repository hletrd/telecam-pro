package com.hletrd.findx9tele.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingSemanticsTest {
    @Test
    fun `slider and switch helpers bind visible names to interactive state`() {
        assertEquals(SettingSemantics("ISO", "1250"), sliderSettingSemantics("ISO", "1250"))
        assertEquals(SettingSemantics("Peaking", "On"), toggleSettingSemantics("Peaking", true))
        assertEquals(SettingSemantics("Peaking", "Off"), toggleSettingSemantics("Peaking", false))
    }

    @Test
    fun `settings sheet selects a side panel only in landscape`() {
        assertTrue(proSheetUsesSideLayout(900f, 400f))
        assertFalse(proSheetUsesSideLayout(400f, 900f))
        assertFalse(proSheetUsesSideLayout(500f, 500f))
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)
    private fun assertFalse(value: Boolean) = org.junit.Assert.assertFalse(value)
}
