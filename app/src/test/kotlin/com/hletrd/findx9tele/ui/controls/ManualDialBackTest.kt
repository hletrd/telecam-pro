package com.hletrd.findx9tele.ui.controls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualDialBackTest {

    @Test
    fun everyExpandedRulerConsumesBackBeforeActivity() {
        DialType.entries.forEach { assertTrue(manualDialConsumesBack(it)) }
    }

    @Test
    fun closedDialClusterLetsBackPropagate() {
        assertFalse(manualDialConsumesBack(null))
    }
}
