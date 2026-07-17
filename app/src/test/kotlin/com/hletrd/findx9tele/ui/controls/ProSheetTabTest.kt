package com.hletrd.findx9tele.ui.controls

import org.junit.Assert.assertEquals
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

    // (A former second test grepped ProSheet.kt's SOURCE TEXT for `.selectable(...)` substrings —
    // it verified nothing about the rendered semantics tree and passed as long as the characters
    // appeared anywhere in the file, so it was removed. Real Role.Tab/selected verification needs
    // an instrumented Compose UI test; per repo convention, Compose-only behavior is covered by
    // on-device verification.)
}
