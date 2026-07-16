package com.hletrd.findx9tele.ui.controls

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProSheetTabTest {

    @Test
    fun `nine tab selection model always has exactly one selected item`() {
        assertEquals(9, ProSheetTab.entries.size)
        for (selected in ProSheetTab.entries) {
            val items = proSheetTabSelection(selected)
            assertEquals(ProSheetTab.entries.toList(), items.map { it.tab })
            assertEquals(1, items.count { it.selected })
            assertEquals(selected, items.single { it.selected }.tab)
        }
    }

    @Test
    fun `rail source keeps selectable group selected state and tab role`() {
        val source = sequenceOf(
            File("app/src/main/kotlin/com/hletrd/findx9tele/ui/controls/ProSheet.kt"),
            File("src/main/kotlin/com/hletrd/findx9tele/ui/controls/ProSheet.kt"),
        ).firstOrNull(File::isFile) ?: error("ProSheet.kt not found from ${File(".").absolutePath}")
        val text = source.readText()

        assertTrue(text.contains(".selectableGroup()"))
        assertTrue(text.contains(".selectable(selected = selected, role = Role.Tab, onClick = onClick)"))
    }
}
