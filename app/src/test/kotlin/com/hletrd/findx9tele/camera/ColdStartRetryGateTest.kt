package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdStartRetryGateTest {
    @Test
    fun `stale or paused failure is ignored without consuming retry`() {
        val gate = ColdStartRetryGate(maxAttempts = 1)

        assertEquals(
            ColdStartRetryGate.Failure.Ignore,
            gate.failed(expectedGeneration = 4, currentGeneration = 5, canRun = true),
        )
        assertEquals(
            ColdStartRetryGate.Failure.Ignore,
            gate.failed(expectedGeneration = 5, currentGeneration = 5, canRun = false),
        )

        val retry = gate.failed(5, 5, canRun = true) as ColdStartRetryGate.Failure.Retry
        assertTrue(gate.claim(retry.token, currentGeneration = 5, canRun = true))
    }

    @Test
    fun `one retry token has exactly one claim owner`() {
        val gate = ColdStartRetryGate(maxAttempts = 3)
        val retry = gate.failed(7, 7, canRun = true) as ColdStartRetryGate.Failure.Retry

        assertEquals(
            ColdStartRetryGate.Failure.Ignore,
            gate.failed(7, 7, canRun = true),
        )
        assertFalse(gate.claim(retry.token, currentGeneration = 8, canRun = true))
        assertTrue(gate.claim(retry.token, currentGeneration = 7, canRun = true))
        assertFalse(gate.claim(retry.token, currentGeneration = 7, canRun = true))
    }

    @Test
    fun `failure budget exhausts and current success resets it`() {
        val gate = ColdStartRetryGate(maxAttempts = 2)
        val first = gate.failed(9, 9, canRun = true) as ColdStartRetryGate.Failure.Retry
        assertTrue(gate.claim(first.token, 9, canRun = true))
        val second = gate.failed(9, 9, canRun = true) as ColdStartRetryGate.Failure.Retry
        assertTrue(gate.claim(second.token, 9, canRun = true))
        assertEquals(
            ColdStartRetryGate.Failure.Exhausted,
            gate.failed(9, 9, canRun = true),
        )

        gate.success(9)
        assertTrue(gate.failed(9, 9, canRun = true) is ColdStartRetryGate.Failure.Retry)
    }

    @Test
    fun `cancel invalidates a scheduled owner and restores budget`() {
        val gate = ColdStartRetryGate(maxAttempts = 1)
        val scheduled = gate.failed(11, 11, canRun = true) as ColdStartRetryGate.Failure.Retry

        gate.cancel()

        assertFalse(gate.claim(scheduled.token, currentGeneration = 11, canRun = true))
        assertTrue(gate.failed(11, 11, canRun = true) is ColdStartRetryGate.Failure.Retry)
    }
}
