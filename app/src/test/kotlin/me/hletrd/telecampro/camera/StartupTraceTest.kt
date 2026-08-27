package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [StartupTrace] is DEBUG-gated and logs through android.util.Log, so a host test can only pin the
 * parts that survive without a framework: the arming state machine (which decides WHETHER a cold
 * start is being measured) and the buffer's join format. Both matter — a begin() that reset a
 * running measurement would report a fake-fast start, and a mark() that appended while unarmed
 * would attribute a warm reopen's timings to the next cold start.
 *
 * Under unit tests `BuildConfig.DEBUG` is true, so the real guards execute.
 */
class StartupTraceTest {

    // A deterministic clock: SystemClock is not mocked on the host, and fixed ticks also let the
    // monotonicity assertion below mean something instead of racing real time.
    private var now = 0L
    private val emitted = mutableListOf<String>()

    @Before
    fun installSeams() {
        now = 1_000L
        emitted.clear()
        StartupTrace.elapsedMs = { now += 5L; now }
        StartupTrace.emit = { emitted += it }
    }

    @After
    fun restoreSeams() {
        StartupTrace.disarm()
        StartupTrace.elapsedMs = { android.os.SystemClock.elapsedRealtime() }
        StartupTrace.emit = { android.util.Log.i("StartupTrace", it) }
    }

    @Test
    fun `begin is idempotent so a re-entrant start cannot restart the clock`() {
        StartupTrace.disarm()
        val owner = checkNotNull(StartupTrace.begin())
        StartupTrace.mark(owner, "first")
        // A second begin() during a live measurement must NOT clear the buffer: the cold start it
        // would "restart" is the same one already being measured (engine restart / double resume).
        assertEquals(owner, StartupTrace.begin())
        StartupTrace.mark(owner, "second")
        assertEquals(listOf("first", "second"), StartupTrace.marksForTest().map { it.first })
    }

    @Test
    fun `marks are ignored while unarmed and resume after a new begin`() {
        StartupTrace.disarm()
        StartupTrace.mark(null, "stray")
        assertTrue(StartupTrace.marksForTest().isEmpty())

        val owner = checkNotNull(StartupTrace.begin())
        StartupTrace.mark(owner, "armed")
        assertEquals(listOf("armed"), StartupTrace.marksForTest().map { it.first })
    }

    @Test
    fun `finish records its own label then disarms`() {
        StartupTrace.disarm()
        val owner = checkNotNull(StartupTrace.begin())
        assertEquals(owner, StartupTrace.currentOwner())
        StartupTrace.mark(owner, "openCamera")
        StartupTrace.finish(owner, "firstCameraResult")
        assertEquals(null, StartupTrace.currentOwner())
        assertEquals(
            listOf("openCamera", "firstCameraResult"),
            StartupTrace.marksForTest().map { it.first },
        )

        // Disarmed: a late mark (a second capture result, a fast-path resubmit) must not extend a
        // completed measurement — the next cold start owns the next line.
        StartupTrace.mark(owner, "late")
        assertEquals(2, StartupTrace.marksForTest().size)

        // Exactly ONE cold-start line per measurement, and it carries every mark in order.
        assertEquals(1, emitted.size)
        assertTrue(emitted.single().startsWith("cold start (ms since resume): openCamera "))
        assertTrue(emitted.single().contains("firstCameraResult "))

        // ...and a second finish is inert rather than emitting a duplicate cold-start line.
        StartupTrace.finish(owner, "firstCameraResult")
        assertEquals(2, StartupTrace.marksForTest().size)
        assertEquals(1, emitted.size)
    }

    @Test
    fun `elapsed values are monotonic and start at or after zero`() {
        StartupTrace.disarm()
        val owner = checkNotNull(StartupTrace.begin())
        StartupTrace.mark(owner, "a")
        StartupTrace.mark(owner, "b")
        val elapsed = StartupTrace.marksForTest().map { it.second }
        assertTrue(elapsed.first() >= 0L)
        assertTrue(elapsed[1] >= elapsed[0])
    }

    @Test
    fun `disarm clears a partial measurement`() {
        StartupTrace.disarm()
        val owner = checkNotNull(StartupTrace.begin())
        StartupTrace.mark(owner, "partial")
        StartupTrace.disarm(owner)
        assertTrue(StartupTrace.marksForTest().isEmpty())
        // disarm() also stops the clock, so marks after it are dropped until the next begin().
        StartupTrace.mark(owner, "after-disarm")
        assertTrue(StartupTrace.marksForTest().isEmpty())
    }

    @Test
    fun `a disarmed measurement cannot be finished by a later unrelated mark`() {
        // The resume-arms-then-early-returns shape: begin() with no open behind it, disarmed, then
        // an ordinary preview rebuild calls finish(). It must emit nothing at all.
        StartupTrace.disarm()
        val owner = checkNotNull(StartupTrace.begin())
        StartupTrace.disarm(owner)
        val before = emitted.size
        StartupTrace.finish(owner, "firstCameraResult")
        assertEquals(before, emitted.size)
        assertTrue(StartupTrace.marksForTest().isEmpty())
    }

    @Test
    fun `stale controller owner cannot mark finish or disarm a replacement trace`() {
        StartupTrace.disarm()
        val old = checkNotNull(StartupTrace.begin())
        StartupTrace.mark(old, "old-open")
        StartupTrace.disarm(old)
        val replacement = checkNotNull(StartupTrace.begin())

        StartupTrace.mark(old, "stale-result")
        StartupTrace.finish(old, "stale-finish")
        StartupTrace.disarm(old)
        StartupTrace.mark(replacement, "replacement-open")
        StartupTrace.finish(replacement, "firstCameraResult")

        assertEquals(
            listOf("replacement-open", "firstCameraResult"),
            StartupTrace.marksForTest().map { it.first },
        )
        assertEquals(1, emitted.size)
    }

    @Test
    fun `only latest preview request may finish the controller trace`() {
        assertTrue(startupTraceRequestMayFinish(requestGeneration = 8L, latestRequestGeneration = 8L))
        assertTrue(!startupTraceRequestMayFinish(requestGeneration = 7L, latestRequestGeneration = 8L))
    }

    @Test
    fun `resume A pause then resume B owns a fresh origin and rejects A result`() {
        val engine = EngineStartupTraceOwnership()
        val resumeA = checkNotNull(engine.begin())
        assertSame(resumeA, engine.begin())
        assertSame(resumeA, engine.claimController(resumeA))
        StartupTrace.mark(resumeA, "A-open")

        // CameraEngine.pause is the lifecycle terminal for the exact A attempt.
        assertTrue(engine.revoke())
        val resumeB = checkNotNull(engine.begin())
        assertNotSame(resumeA, resumeB)
        assertSame(resumeB, engine.claimController(resumeB))

        StartupTrace.finish(resumeA, "A-late-result")
        StartupTrace.mark(resumeB, "B-open")
        StartupTrace.finish(resumeB, "B-first-result")

        assertEquals(listOf("B-open", "B-first-result"), StartupTrace.marksForTest().map { it.first })
        assertEquals(1, emitted.size)
    }

    @Test
    fun `second controller revokes first owner so both stale result orders are inert`() {
        val engine = EngineStartupTraceOwnership()
        val attempt = checkNotNull(engine.begin())
        val firstController = engine.claimController(attempt)
        assertSame(attempt, firstController)

        // Installing a replacement controller is a terminal for the first controller's trace. The
        // replacement receives no inherited owner, so callbacks from either side cannot mix marks.
        assertNull(engine.claimController(attempt))
        assertTrue(!StartupTrace.owns(attempt))
        StartupTrace.finish(firstController, "old-result-after-replacement")
        StartupTrace.finish(attempt, "replacement-result-with-old-owner")
        assertTrue(emitted.isEmpty())

        val freshAttempt = checkNotNull(engine.begin())
        val freshController = engine.claimController(freshAttempt)
        StartupTrace.mark(freshController, "fresh-open")
        StartupTrace.finish(freshController, "fresh-result")
        assertEquals(listOf("fresh-open", "fresh-result"), StartupTrace.marksForTest().map { it.first })
        assertEquals(1, emitted.size)
    }
}
