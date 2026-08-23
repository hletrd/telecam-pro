package me.hletrd.telecampro.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCallbackSinkTest {
    @Test
    fun `four and ten argument callbacks preserve every argument`() {
        val sink = EngineCallbackSink()
        var four: List<Int>? = null
        var ten: List<Int>? = null
        sink.install<(Int, Int, Int, Int) -> Unit>(EngineCallbackKey.ANALYSIS) { a, b, c, d ->
            four = listOf(a, b, c, d)
        }
        sink.install<(Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit>(
            EngineCallbackKey.OPTICS_ROLLBACK,
        ) { a, b, c, d, e, f, g, h, i, j ->
            ten = listOf(a, b, c, d, e, f, g, h, i, j)
        }

        sink.function4<Int, Int, Int, Int>(EngineCallbackKey.ANALYSIS)!!(1, 2, 3, 4)
        sink.function10<Int, Int, Int, Int, Int, Int, Int, Int, Int, Int>(
            EngineCallbackKey.OPTICS_ROLLBACK,
        )!!(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        assertEquals(listOf(1, 2, 3, 4), four)
        assertEquals((1..10).toList(), ten)
    }

    @Test
    fun `function acquired before close rechecks terminal state`() {
        val sink = EngineCallbackSink()
        var publications = 0
        sink.install<(Int) -> Unit>(EngineCallbackKey.MEDIA_SAVED) { publications += it }
        val acquired = sink.function1<Int>(EngineCallbackKey.MEDIA_SAVED)!!

        sink.closeAndDrain()
        acquired(7)

        assertEquals(0, publications)
        assertNull(sink.function1<Int>(EngineCallbackKey.MEDIA_SAVED))
    }

    @Test
    fun `close drains an admitted callback before returning`() {
        val sink = EngineCallbackSink()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeReturned = AtomicBoolean(false)
        sink.install<() -> Unit>(EngineCallbackKey.RECORDING_STARTED) {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }
        val acquired = sink.function0(EngineCallbackKey.RECORDING_STARTED)!!
        val caller = thread(start = true) { acquired() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val closer = thread(start = true) {
            sink.closeAndDrain()
            closeReturned.set(true)
        }
        Thread.sleep(25)
        assertFalse(closeReturned.get())

        release.countDown()
        caller.join(5_000)
        closer.join(5_000)
        assertFalse(caller.isAlive)
        assertFalse(closer.isAlive)
        assertTrue(closeReturned.get())
    }
}
