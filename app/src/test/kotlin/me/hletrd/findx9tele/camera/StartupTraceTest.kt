package me.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
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
        StartupTrace.begin()
        StartupTrace.mark("first")
        // A second begin() during a live measurement must NOT clear the buffer: the cold start it
        // would "restart" is the same one already being measured (engine restart / double resume).
        StartupTrace.begin()
        StartupTrace.mark("second")
        assertEquals(listOf("first", "second"), StartupTrace.marksForTest().map { it.first })
    }

    @Test
    fun `marks are ignored while unarmed and resume after a new begin`() {
        StartupTrace.disarm()
        StartupTrace.mark("stray")
        assertTrue(StartupTrace.marksForTest().isEmpty())

        StartupTrace.begin()
        StartupTrace.mark("armed")
        assertEquals(listOf("armed"), StartupTrace.marksForTest().map { it.first })
    }

    @Test
    fun `finish records its own label then disarms`() {
        StartupTrace.disarm()
        StartupTrace.begin()
        StartupTrace.mark("openCamera")
        StartupTrace.finish("firstCameraResult")
        assertEquals(
            listOf("openCamera", "firstCameraResult"),
            StartupTrace.marksForTest().map { it.first },
        )

        // Disarmed: a late mark (a second capture result, a fast-path resubmit) must not extend a
        // completed measurement — the next cold start owns the next line.
        StartupTrace.mark("late")
        assertEquals(2, StartupTrace.marksForTest().size)

        // Exactly ONE cold-start line per measurement, and it carries every mark in order.
        assertEquals(1, emitted.size)
        assertTrue(emitted.single().startsWith("cold start (ms since resume): openCamera "))
        assertTrue(emitted.single().contains("firstCameraResult "))

        // ...and a second finish is inert rather than emitting a duplicate cold-start line.
        StartupTrace.finish("firstCameraResult")
        assertEquals(2, StartupTrace.marksForTest().size)
        assertEquals(1, emitted.size)
    }

    @Test
    fun `elapsed values are monotonic and start at or after zero`() {
        StartupTrace.disarm()
        StartupTrace.begin()
        StartupTrace.mark("a")
        StartupTrace.mark("b")
        val elapsed = StartupTrace.marksForTest().map { it.second }
        assertTrue(elapsed.first() >= 0L)
        assertTrue(elapsed[1] >= elapsed[0])
    }

    @Test
    fun `disarm clears a partial measurement`() {
        StartupTrace.disarm()
        StartupTrace.begin()
        StartupTrace.mark("partial")
        StartupTrace.disarm()
        assertTrue(StartupTrace.marksForTest().isEmpty())
        // disarm() also stops the clock, so marks after it are dropped until the next begin().
        StartupTrace.mark("after-disarm")
        assertTrue(StartupTrace.marksForTest().isEmpty())
    }

    @Test
    fun `a disarmed measurement cannot be finished by a later unrelated mark`() {
        // The resume-arms-then-early-returns shape: begin() with no open behind it, disarmed, then
        // an ordinary preview rebuild calls finish(). It must emit nothing at all.
        StartupTrace.disarm()
        StartupTrace.begin()
        StartupTrace.disarm()
        val before = emitted.size
        StartupTrace.finish("firstCameraResult")
        assertEquals(before, emitted.size)
        assertTrue(StartupTrace.marksForTest().isEmpty())
    }
}
