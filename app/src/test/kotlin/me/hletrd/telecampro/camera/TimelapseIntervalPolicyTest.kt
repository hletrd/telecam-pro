package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelapseIntervalPolicyTest {
    @Test
    fun `shared interval authority clamps below inside and above its UI domain`() {
        assertEquals(1, normalizeTimelapseIntervalSeconds(-20))
        assertEquals(1, normalizeTimelapseIntervalSeconds(1))
        assertEquals(17, normalizeTimelapseIntervalSeconds(17))
        assertEquals(30, normalizeTimelapseIntervalSeconds(30))
        assertEquals(30, normalizeTimelapseIntervalSeconds(300))
    }
}
