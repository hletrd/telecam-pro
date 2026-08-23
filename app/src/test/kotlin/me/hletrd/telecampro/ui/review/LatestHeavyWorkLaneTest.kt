package me.hletrd.telecampro.ui.review

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestHeavyWorkLaneTest {
    @Test
    fun `blocked A coalesces waiting B into newest C and disposes stale A`() {
        val executor = Executors.newFixedThreadPool(3)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val aStarted = CountDownLatch(1)
            val releaseA = CountDownLatch(1)
            val executed = Collections.synchronizedList(mutableListOf<String>())
            val disposed = Collections.synchronizedList(mutableListOf<String>())
            val lane = LatestHeavyWorkLane<String, String>(
                dispatcher = dispatcher,
                work = { input ->
                    executed += input
                    if (input == "A") {
                        aStarted.countDown()
                        check(releaseA.await(2, TimeUnit.SECONDS))
                    }
                    "result-$input"
                },
                dispose = disposed::add,
            )

            runBlocking {
                val a = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "A") }
                assertTrue(aStarted.await(2, TimeUnit.SECONDS))
                val b = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "B") }
                val c = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "C") }
                releaseA.countDown()

                assertNull(a.await())
                assertNull(b.await())
                val newest = checkNotNull(c.await())
                var published: String? = null
                assertTrue(lane.claim(newest) { published = it })
                assertEquals("result-C", published)
            }

            assertEquals(listOf("A", "C"), executed)
            assertEquals(listOf("result-A"), disposed)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `invalidating old owner never cancels replacement owner`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val aStarted = CountDownLatch(1)
            val releaseA = CountDownLatch(1)
            val disposed = Collections.synchronizedList(mutableListOf<String>())
            val lane = LatestHeavyWorkLane<String, String>(
                dispatcher = dispatcher,
                work = { input ->
                    if (input == "A") {
                        aStarted.countDown()
                        check(releaseA.await(2, TimeUnit.SECONDS))
                    }
                    input
                },
                dispose = disposed::add,
            )
            val ownerA = Any()
            val ownerB = Any()

            runBlocking {
                val a = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(ownerA, "A") }
                assertTrue(aStarted.await(2, TimeUnit.SECONDS))
                lane.invalidate(ownerA)
                val b = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(ownerB, "B") }
                lane.invalidate(ownerA)
                releaseA.countDown()

                assertNull(a.await())
                val replacement = checkNotNull(b.await())
                assertTrue(lane.claim(replacement) {})
            }

            assertEquals(listOf("A"), disposed)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `new request between worker return and publication disposes stale completion`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val disposed = Collections.synchronizedList(mutableListOf<String>())
            val lane = LatestHeavyWorkLane<String, String>(
                dispatcher = dispatcher,
                work = { "result-$it" },
                dispose = disposed::add,
            )

            runBlocking {
                val old = checkNotNull(lane.submit(Any(), "old"))
                val newestDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.submit(Any(), "new")
                }
                val newest = checkNotNull(newestDeferred.await())
                assertFalse(lane.claim(old) {})
                assertTrue(lane.claim(newest) {})
            }

            assertEquals(listOf("result-old"), disposed)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
