package me.hletrd.telecampro.gl

import org.junit.Assert.*
import org.junit.Test

class MakerLogSourceTest {
    @Test fun `monitor decode follows the published grey and white anchors`() {
        assertEquals(0.18, MakerLogSource.decode(0.48827246), 0.00001)
        assertEquals(1.0, MakerLogSource.decode(0.69455317), 0.00001)
    }

    @Test fun `native highlight headroom is not clamped in the source inverse`() {
        assertTrue(MakerLogSource.decode(0.95) > 7.9)
        assertTrue(MakerLogSource.decode(1.0) > 11.0)
        assertEquals(MakerLogSource.R0, MakerLogSource.decode(0.0), 1e-10)
    }

    @Test fun `toe join is continuous and decode is monotone over ten bit codes`() {
        assertEquals(0.01, MakerLogSource.decode(MakerLogSource.CUT_CODE - 1e-8), 1e-6)
        assertEquals(0.01, MakerLogSource.decode(MakerLogSource.CUT_CODE + 1e-8), 1e-6)
        var previous = MakerLogSource.decode(0.0)
        for (code in 1..1023) {
            val current = MakerLogSource.decode(code / 1023.0)
            assertTrue(current > previous)
            previous = current
        }
    }
}
