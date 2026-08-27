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
    fun `cached redraws neither hide nor invent producer gaps`() {
        val timing = PreviewFrameTiming(frameGapThresholdMs = 200L)

        assertEquals(null, timing.recordDraw(1_000L, realCameraFrame = true))
        assertEquals(null, timing.recordDraw(1_050L, realCameraFrame = false))
        assertEquals(null, timing.recordDraw(1_100L, realCameraFrame = false))
        assertEquals(null, timing.recordDraw(1_150L, realCameraFrame = false))
        assertEquals(400L, timing.recordDraw(1_400L, realCameraFrame = true))
        assertEquals(null, timing.recordDraw(1_800L, realCameraFrame = false))
    }

    @Test
    fun `render idle and producer clocks reset together`() {
        val timing = PreviewFrameTiming(frameGapThresholdMs = 200L)

        assertEquals(Long.MAX_VALUE, timing.renderIdleMs(1_000L))
        timing.recordDraw(1_000L, realCameraFrame = true)
        assertEquals(16L, timing.renderIdleMs(1_016L))
        timing.reset()
        assertEquals(Long.MAX_VALUE, timing.renderIdleMs(2_000L))
        assertEquals(null, timing.recordDraw(2_000L, realCameraFrame = true))
    }

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
    fun continuouslyChangingTupleIsPacedBeforeTheProcessBudget() {
        val gate = ThreeADiagnosticLogGate()
        var rows = 0
        for (second in 0..600) {
            val changing = baseKey.copy(aeState = second and 1)
            if (gate.shouldEmit(second * 1_000L, changing, force = second == 0)) rows++
        }
        assertEquals(201, rows)
    }

    @Test
    fun stableFocusAndHeldHardwareKeyUseSlowBoundedHeartbeats() {
        val focus = DiagnosticChangeLogGate<String?>()
        var focusRows = 0
        for (second in 0..600) {
            if (focus.shouldEmit(second * 1_000L, null)) focusRows++
        }
        assertEquals(41, focusRows)

        val key = HardwareKeyDiagnosticLogGate()
        var keyRows = 0
        for (tick in 0..12_000) {
            if (key.shouldEmit(168, actionDown = true, repeatCount = tick, nowMs = tick * 50L)) {
                keyRows++
            }
        }
        if (key.shouldEmit(168, actionDown = false, repeatCount = 0, nowMs = 600_001L)) keyRows++
        assertEquals(42, keyRows)
    }

    @Test
    fun everyRecurringProducerSharesOneProcessReserve() {
        val budget = ProcessDiagnosticLogBudget(RECURRING_DIAGNOSTIC_ROW_BUDGET)
        val threeA = ThreeADiagnosticLogGate()
        val focus = DiagnosticChangeLogGate<String?>()
        val motion = DiagnosticChangeLogGate<Int>()
        val hardware = HardwareKeyDiagnosticLogGate()
        var emitted = 0

        fun emit(wanted: Boolean) {
            if (wanted && budget.tryAcquire()) emitted++
        }

        for (second in 0..600) {
            val nowMs = second * 1_000L
            emit(threeA.shouldEmit(nowMs, baseKey.copy(aeState = second and 1), force = second == 0))
            emit(focus.shouldEmit(nowMs, null))
            emit(motion.shouldEmit(nowMs, 1))
            emit(
                hardware.shouldEmit(
                    keyCode = 168,
                    actionDown = true,
                    repeatCount = second,
                    nowMs = nowMs,
                ),
            )
            if (second == 0 || second == 600) {
                // ZSL enable and terminal summary.
                emit(true)
            }
        }
        emit(hardware.shouldEmit(168, actionDown = false, repeatCount = 0, nowMs = 600_001L))

        assertEquals(RECURRING_DIAGNOSTIC_ROW_BUDGET, emitted)
        assertEquals(RECURRING_DIAGNOSTIC_ROW_BUDGET, budget.usedRows())
        assertEquals(
            120,
            COLOR_OS_PROCESS_LOG_ROW_LIMIT - budget.usedRows(),
        )
        assertTrue(!budget.tryAcquire())
    }

    @Test
    fun `repeatable capture rows stop at the shared budget and preserve fault reserve`() {
        val budget = ProcessDiagnosticLogBudget(RECURRING_DIAGNOSTIC_ROW_BUDGET)
        var emitted = 0
        // One ordinary Single can request registration, started, completed, images, settlement.
        repeat(60) {
            repeat(5) {
                if (recurringDiagnosticAllowed(debugEnabled = true, budget = budget)) emitted++
            }
        }

        assertEquals(RECURRING_DIAGNOSTIC_ROW_BUDGET, emitted)
        assertEquals(120, COLOR_OS_PROCESS_LOG_ROW_LIMIT - budget.usedRows())
        assertTrue(!recurringDiagnosticAllowed(debugEnabled = true, budget = budget))
    }

    @Test
    fun `release diagnostics never consume the debug process owner`() {
        val budget = ProcessDiagnosticLogBudget(1)

        assertTrue(!recurringDiagnosticAllowed(debugEnabled = false, budget = budget))
        assertEquals(0, budget.usedRows())
        assertTrue(!recurringDiagnosticAllowed(debugEnabled = false))
    }

    @Test
    fun `tap scan and owned reset share the recurring ceiling and preserve fault reserve`() {
        val budget = ProcessDiagnosticLogBudget(RECURRING_DIAGNOSTIC_ROW_BUDGET)
        var emitted = 0

        repeat(COLOR_OS_PROCESS_LOG_ROW_LIMIT) {
            if (tapFocusDiagnosticAllowed(debugEnabled = true, edgeOwned = true, budget = budget)) {
                emitted++ // Touch AF: scanning
            }
            if (tapFocusDiagnosticAllowed(debugEnabled = true, edgeOwned = true, budget = budget)) {
                emitted++ // TapFocus: cleared
            }
            assertTrue(!tapFocusDiagnosticAllowed(debugEnabled = true, edgeOwned = false, budget = budget))
        }

        assertEquals(RECURRING_DIAGNOSTIC_ROW_BUDGET, emitted)
        assertEquals(120, COLOR_OS_PROCESS_LOG_ROW_LIMIT - budget.usedRows())
        assertTrue(!tapFocusDiagnosticAllowed(debugEnabled = true, edgeOwned = true, budget = budget))
    }

    @Test
    fun `tap focus default process budget path still short circuits disabled and empty edges`() {
        // Omit the budget argument deliberately: this executes Kotlin's production default bridge
        // without consuming the process singleton in either short-circuited case.
        assertTrue(!tapFocusDiagnosticAllowed(debugEnabled = false, edgeOwned = true))
        assertTrue(!tapFocusDiagnosticAllowed(debugEnabled = true, edgeOwned = false))
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
