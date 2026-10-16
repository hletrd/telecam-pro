package me.hletrd.telecampro.ui.review

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
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
    @Test
    fun `unused cleanup reservation cancels lease exactly and retry stays absent`() {
        val budget = ReviewSourceByteBudget(8L)
        val cleanup = ReviewSpoolCleanupOwner(capacity = 1)
        val reservation = requireNotNull(cleanup.reserve())
        val lease = budget.openLease()
        assertTrue(lease.tryGrow(4))

        assertEquals(ReviewSpoolDeleteDisposition.ABSENT, reservation.cancel(lease))
        assertEquals(ReviewSpoolDeleteDisposition.ABSENT, reservation.cancel(lease))
        assertEquals(ReviewSpoolDeleteDisposition.ABSENT, reservation.retry())
        assertEquals(0L, budget.usedBytes())
        assertEquals(0, cleanup.admittedCount())
        assertEquals(0, cleanup.unresolvedCount())
    }

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
    fun `ordinary unverified spool admits with production byte and process budget defaults`() {
        val directory = temporaryFolder.newFolder("default-admission")

        val source = requireNotNull(
            spoolReviewSource(directory, ByteArrayInputStream(byteArrayOf(1, 2, 3))),
        )

        assertEquals(3L, source.sizeBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), source.openInputStream().use { it.readBytes() })
        source.close()
        assertFalse(source.exists())
    }

    @Test
    fun `false full deletion retains exact bytes until retry proves absence`() {
        val directory = temporaryFolder.newFolder("false-delete")
        val budget = ReviewSourceByteBudget(8L)
        var deletionsSucceed = false
        val cleanup = ReviewSpoolCleanupOwner(capacity = 1) { file ->
            if (deletionsSucceed) file.delete() else false
        }
        val source = requireNotNull(
            spoolReviewSource(
                directory,
                ByteArrayInputStream(ByteArray(6)),
                maxBytes = 8L,
                budget = budget,
                cleanupOwner = cleanup,
            ),
        )

        assertEquals(ReviewSpoolDeleteDisposition.RETAINED, source.closeWithResult())
        assertTrue(source.exists())
        assertEquals(6L, budget.usedBytes())
        assertEquals(1, cleanup.admittedCount())
        assertEquals(1, cleanup.unresolvedCount())

        deletionsSucceed = true
        assertEquals(ReviewSpoolRetryReport(1, 1, 0), cleanup.retryUnresolved())
        assertFalse(source.exists())
        assertEquals(0L, budget.usedBytes())
        assertEquals(0, cleanup.admittedCount())
    }

    @Test
    fun `throwing full deletion has the same retained typed truth`() {
        val directory = temporaryFolder.newFolder("throw-delete")
        val budget = ReviewSourceByteBudget(8L)
        var throwDelete = true
        val cleanup = ReviewSpoolCleanupOwner(capacity = 1) { file ->
            if (throwDelete) error("injected delete failure") else file.delete()
        }
        val source = requireNotNull(
            spoolReviewSource(
                directory,
                ByteArrayInputStream(ByteArray(5)),
                maxBytes = 8L,
                budget = budget,
                cleanupOwner = cleanup,
            ),
        )

        assertEquals(ReviewSpoolDeleteDisposition.RETAINED, source.closeWithResult())
        assertEquals(ReviewSpoolDeleteDisposition.RETAINED, source.closeWithResult())
        assertEquals(5L, budget.usedBytes())
        assertEquals(1, cleanup.unresolvedCount())

        throwDelete = false
        assertEquals(ReviewSpoolRetryReport(1, 1, 0), cleanup.retryUnresolved())
        assertEquals(ReviewSpoolDeleteDisposition.ABSENT, source.closeWithResult())
        assertEquals(0L, budget.usedBytes())
    }

    @Test
    fun `partial spool delete failure retains written bytes and reports retry truth`() {
        val directory = temporaryFolder.newFolder("partial-delete")
        val budget = ReviewSourceByteBudget(16L)
        var deletionsSucceed = false
        val cleanup = ReviewSpoolCleanupOwner(capacity = 1) { file ->
            if (deletionsSucceed) file.delete() else false
        }
        val input = ChunkedInputStream(listOf(ByteArray(6), ByteArray(3)))

        val result = spoolReviewSourceResult(
            directory,
            input,
            maxBytes = 8L,
            budget = budget,
            filePrefix = "review-source-",
            cleanupOwner = cleanup,
        )

        assertEquals(
            ReviewSpoolBuildResult.Failed(ReviewSpoolDeleteDisposition.RETAINED),
            result,
        )
        assertEquals("only bytes written before overflow stay accounted", 6L, budget.usedBytes())
        assertEquals(1, cleanup.unresolvedCount())
        assertEquals(1, directory.listFiles().orEmpty().size)

        deletionsSucceed = true
        assertEquals(ReviewSpoolRetryReport(1, 1, 0), cleanup.retryUnresolved())
        assertEquals(0L, budget.usedBytes())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `repeated deletion failures close new admission at finite cleanup capacity`() {
        val directory = temporaryFolder.newFolder("cleanup-capacity")
        val budget = ReviewSourceByteBudget(16L)
        var deletionsSucceed = false
        val cleanup = ReviewSpoolCleanupOwner(capacity = 2) { file ->
            if (deletionsSucceed) file.delete() else false
        }
        repeat(2) {
            val source = requireNotNull(
                spoolReviewSource(
                    directory,
                    ByteArrayInputStream(ByteArray(4)),
                    maxBytes = 8L,
                    budget = budget,
                    cleanupOwner = cleanup,
                ),
            )
            assertEquals(ReviewSpoolDeleteDisposition.RETAINED, source.closeWithResult())
        }
        var reads = 0
        val refusedInput = object : InputStream() {
            override fun read(): Int = (-1).also { reads++ }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                (-1).also { reads++ }
        }

        assertEquals(
            ReviewSpoolBuildResult.Failed(ReviewSpoolDeleteDisposition.CAPACITY_EXHAUSTED),
            spoolReviewSourceResult(
                directory,
                refusedInput,
                maxBytes = 8L,
                budget = budget,
                filePrefix = "review-source-",
                cleanupOwner = cleanup,
            ),
        )
        assertEquals("capacity refusal must precede provider consumption", 0, reads)
        assertEquals(8L, budget.usedBytes())
        assertEquals(2, cleanup.admittedCount())
        assertEquals(2, cleanup.unresolvedCount())

        deletionsSucceed = true
        assertEquals(ReviewSpoolRetryReport(2, 2, 0), cleanup.retryUnresolved())
        assertEquals(0L, budget.usedBytes())
        assertEquals(0, cleanup.admittedCount())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
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

    @Test
    fun `new process generation reclaims a crash orphan before admitting another spool`() {
        val cacheRoot = temporaryFolder.newFolder("restart-cache")
        val firstOwner = ReviewSpoolDirectoryOwner("aaaaaaaaaaaaaaaa")
        val firstLocation = requireNotNull(firstOwner.prepare(cacheRoot))
        val budget = ReviewSourceByteBudget(32L)
        val orphan = requireNotNull(
            spoolReviewSource(
                cacheDirectory = firstLocation.directory,
                input = ByteArrayInputStream(ByteArray(8)),
                maxBytes = 16L,
                budget = budget,
                filePrefix = firstLocation.filePrefix,
            ),
        )
        assertTrue(orphan.exists())

        val replacementOwner = ReviewSpoolDirectoryOwner("bbbbbbbbbbbbbbbb")
        val replacementLocation = requireNotNull(replacementOwner.prepare(cacheRoot))

        assertEquals(firstLocation.directory, replacementLocation.directory)
        assertFalse("prior-process orphan survived startup reclamation", orphan.exists())
        assertEquals(ReviewSpoolCleanupReport(examined = 1, deleted = 1), replacementOwner.cleanupReport())
        orphan.close() // releases the simulated dead process's test-only byte lease
        assertEquals(0L, budget.usedBytes())
    }

    @Test
    fun `directory create race adopts the no-follow directory that won`() {
        val cacheRoot = temporaryFolder.newFolder("create-race-cache")
        var racedPath: java.nio.file.Path? = null
        val owner = ReviewSpoolDirectoryOwner(
            processToken = "bbbbbbbbbbbbbbbb",
            beforeCreateDirectory = { path ->
                racedPath = path
                Files.createDirectory(path)
            },
        )

        val location = requireNotNull(owner.prepare(cacheRoot))

        assertEquals(racedPath, location.directory.toPath())
        assertTrue(location.directory.isDirectory)
        assertFalse(Files.isSymbolicLink(location.directory.toPath()))
        assertEquals(ReviewSpoolCleanupReport(0, 0), owner.cleanupReport())
    }

    @Test
    fun `cleanup is prefix type and generation safe without touching unrelated cache`() {
        val cacheRoot = temporaryFolder.newFolder("typed-cleanup-cache")
        val unrelatedRootFile = File(cacheRoot, "keep-root.txt").apply { writeText("root") }
        val seedLocation = requireNotNull(
            ReviewSpoolDirectoryOwner("aaaaaaaaaaaaaaaa").prepare(cacheRoot),
        )
        val stale = File(seedLocation.directory, "review-source-cccccccccccccccc-1.bin").apply {
            writeBytes(byteArrayOf(1))
        }
        val unrelated = File(seedLocation.directory, "keep.bin").apply { writeText("keep") }
        val lookalikeDirectory = File(
            seedLocation.directory,
            "review-source-dddddddddddddddd-2.bin",
        ).apply { mkdir() }
        val currentGeneration = File(
            seedLocation.directory,
            "review-source-bbbbbbbbbbbbbbbb-3.bin",
        ).apply { writeBytes(byteArrayOf(3)) }
        val outsideTarget = temporaryFolder.newFile("outside-target.bin").apply { writeText("outside") }
        val symlink = File(seedLocation.directory, "review-source-eeeeeeeeeeeeeeee-4.bin")
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(symlink.toPath(), outsideTarget.toPath())
            true
        }.getOrDefault(false)

        val owner = ReviewSpoolDirectoryOwner("bbbbbbbbbbbbbbbb")
        requireNotNull(owner.prepare(cacheRoot))

        assertFalse(stale.exists())
        assertTrue(unrelatedRootFile.exists())
        assertTrue(unrelated.exists())
        assertTrue(lookalikeDirectory.isDirectory)
        assertTrue("current process generation was reclaimed", currentGeneration.exists())
        assertTrue(outsideTarget.exists())
        if (symlinkCreated) assertTrue("cleanup followed or removed a symlink", Files.isSymbolicLink(symlink.toPath()))
    }

    @Test
    fun `stale cleanup examines at most its configured bound`() {
        val cacheRoot = temporaryFolder.newFolder("bounded-cleanup-cache")
        val directory = requireNotNull(
            ReviewSpoolDirectoryOwner("aaaaaaaaaaaaaaaa").prepare(cacheRoot),
        ).directory
        repeat(5) { index ->
            File(directory, "review-source-${(index + 1).toString(16).repeat(16)}-$index.bin")
                .writeBytes(byteArrayOf(index.toByte()))
        }

        val owner = ReviewSpoolDirectoryOwner("ffffffffffffffff", scanLimit = 2)
        requireNotNull(owner.prepare(cacheRoot))

        assertEquals(ReviewSpoolCleanupReport(examined = 2, deleted = 2), owner.cleanupReport())
        assertEquals(3, directory.listFiles().orEmpty().count { it.isFile })
    }

    @Test
    fun `normal spool lives only in dedicated directory and closes exactly`() {
        val cacheRoot = temporaryFolder.newFolder("normal-dedicated-cache")
        val owner = ReviewSpoolDirectoryOwner("1234567890abcdef")
        val location = requireNotNull(owner.prepare(cacheRoot))
        val budget = ReviewSourceByteBudget(16L)
        val source = requireNotNull(
            spoolReviewSource(
                cacheDirectory = location.directory,
                input = ByteArrayInputStream(ByteArray(6)),
                maxBytes = 8L,
                budget = budget,
                filePrefix = location.filePrefix,
            ),
        )

        assertEquals(REVIEW_SPOOL_DIRECTORY_NAME, location.directory.name)
        assertEquals(cacheRoot, location.directory.parentFile)
        assertTrue(source.exists())
        source.close()
        assertFalse(source.exists())
        assertEquals(0L, budget.usedBytes())
        assertTrue(location.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `dedicated directory symlink is refused without touching its target`() {
        val cacheRoot = temporaryFolder.newFolder("symlink-cache")
        val outside = temporaryFolder.newFolder("symlink-outside")
        val outsideFile = File(outside, "review-source-aaaaaaaaaaaaaaaa-1.bin").apply {
            writeText("outside")
        }
        val link = File(cacheRoot, REVIEW_SPOOL_DIRECTORY_NAME)
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        }.getOrDefault(false)
        if (!created) return

        assertNull(ReviewSpoolDirectoryOwner("bbbbbbbbbbbbbbbb").prepare(cacheRoot))
        assertTrue(outsideFile.exists())
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

    private class ChunkedInputStream(chunks: List<ByteArray>) : InputStream() {
        private val chunks = ArrayDeque(chunks)
        private var current: ByteArray? = null
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (current == null || this.offset >= checkNotNull(current).size) {
                current = chunks.removeFirstOrNull() ?: return -1
                this.offset = 0
            }
            val source = checkNotNull(current)
            val count = min(length, source.size - this.offset)
            source.copyInto(buffer, offset, this.offset, this.offset + count)
            this.offset += count
            return count
        }
    }
}
