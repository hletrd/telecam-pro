package me.hletrd.telecampro.ui.review

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestHeavyWorkLaneTest {
    @Test
    fun `progressive production defaults execute and publish on the process owner`() = runBlocking {
        val lane = ProgressiveLatestWorkLane<String, String>(
            work = { "result-$it" },
            dispose = {},
        )

        val completion = checkNotNull(lane.submit(Any(), "default"))
        var published: String? = null
        assertTrue(lane.claim(completion) { published = it })
        assertEquals("result-default", published)
    }

    @Test
    fun `retired progressive request never enters blocking work`() = runBlocking {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            var executed = false
            val lane = ProgressiveLatestWorkLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                work = {
                    executed = true
                    it
                },
                dispose = {},
            )
            val retired = lane.Request(Any(), "stale")

            retired.retire()
            retired.execute()

            assertNull(retired.result.await())
            assertFalse(executed)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `progressive lane enforces redundant blocking capacity`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ProgressiveLatestWorkLane<String, String>(
                    dispatcher = dispatcher,
                    workerCount = 1,
                    work = { it },
                    dispose = {},
                )
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `progressive null and thrown work retire without publication`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val lane = ProgressiveLatestWorkLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                work = { input -> if (input == "null") null else error("broken provider") },
                dispose = {},
            )

            runBlocking {
                assertNull(lane.submit(Any(), "null"))
                assertNull(lane.submit(Any(), "throw"))
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `progressive cancellation retires immediately and disposes a late native result`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val releaseWork = CountDownLatch(1)
        try {
            val started = CountDownLatch(1)
            val disposed = CountDownLatch(1)
            val lane = ProgressiveLatestWorkLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                work = {
                    started.countDown()
                    releaseWork.await()
                    "native-$it"
                },
                dispose = { disposed.countDown() },
            )

            runBlocking {
                val pending = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "A") }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                pending.cancel()
                pending.join()
                assertTrue(pending.isCancelled)
                releaseWork.countDown()
            }
            assertTrue(disposed.await(2, TimeUnit.SECONDS))
        } finally {
            releaseWork.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `progressive wrong-owner invalidation is inert and stale completion disposes once`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val disposed = Collections.synchronizedList(mutableListOf<String>())
            val lane = ProgressiveLatestWorkLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                work = { "result-$it" },
                dispose = disposed::add,
            )
            val owner = Any()

            runBlocking {
                val old = checkNotNull(lane.submit(owner, "old"))
                lane.invalidate(Any())
                val newestDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.submit(Any(), "new")
                }
                val newest = checkNotNull(newestDeferred.await())

                assertFalse(lane.claim(old) {})
                assertTrue(lane.claim(newest) {})
                assertFalse(lane.claim(newest) {})
                lane.invalidate(owner) // no current request: a no-op
            }

            assertEquals(listOf("result-old"), disposed)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `two blocked workers retain only newest pending progressive request`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val releaseA = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        try {
            val started = CountDownLatch(2)
            val staleDisposed = CountDownLatch(2)
            val executed = Collections.synchronizedList(mutableListOf<String>())
            val disposed = Collections.synchronizedList(mutableListOf<String>())
            val lane = ProgressiveLatestWorkLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                work = { input ->
                    executed += input
                    when (input) {
                        "A" -> {
                            started.countDown()
                            releaseA.await()
                        }
                        "B" -> {
                            started.countDown()
                            releaseB.await()
                        }
                    }
                    "result-$input"
                },
                dispose = { result ->
                    disposed += result
                    if (result == "result-A" || result == "result-B") staleDisposed.countDown()
                },
            )

            runBlocking {
                val a = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "A") }
                // Let A enter work before B retires its publication identity.
                while (executed.isEmpty()) Thread.yield()
                val b = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "B") }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val c = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "C") }
                val d = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "D") }

                // C occupied the one conflated pending slot and was retired by D without running.
                assertNull(c.await())
                assertFalse(executed.contains("C"))

                // Free only B's worker. A remains permanently blocked while newest D progresses.
                releaseB.countDown()
                assertNull(b.await())
                val newest = checkNotNull(d.await())
                var published: String? = null
                assertTrue(lane.claim(newest) { published = it })
                assertEquals("result-D", published)
                assertNull(a.await())

                releaseA.countDown()
            }

            assertTrue(staleDisposed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("A", "B", "D"), executed)
            assertTrue(disposed.containsAll(listOf("result-A", "result-B")))
        } finally {
            releaseA.countDown()
            releaseB.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancellation after worker result disposes the admitted completion`() = runBlocking {
        val workerTasks = ArrayDeque<Runnable>()
        val workerDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                workerTasks.addLast(block)
            }
        }
        val disposed = mutableListOf<String>()
        val lane = LatestHeavyWorkLane<String, String>(
            dispatcher = workerDispatcher,
            work = { "result-$it" },
            dispose = disposed::add,
        )
        val pending = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "A") }

        // Work and Completion creation finish on the manual dispatcher. The caller continuation is
        // still queued on runBlocking's event loop, creating the exact produced-result cancellation gap.
        workerTasks.removeFirst().run()
        pending.cancel()
        pending.join()

        assertTrue(pending.isCancelled)
        assertEquals(listOf("result-A"), disposed)
    }

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
