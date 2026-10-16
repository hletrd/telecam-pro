package me.hletrd.telecampro.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AGG2-23: a per-readback analysis exception logs once per class, never once per frame. */
class AnalysisFailureLogGateTest {
    @Test
    fun `a repeating failure logs once per class`() {
        val gate = AnalysisFailureLogGate()

        assertTrue(gate.shouldLog(IllegalStateException("ae")))
        repeat(100) { assertFalse(gate.shouldLog(IllegalStateException("ae again"))) }
        assertTrue("a different class is a different failure", gate.shouldLog(ArithmeticException()))
        assertFalse(gate.shouldLog(ArithmeticException()))
    }

    @Test
    fun `distinct classes are capped per generation`() {
        val gate = AnalysisFailureLogGate(maxClasses = 2)

        assertTrue(gate.shouldLog(IllegalStateException()))
        assertTrue(gate.shouldLog(IllegalArgumentException()))
        assertFalse(gate.shouldLog(ArithmeticException()))
        assertFalse(gate.shouldLog(UnsupportedOperationException()))
    }
}
