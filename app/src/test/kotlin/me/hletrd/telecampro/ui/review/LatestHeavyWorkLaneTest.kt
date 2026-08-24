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
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestHeavyWorkLaneTest {
    @Test
    fun `serial production defaults execute and publish on Dispatchers IO`() = runBlocking {
        val lane = LatestHeavyWorkLane<String, String>(
            work = { "result-$it" },
            dispose = {},
        )

        val completion = checkNotNull(lane.submit(Any(), "default"))
        var published: String? = null
        assertTrue(lane.claim(completion) { published = it })
        assertEquals("result-default", published)
    }

    @Test
    fun `progressive production defaults execute and publish on the process owner`() = runBlocking {
        val lane = ProgressiveLatestWorkLane<String, String>(
            work = { "result-$it" },
            dispose = {},
        )

        val completion = (lane.submit(Any(), "default") as
            ProgressiveLatestWorkLane.Submission.Completed).completion
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

            assertEquals(ProgressiveLatestWorkLane.Submission.Retired, retired.result.await())
            assertFalse(executed)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `timeout terminal observes an already retired request without reclassifying it`() = runBlocking {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val lane = ProgressiveLatestWorkLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                work = { it },
                dispose = {},
            )
            val request = lane.Request(Any(), "retired")

            request.retire()

            assertEquals(
                ProgressiveLatestWorkLane.Submission.Retired,
                request.terminalAfterTimeout(),
            )
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `timeout boundary atomically publishes or disposes an already produced value`() = runBlocking {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val disposed = mutableListOf<String>()
            val lane = ProgressiveLatestWorkLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                work = { "result-$it" },
                dispose = disposed::add,
            )

            // Models work completing after the caller's timer fired but before timeout
            // classification. The produced completion owns the atomic state and must win.
            val publishRequest = lane.Request(Any(), "publish")
            publishRequest.executeOwned()
            val publishSubmission = publishRequest.terminalAfterTimeout()
            val publishCompletion = (publishSubmission as
                ProgressiveLatestWorkLane.Submission.Completed).completion
            var published: String? = null
            assertTrue(publishCompletion.publish { published = it })
            assertEquals("result-publish", published)
            assertTrue(publishRequest.retire())
            assertTrue(disposed.isEmpty())

            // If invalidation wins after the same boundary, the exact value is disposed once and
            // can no longer publish.
            val disposeRequest = lane.Request(Any(), "dispose")
            disposeRequest.executeOwned()
            val disposeSubmission = disposeRequest.terminalAfterTimeout()
            val disposeCompletion = (disposeSubmission as
                ProgressiveLatestWorkLane.Submission.Completed).completion
            assertTrue(disposeRequest.retire())
            assertFalse(disposeCompletion.publish {})
            assertEquals(listOf("result-dispose"), disposed)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `started timeout is retryable and does not claim exhausted capacity`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val release = CountDownLatch(1)
        try {
            val started = CountDownLatch(1)
            val disposed = CountDownLatch(1)
            val lane = ProgressiveLatestWorkLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                terminalTimeoutMs = 100,
                work = { input ->
                    if (input == "slow") {
                        started.countDown()
                        release.await()
                    }
                    "result-$input"
                },
                dispose = { if (it == "result-slow") disposed.countDown() },
            )

            runBlocking {
                val slow = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.submit(Any(), "slow")
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertSame(ProgressiveLatestWorkLane.Submission.TimedOut, slow.await())
                assertFalse(lane.hasLatestRequest())

                release.countDown()
                assertTrue(disposed.await(2, TimeUnit.SECONDS))

                val retry = (lane.submit(Any(), "retry") as
                    ProgressiveLatestWorkLane.Submission.Completed).completion
                var published: String? = null
                assertTrue(lane.claim(retry) { published = it })
                assertEquals("result-retry", published)
                assertFalse(lane.hasLatestRequest())
            }
        } finally {
            release.countDown()
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
                assertEquals(
                    ProgressiveLatestWorkLane.Submission.Retired,
                    lane.submit(Any(), "null"),
                )
                assertEquals(
                    ProgressiveLatestWorkLane.Submission.Retired,
                    lane.submit(Any(), "throw"),
                )
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
                val old = (lane.submit(owner, "old") as
                    ProgressiveLatestWorkLane.Submission.Completed).completion
                lane.invalidate(Any())
                val newestDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.submit(Any(), "new")
                }
                val newest = (newestDeferred.await() as
                    ProgressiveLatestWorkLane.Submission.Completed).completion

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
                assertEquals(ProgressiveLatestWorkLane.Submission.Retired, c.await())
                assertFalse(executed.contains("C"))

                // Free only B's worker. A remains permanently blocked while newest D progresses.
                releaseB.countDown()
                assertEquals(ProgressiveLatestWorkLane.Submission.Retired, b.await())
                val newest = (d.await() as
                    ProgressiveLatestWorkLane.Submission.Completed).completion
                var published: String? = null
                assertTrue(lane.claim(newest) { published = it })
                assertEquals("result-D", published)
                assertEquals(ProgressiveLatestWorkLane.Submission.Retired, a.await())

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
    fun `fully blocked progressive lane terminally reports capacity without releasing workers`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val release = CountDownLatch(1)
        try {
            val started = CountDownLatch(2)
            val lane = ProgressiveLatestWorkLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                terminalTimeoutMs = 100,
                work = {
                    started.countDown()
                    release.await()
                    it
                },
                dispose = {},
            )

            runBlocking {
                val a = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "A") }
                val b = async(start = CoroutineStart.UNDISPATCHED) { lane.submit(Any(), "B") }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val newest = lane.submit(Any(), "C")

                assertEquals(ProgressiveLatestWorkLane.Submission.CapacityExhausted, newest)
                assertEquals(ProgressiveLatestWorkLane.Submission.Retired, a.await())
                assertEquals(ProgressiveLatestWorkLane.Submission.Retired, b.await())
            }
        } finally {
            release.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `shared four-thread pool gives a healthy lane a bounded exhaustion terminal`() {
        val executor = Executors.newFixedThreadPool(4)
        val dispatcher = executor.asCoroutineDispatcher()
        val release = CountDownLatch(1)
        try {
            val started = CountDownLatch(4)
            fun lane() = ProgressiveLatestWorkLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                terminalTimeoutMs = 100,
                work = {
                    started.countDown()
                    release.await()
                    it
                },
                dispose = {},
            )
            // One request per lane: a second request on the same lane is allowed to retire its
            // predecessor before dispatch (the production latest-wins contract), which made this
            // capacity test race its own setup under host load.
            val blockers = List(4) { lane() }
            val healthy = lane()

            runBlocking {
                val blocked = blockers.mapIndexed { index, blocker ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        blocker.submit(Any(), ('A'.code + index).toChar().toString())
                    }
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))

                assertEquals(
                    ProgressiveLatestWorkLane.Submission.CapacityExhausted,
                    healthy.submit(Any(), "healthy"),
                )
                blocked.forEach { it.cancel() }
            }
        } finally {
            release.countDown()
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
