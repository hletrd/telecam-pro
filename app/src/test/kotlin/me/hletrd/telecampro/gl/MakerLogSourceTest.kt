package me.hletrd.telecampro.gl

import org.junit.Assert.*
import org.junit.Test

class MakerLogSourceTest {
    @Test fun `monitor decode matches OPPO ACES V2 black shadow grey white and headroom anchors`() {
        // Values from OPPO's O-Log2_to_ACES_AP0_V2.ctl forward function, not Apple Log.
        val anchors = listOf(
            0.1199148853601808 to 0.0,
            0.13213779041367546 to 0.001,
            0.2079582832203723 to 0.01,
            0.4878881393293232 to 0.18,
            0.69417851621953 to 1.0,
            0.9996129347013585 to 12.0,
        )
        for ((code, linear) in anchors) assertEquals(linear, MakerLogSource.decode(code), 1e-10)
    }

    @Test fun `source inverse retains below-black values and highlight headroom until display mapping`() {
        assertTrue(MakerLogSource.decode(0.0) < 0.0)
        assertTrue(MakerLogSource.decode(1.0) > 12.0)
        // The old quadratic toe crushed this positive OPPO shadow to a negative value.
        assertTrue(MakerLogSource.decode(0.13) > 0.0)
    }

    @Test fun `source inverse is monotone across every ten bit code`() {
        var previous = MakerLogSource.decode(0.0)
        for (code in 1..1023) {
            val current = MakerLogSource.decode(code / 1023.0)
            assertTrue(current > previous)
            previous = current
        }
    }
}
