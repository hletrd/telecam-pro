package me.hletrd.telecampro.ui.review

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewSourceSpoolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `large source is file backed and releases exact process admission on close`() {
        val size = 2 * 1024 * 1024 + 17
        val budget = ReviewSourceByteBudget(size.toLong())
        val source = requireNotNull(
            spoolReviewSource(
                cacheDirectory = temporaryFolder.newFolder("large"),
                input = PatternInputStream(size),
                maxBytes = size.toLong(),
                budget = budget,
            ),
        )

        assertEquals(size.toLong(), source.sizeBytes)
        assertEquals(size.toLong(), budget.usedBytes())
        assertTrue(source.exists())
        var readBytes = 0L
        source.openInputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                readBytes += read
            }
        }
        assertEquals(size.toLong(), readBytes)

        source.close()
        source.close()
        assertFalse(source.exists())
        assertEquals(0L, budget.usedBytes())
    }

    @Test
    fun `overlapping thumbnail and full spools share one exact byte budget`() {
        val directory = temporaryFolder.newFolder("overlap")
        val budget = ReviewSourceByteBudget(10L)
        val full = requireNotNull(
            spoolReviewSource(directory, ByteArrayInputStream(ByteArray(6)), 10L, budget),
        )
        assertEquals(6L, budget.usedBytes())

        val refusedThumbnail = spoolReviewSource(
            directory,
            ByteArrayInputStream(ByteArray(5)),
            10L,
            budget,
        )
        assertNull(refusedThumbnail)
        assertEquals("failed overlap must release its partial reservation", 6L, budget.usedBytes())
        assertEquals("failed overlap must delete its partial spool", 1, directory.listFiles().orEmpty().size)

        full.close()
        val thumbnail = requireNotNull(
            spoolReviewSource(directory, ByteArrayInputStream(ByteArray(5)), 10L, budget),
        )
        assertEquals(5L, budget.usedBytes())
        thumbnail.close()
        assertEquals(0L, budget.usedBytes())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `spool stays immutable when provider backing bytes change`() {
        val directory = temporaryFolder.newFolder("immutable")
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val expected = original.copyOf()
        val budget = ReviewSourceByteBudget(32L)
        val source = requireNotNull(
            spoolReviewSource(directory, ByteArrayInputStream(original), 32L, budget),
        )

        original.fill(99)
        val firstRead = source.openInputStream().use { it.readBytes() }
        val secondRead = source.openInputStream().use { it.readBytes() }
        assertArrayEquals(expected, firstRead)
        assertArrayEquals(expected, secondRead)

        source.close()
        assertEquals(0L, budget.usedBytes())
    }

    @Test
    fun `per source overflow deletes partial spool and releases budget`() {
        val directory = temporaryFolder.newFolder("overflow")
        val budget = ReviewSourceByteBudget(32L)

        assertNull(
            spoolReviewSource(
                directory,
                ByteArrayInputStream(ByteArray(9)),
                maxBytes = 8L,
                budget = budget,
            ),
        )
        assertEquals(0L, budget.usedBytes())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `zero length bulk read makes bounded progress through the single byte fallback`() {
        val directory = temporaryFolder.newFolder("zero-read")
        val budget = ReviewSourceByteBudget(4L)
        var bulkReads = 0
        val input = object : InputStream() {
            private var delivered = false

            override fun read(): Int = if (delivered) -1 else 0x5a.also { delivered = true }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = when {
                bulkReads++ == 0 -> 0
                delivered -> -1
                else -> error("single-byte fallback must own progress")
            }
        }

        val source = requireNotNull(spoolReviewSource(directory, input, 4L, budget))
        assertArrayEquals(byteArrayOf(0x5a), source.openInputStream().use { it.readBytes() })
        assertEquals(1L, budget.usedBytes())
        source.close()
        assertEquals(0L, budget.usedBytes())
    }

    @Test
    fun `retired request keeps exact admission until its blocked worker cleans up`() = runBlocking {
        val directory = temporaryFolder.newFolder("retired")
        val budget = ReviewSourceByteBudget(12L)
        val oldSpooled = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val lane = ProgressiveLatestWorkLane<Pair<String, ByteArray>, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                terminalTimeoutMs = 2_000L,
                work = work@ { (name, bytes) ->
                    val source = spoolReviewSource(
                        directory,
                        ByteArrayInputStream(bytes),
                        maxBytes = 12L,
                        budget = budget,
                    ) ?: return@work null
                    source.use {
                        if (name == "old") {
                            oldSpooled.countDown()
                            releaseOld.await(2, TimeUnit.SECONDS)
                        }
                        name
                    }
                },
                dispose = {},
            )
            val old = async(Dispatchers.Default) { lane.submit(Any(), "old" to ByteArray(6)) }
            assertTrue(oldSpooled.await(2, TimeUnit.SECONDS))
            assertEquals(6L, budget.usedBytes())

            val replacement = async(Dispatchers.Default) {
                lane.submit(Any(), "new" to ByteArray(6))
            }
            val replacementSubmission = replacement.await()
            assertTrue(replacementSubmission is ProgressiveLatestWorkLane.Submission.Completed)
            replacementSubmission as ProgressiveLatestWorkLane.Submission.Completed
            assertTrue(lane.claim(replacementSubmission.completion) { })
            assertEquals("blocked retired source remains accounted", 6L, budget.usedBytes())
            assertSame(ProgressiveLatestWorkLane.Submission.Retired, old.await())

            releaseOld.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (budget.usedBytes() != 0L && System.nanoTime() < deadline) Thread.yield()
            assertEquals(0L, budget.usedBytes())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            releaseOld.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private class PatternInputStream(private val totalBytes: Int) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position >= totalBytes) {
            -1
        } else {
            (position++ and 0xff)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= totalBytes) return -1
            val count = min(length, totalBytes - position)
            for (index in 0 until count) buffer[offset + index] = ((position + index) and 0xff).toByte()
            position += count
            return count
        }
    }
}
