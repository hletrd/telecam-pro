package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.VideoColorModeBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSheetPolicyTest {
    @Test
    fun `Recent never repeats a row already pinned in My Menu`() {
        val pinned = FnSlot.entries.take(2)
        val recent = listOf(FnSlot.entries[1], FnSlot.entries[3], FnSlot.entries[0], FnSlot.entries[4])
        assertEquals(listOf(FnSlot.entries[3], FnSlot.entries[4]), myMenuRecentRows(pinned, recent))
        assertEquals(recent, myMenuRecentRows(emptyList(), recent))
    }

    @Test
    fun `every Gamma block has its own reason line`() {
        val texts = VideoColorModeBlock.entries.map(::videoColorModeBlockText)
        assertEquals(texts.size, texts.toSet().size)
    }
}
