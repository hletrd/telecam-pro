package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTelemetryTest {
    private val baseKey = ThreeADiagnosticKey(
        opticsGeneration = 1L,
        requestGeneration = 1L,
        mode = CaptureMode.PHOTO,
        aeState = 2,
        afState = 4,
        afMode = 1,
        isoStops = diagnosticStopBucket(800L),
        exposureStops = diagnosticStopBucket(33_333_333L),
        focusCentidiopters = 10,
        ois = 1,
        videoStabilization = 0,
        flashMode = 0,
        flashState = 2,
        requestedVideoStabilization = 0,
        teleconverter = false,
        effectiveZoomCentipercent = 100,
    )

    @Test
    fun stableTenMinuteSoakUsesOnlyFifteenSecondHeartbeats() {
        val gate = ThreeADiagnosticLogGate()
        var rows = 0
        for (second in 0..600) {
            if (gate.shouldEmit(second * 1_000L, baseKey, force = second == 0)) rows++
        }
        assertEquals(41, rows)
    }

    @Test
    fun continuouslyChangingTupleStillLeavesQuotaForFaultEvidence() {
        val gate = ThreeADiagnosticLogGate()
        var rows = 0
        for (second in 0..600) {
            val changing = baseKey.copy(aeState = second and 1)
            if (gate.shouldEmit(second * 1_000L, changing, force = second == 0)) rows++
        }
        assertEquals(201, rows)
        // Two ZSL probe rows plus continuous 3A retain almost one third of ColorOS's 300-row budget.
        assertTrue(rows + 2 <= 210)
    }

    @Test
    fun harmlessSensorJitterStaysInsideOneSixthStopBucket() {
        assertEquals(diagnosticStopBucket(800L), diagnosticStopBucket(820L))
        assertEquals(diagnosticStopBucket(33_000_000L), diagnosticStopBucket(34_000_000L))
    }

    @Test
    fun defaultGateCallAndPartialCadenceWindowRemainExecutable() {
        val gate = ThreeADiagnosticLogGate()
        assertTrue(gate.shouldEmit(0L, baseKey))

        val accumulator = ZslSpikeAccumulator()
        accumulator.recordFrame(10L)
        val summary = accumulator.finish(510L)
        assertEquals(1L, summary.frames)
        assertEquals(500L, summary.durationMs)
        assertEquals(1, summary.windows)
        assertEquals(2, summary.minimumWindowFps)
    }

    @Test
    fun zslCadenceAccumulatesOneBoundedSummary() {
        val accumulator = ZslSpikeAccumulator()
        repeat(301) { frame -> accumulator.recordFrame(frame * 20L) }
        val summary = accumulator.finish(6_020L)

        assertEquals(301L, summary.frames)
        assertEquals(6_020L, summary.durationMs)
        assertEquals(50L, summary.averageFps)
        assertTrue(summary.windows in 6..7)
        assertTrue(checkNotNull(summary.minimumWindowFps) in 49..51)
        assertTrue(checkNotNull(summary.maximumWindowFps) in 49..51)
    }
}
