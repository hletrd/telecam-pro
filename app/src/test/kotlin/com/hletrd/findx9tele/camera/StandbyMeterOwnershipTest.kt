package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class StandbyMeterOwnershipTest {
    @Test
    fun `simultaneous meter admissions reserve exactly one owner and release`() {
        val ownership = StandbyMeterOwnership<Any>()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val factories = AtomicInteger(0)
        val results = Collections.synchronizedList(
            mutableListOf<StandbyMeterOwnership.Owner<Any>?>(),
        )
        val workers = List(2) { index ->
            thread(start = true, name = "standby-admission-$index") {
                ready.countDown()
                assertTrue(start.await(1, TimeUnit.SECONDS))
                results += ownership.reserve(enabled = true, canStart = true) {
                    factories.incrementAndGet()
                    Any()
                }
            }
        }

        assertTrue(ready.await(1, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(1_000) }

        assertTrue(workers.none { it.isAlive })
        assertEquals(1, results.count { it != null })
        assertEquals(1, factories.get())
    }

    @Test
    fun `recording claim disables meter and returns its exact release owner`() {
        val ownership = StandbyMeterOwnership<Any>()
        val release = Any()
        val meter = ownership.reserve(enabled = true, canStart = true) { release }!!

        val recording = ownership.beginRecording()

        assertTrue(recording.admitted)
        assertSame(release, recording.release)
        assertFalse(ownership.ownsAndWants(meter))
        assertNull(ownership.reserve(enabled = true, canStart = true) { Any() })
        assertTrue(ownership.complete(meter).completed)
        assertNull(ownership.reserve(enabled = true, canStart = true) { Any() })
    }

    @Test
    fun `reserved meter loses late thread start admission after recording claim`() {
        val ownership = StandbyMeterOwnership<String>()
        val reserved = ownership.reserve(enabled = true, canStart = true) { "release" }!!

        val recording = ownership.beginRecording()

        assertEquals("release", recording.release)
        assertFalse(ownership.ownsAndWants(reserved))
    }

    @Test
    fun `failed recording setup restores prior standby intent`() {
        val ownership = StandbyMeterOwnership<String>()
        val meter = ownership.reserve(enabled = true, canStart = true) { "release" }!!
        assertTrue(ownership.beginRecording().admitted)
        assertTrue(ownership.complete(meter).completed)

        ownership.abortRecording()
        assertEquals(
            "restarted",
            ownership.reserveCurrentWanted(canStart = true) { "restarted" }?.release,
        )
    }

    @Test
    fun `failed recording setup does not override a newer disable intent`() {
        val ownership = StandbyMeterOwnership<String>()
        ownership.reserve(enabled = true, canStart = true) { "release" }
        assertTrue(ownership.beginRecording().admitted)

        ownership.disable()

        ownership.abortRecording()
        assertNull(ownership.reserveCurrentWanted(canStart = true) { "must-not-start" })
    }

    @Test
    fun `pending meter intent starts only after recorder finalization`() {
        val ownership = StandbyMeterOwnership<String>()
        assertTrue(ownership.beginRecording().admitted)

        assertNull(ownership.reserve(enabled = true, canStart = true) { "blocked" })
        ownership.finishRecording()

        val restarted = ownership.reserveCurrentWanted(canStart = true) { "next" }
        assertEquals("next", restarted?.release)
    }

    @Test
    fun `newer disable rejects stale finalizer restart`() {
        val ownership = StandbyMeterOwnership<String>()
        assertTrue(ownership.beginRecording().admitted)
        assertNull(ownership.reserve(enabled = true, canStart = true) { "pending" })
        ownership.finishRecording()

        ownership.disable()

        assertNull(ownership.reserveCurrentWanted(canStart = true) { "must-not-start" })
    }

    @Test
    fun `newer disable rejects a pending owner completion retry`() {
        val ownership = StandbyMeterOwnership<String>()
        val first = ownership.reserve(enabled = true, canStart = true) { "first" }!!
        assertNull(ownership.reserve(enabled = true, canStart = true) { "pending" })
        val completion = ownership.complete(first)
        assertTrue(completion.retryPending)

        ownership.disable()

        assertNull(ownership.reserveCurrentWanted(canStart = true) { "must-not-start" })
    }

    @Test
    fun `completion and disable are identity safe across generations`() {
        val ownership = StandbyMeterOwnership<String>()
        val first = ownership.reserve(enabled = true, canStart = true) { "first" }!!
        val impostor = StandbyMeterOwnership.Owner(first.id + 1, "other")

        assertFalse(ownership.complete(impostor).completed)
        assertEquals("first", ownership.disable())
        assertTrue(ownership.complete(first).completed)

        val second = ownership.reserve(enabled = true, canStart = true) { "second" }!!
        assertTrue(second.id > first.id)
        assertFalse(ownership.complete(first).completed)
        assertTrue(ownership.ownsAndWants(second))
    }
}
