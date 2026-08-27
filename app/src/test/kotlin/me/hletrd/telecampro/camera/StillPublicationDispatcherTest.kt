package me.hletrd.telecampro.camera

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StillPublicationDispatcherTest {
    @Test
    fun `every DNG uses the process tail and mixed output waits for its processed sibling`() {
        val rawOnly = PhotoFormats(heif = false, jpeg = false, dngRaw = true)
        val paired = PhotoFormats(heif = true, jpeg = false, dngRaw = true)

        assertEquals(DngPublicationTransfer.DIRECT, dngPublicationTransfer(rawOnly))
        assertEquals(DngPublicationTransfer.AFTER_PROCESSED, dngPublicationTransfer(paired))
        assertEquals(DngPublicationTransfer.NONE, dngPublicationTransfer(PhotoFormats()))
    }

    @Test
    fun `mixed transfer cannot publish until ordered processed work reaches terminal`() {
        val events = mutableListOf<String>()
        var queuedTransfer: Runnable? = null

        assertTrue(
            transferCompletedDngPublication(
                order = DngPublicationTransfer.AFTER_PROCESSED,
                enqueueAfterProcessed = { task -> queuedTransfer = task; true },
                publication = { events += "dng" },
                onTransferRejected = { error("accepted transfer cannot fall back") },
            ),
        )
        assertTrue(events.isEmpty())

        events += "processed"
        checkNotNull(queuedTransfer).run()
        assertEquals(listOf("processed", "dng"), events)
    }

    @Test
    fun `raw-only transfer is direct and rejected mixed transfer stays recovery-only`() {
        val events = mutableListOf<String>()
        assertTrue(
            transferCompletedDngPublication(
                order = DngPublicationTransfer.DIRECT,
                enqueueAfterProcessed = { error("RAW-only has no processed predecessor") },
                publication = { events += "direct" },
                onTransferRejected = { error("direct transfer cannot reject before dispatch") },
            ),
        )
        assertEquals(listOf("direct"), events)

        assertFalse(
            transferCompletedDngPublication(
                order = DngPublicationTransfer.AFTER_PROCESSED,
                enqueueAfterProcessed = { false },
                publication = { events += "must-not-publish" },
                onTransferRejected = { events += "recover" },
            ),
        )
        assertEquals(listOf("direct", "recover"), events)
    }

    @Test
    fun `saturation bounds active and queued DNG tails and never runs overflow inline`() {
        val releaseWorkers = CountDownLatch(1)
        val workersEntered = CountDownLatch(2)
        val createdThreads = AtomicInteger()
        val overflowPublished = AtomicBoolean()
        val overflowRecovered = AtomicInteger()
        val overflowTerminal = AtomicInteger()
        val dispatcher = StillPublicationDispatcher(
            workerCount = 2,
            backlogCapacity = 2,
            threadFactory = ThreadFactory { task ->
                Thread(task, "test-still-publication-${createdThreads.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )

        try {
            repeat(2) {
                assertEquals(
                    StillPublicationDispatch.ACCEPTED,
                    dispatcher.dispatch(Runnable { workersEntered.countDown(); releaseWorkers.await() }),
                )
            }
            assertTrue(workersEntered.await(5, TimeUnit.SECONDS))
            repeat(2) {
                assertEquals(StillPublicationDispatch.ACCEPTED, dispatcher.dispatch(Runnable {}))
            }

            assertEquals(
                StillPublicationDispatch.OVERFLOW,
                dispatcher.dispatchRecoverable(
                    publication = { overflowPublished.set(true) },
                    onRejected = { overflowRecovered.incrementAndGet() },
                    onTerminal = { overflowTerminal.incrementAndGet() },
                ),
            )

            assertEquals(2, dispatcher.activeTaskCount())
            assertEquals(2, dispatcher.queuedTaskCount())
            assertEquals(2, createdThreads.get())
            assertFalse(overflowPublished.get())
            assertEquals(1, overflowRecovered.get())
            assertEquals(1, overflowTerminal.get())
        } finally {
            releaseWorkers.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun `overflow keeps complete private bytes for an explicit recovery owner`() {
        val releaseWorker = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val privateRows = Collections.synchronizedSet(mutableSetOf("shot-overflow.dng"))
        val publishedRows = Collections.synchronizedSet(mutableSetOf<String>())
        val delayed = AtomicInteger()
        val terminal = AtomicInteger()
        val dispatcher = isolatedDispatcher(workerCount = 1, backlogCapacity = 1)

        try {
            assertEquals(
                StillPublicationDispatch.ACCEPTED,
                dispatcher.dispatch(Runnable { workerEntered.countDown(); releaseWorker.await() }),
            )
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            assertEquals(StillPublicationDispatch.ACCEPTED, dispatcher.dispatch(Runnable {}))

            assertEquals(
                StillPublicationDispatch.OVERFLOW,
                dispatcher.dispatchRecoverable(
                    publication = {
                        privateRows.remove("shot-overflow.dng")
                        publishedRows += "shot-overflow.dng"
                    },
                    onRejected = { delayed.incrementAndGet() },
                    onTerminal = { terminal.incrementAndGet() },
                ),
            )
            assertEquals(setOf("shot-overflow.dng"), privateRows.toSet())
            assertTrue(publishedRows.isEmpty())
            assertEquals(1, delayed.get())
            assertEquals(1, terminal.get())

            // Models the bounded next-launch recovery pass: the same complete bytes, never a
            // replacement write, are the artifact eventually published.
            privateRows.remove("shot-overflow.dng")
            publishedRows += "shot-overflow.dng"
            assertTrue(privateRows.isEmpty())
            assertEquals(setOf("shot-overflow.dng"), publishedRows.toSet())
        } finally {
            releaseWorker.countDown()
            dispatcher.shutdown()
        }
    }

    @Test
    fun `accepted publication and throwing callbacks terminally release exactly once`() {
        val finished = CountDownLatch(1)
        val terminal = AtomicInteger()
        val dispatcher = isolatedDispatcher(workerCount = 1, backlogCapacity = 1)

        try {
            assertEquals(
                StillPublicationDispatch.ACCEPTED,
                dispatcher.dispatchRecoverable(
                    publication = { throw IllegalStateException("provider failure") },
                    onRejected = { error("accepted work cannot recover at admission") },
                    onTerminal = { terminal.incrementAndGet(); finished.countDown() },
                ),
            )
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, terminal.get())
        } finally {
            dispatcher.shutdown()
        }

        val rejectedTerminal = AtomicInteger()
        val closed = isolatedDispatcher(workerCount = 1, backlogCapacity = 1)
        closed.shutdown()
        assertThrows(IllegalStateException::class.java) {
            closed.dispatchRecoverable(
                publication = { error("closed publication ran") },
                onRejected = { throw IllegalStateException("status observer failed") },
                onTerminal = { rejectedTerminal.incrementAndGet() },
            )
        }
        assertEquals(1, rejectedTerminal.get())
    }

    @Test
    fun `replacement Engines share one finite publication owner`() {
        val releaseWorkers = CountDownLatch(1)
        val workersEntered = CountDownLatch(STILL_PUBLICATION_WORKER_COUNT)
        val acceptedCount = STILL_PUBLICATION_WORKER_COUNT + STILL_PUBLICATION_BACKLOG_CAPACITY
        val terminals = AtomicInteger()
        val allTerminals = CountDownLatch(acceptedCount + 1)
        val oldEngine = StillPublicationDispatcher(
            STILL_PUBLICATION_WORKER_COUNT,
            STILL_PUBLICATION_BACKLOG_CAPACITY,
        )
        val replacementEngine = StillPublicationDispatcher(
            STILL_PUBLICATION_WORKER_COUNT,
            STILL_PUBLICATION_BACKLOG_CAPACITY,
        )

        try {
            repeat(STILL_PUBLICATION_WORKER_COUNT) {
                assertEquals(
                    StillPublicationDispatch.ACCEPTED,
                    oldEngine.dispatchRecoverable(
                        publication = { workersEntered.countDown(); releaseWorkers.await() },
                        onRejected = { error("worker admission unexpectedly rejected") },
                        onTerminal = { terminals.incrementAndGet(); allTerminals.countDown() },
                    ),
                )
            }
            assertTrue(workersEntered.await(5, TimeUnit.SECONDS))
            repeat(STILL_PUBLICATION_BACKLOG_CAPACITY) {
                assertEquals(
                    StillPublicationDispatch.ACCEPTED,
                    oldEngine.dispatchRecoverable(
                        publication = {},
                        onRejected = { error("backlog admission unexpectedly rejected") },
                        onTerminal = { terminals.incrementAndGet(); allTerminals.countDown() },
                    ),
                )
            }

            oldEngine.shutdown()
            assertEquals(
                StillPublicationDispatch.OVERFLOW,
                replacementEngine.dispatchRecoverable(
                    publication = { error("replacement overflow ran") },
                    onRejected = {},
                    onTerminal = { terminals.incrementAndGet(); allTerminals.countDown() },
                ),
            )
            assertEquals(STILL_PUBLICATION_WORKER_COUNT, replacementEngine.activeTaskCount())
            assertEquals(STILL_PUBLICATION_BACKLOG_CAPACITY, replacementEngine.queuedTaskCount())

            releaseWorkers.countDown()
            assertTrue(allTerminals.await(5, TimeUnit.SECONDS))
            assertEquals(acceptedCount + 1, terminals.get())
        } finally {
            releaseWorkers.countDown()
            oldEngine.shutdown()
            replacementEngine.shutdown()
        }
    }

    @Test
    fun `mixed and sequence tails share one ceiling across repeated Engine facades`() {
        val releaseWorkers = CountDownLatch(1)
        val workersEntered = CountDownLatch(2)
        val terminals = Collections.synchronizedList(mutableListOf<String>())
        val owner = StillPublicationCapacityOwner(
            workerCount = 2,
            backlogCapacity = 2,
            threadFactory = ThreadFactory { task ->
                Thread(task, "isolated-all-dng-tail").apply { isDaemon = true }
            },
        )
        val oldEngine = StillPublicationDispatcher(owner)
        val replacementEngine = StillPublicationDispatcher(owner)
        val secondReplacement = StillPublicationDispatcher(owner)

        fun submit(
            dispatcher: StillPublicationDispatcher,
            label: String,
            block: Boolean,
        ): StillPublicationDispatch = dispatcher.dispatchRecoverable(
            publication = {
                if (block) {
                    workersEntered.countDown()
                    releaseWorkers.await()
                }
            },
            onRejected = {},
            onTerminal = { terminals += label },
        )

        try {
            assertEquals(StillPublicationDispatch.ACCEPTED, submit(oldEngine, "mixed-single", true))
            assertEquals(StillPublicationDispatch.ACCEPTED, submit(oldEngine, "burst", true))
            assertTrue(workersEntered.await(5, TimeUnit.SECONDS))
            assertEquals(StillPublicationDispatch.ACCEPTED, submit(replacementEngine, "aeb", false))
            assertEquals(StillPublicationDispatch.ACCEPTED, submit(replacementEngine, "timelapse", false))

            oldEngine.shutdown()
            replacementEngine.shutdown()
            assertEquals(
                StillPublicationDispatch.OVERFLOW,
                submit(secondReplacement, "replacement-overflow", false),
            )
            assertEquals(2, secondReplacement.activeTaskCount())
            assertEquals(2, secondReplacement.queuedTaskCount())
            assertEquals(listOf("replacement-overflow"), terminals.toList())

            releaseWorkers.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (terminals.size < 5 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(
                setOf("mixed-single", "burst", "aeb", "timelapse", "replacement-overflow"),
                terminals.toSet(),
            )
            assertEquals(5, terminals.size)
        } finally {
            releaseWorkers.countDown()
            oldEngine.shutdown()
            replacementEngine.shutdown()
            secondReplacement.shutdown()
        }
    }

    @Test
    fun `process owner rejects capacity drift`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessStillPublicationOwner.capacity(
                STILL_PUBLICATION_WORKER_COUNT + 1,
                STILL_PUBLICATION_BACKLOG_CAPACITY,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessStillPublicationOwner.capacity(
                STILL_PUBLICATION_WORKER_COUNT,
                STILL_PUBLICATION_BACKLOG_CAPACITY + 1,
            )
        }
    }

    private fun isolatedDispatcher(
        workerCount: Int,
        backlogCapacity: Int,
    ) = StillPublicationDispatcher(
        workerCount = workerCount,
        backlogCapacity = backlogCapacity,
        threadFactory = ThreadFactory { task ->
            Thread(task, "isolated-still-publication").apply { isDaemon = true }
        },
    )
}
