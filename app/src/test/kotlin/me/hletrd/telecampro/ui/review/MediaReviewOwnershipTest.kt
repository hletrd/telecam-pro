package me.hletrd.telecampro.ui.review

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaReviewOwnershipTest {

    @Test
    fun `unpublished descriptor disposes its exact still bitmap`() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val descriptor = ReviewDescriptor(
            state = ReviewMediaState.Ready.Still(ReviewBitmap(bitmap)),
            metadata = null,
        )

        descriptor.dispose()

        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `decoded still load owns ready pixels and null fails closed`() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val ready = reviewBitmapLoad(bitmap)

        assertTrue(ready is ReviewBitmapLoad.Ready)
        (ready as ReviewBitmapLoad.Ready).bitmap.dispose()
        assertTrue(bitmap.isRecycled)
        assertEquals(ReviewBitmapLoad.Failed, reviewBitmapLoad(null))
    }
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `process review context canonicalizes an activity to its application owner`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()

            assertSame(activity.applicationContext, processReviewContext(activity))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `permanently blocked video provider leaves a worker for replacement publication`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val setupStarted = CountDownLatch(1)
            val releaseSetup = CountDownLatch(1)
            val setupThread = AtomicReference<Thread>()
            val released = Collections.synchronizedList(mutableListOf<String>())
            val published = Collections.synchronizedList(mutableListOf<String>())
            val oldReleased = CountDownLatch(1)
            val lane = LatestReviewSetupLane<String, String>(
                dispatcher = dispatcher,
                work = { input ->
                    setupThread.set(Thread.currentThread())
                    if (input == "video-A") {
                        setupStarted.countDown()
                        releaseSetup.await()
                    }
                    "player-$input"
                },
                release = { result ->
                    released += result
                    if (result == "player-video-A") oldReleased.countDown()
                },
            )
            val callerThread = Thread.currentThread()
            val oldOwner = Any()
            val replacementOwner = Any()

            runBlocking {
                val old = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.run(oldOwner, "video-A", published::add)
                }
                assertTrue(setupStarted.await(2, TimeUnit.SECONDS))
                assertTrue(old.isActive)
                assertFalse(setupThread.get() === callerThread)

                // Models Back/onDispose: retirement is synchronous even though the provider-owned
                // worker is still blocked, so the main caller can continue immediately.
                lane.invalidate(oldOwner)
                var backHandled = false
                backHandled = true
                assertTrue(backHandled)

                val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.run(replacementOwner, "video-B", published::add)
                }

                // B must finish while the retired A call is still permanently blocked. Releasing A
                // first would only prove serialization, the exact head-of-line bug this lane fixes.
                assertSame(LatestReviewSetupLane.Outcome.PUBLISHED, replacement.await())
                assertSame(LatestReviewSetupLane.Outcome.RETIRED, old.await())
                assertTrue(published == listOf("player-video-B"))

                releaseSetup.countDown()
            }

            assertTrue(oldReleased.await(2, TimeUnit.SECONDS))
            assertTrue(released.contains("player-video-A"))
            assertTrue(published == listOf("player-video-B"))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `fully poisoned setup lane returns the typed restart terminal`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val release = CountDownLatch(1)
        try {
            val firstStarted = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            val lane = LatestReviewSetupLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                terminalTimeoutMs = 100,
                work = {
                    when (it) {
                        "A" -> firstStarted.countDown()
                        "B" -> secondStarted.countDown()
                    }
                    release.await()
                    it
                },
                release = {},
            )

            runBlocking {
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.run(Any(), "A") {}
                }
                assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.run(Any(), "B") {}
                }
                assertTrue(secondStarted.await(2, TimeUnit.SECONDS))

                assertSame(
                    LatestReviewSetupLane.Outcome.CAPACITY_EXHAUSTED,
                    lane.run(Any(), "C") {},
                )
                assertSame(LatestReviewSetupLane.Outcome.RETIRED, first.await())
                assertSame(LatestReviewSetupLane.Outcome.RETIRED, second.await())
            }
        } finally {
            release.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `one active slow setup is retryable rather than capacity exhausted`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val release = CountDownLatch(1)
        try {
            val started = CountDownLatch(1)
            val released = CountDownLatch(1)
            val lane = LatestReviewSetupLane<String, String>(
                dispatcher = dispatcher,
                workerCount = 2,
                terminalTimeoutMs = 100,
                work = {
                    started.countDown()
                    release.await()
                    it
                },
                release = { released.countDown() },
            )

            runBlocking {
                val slow = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.run(Any(), "slow") {}
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertSame(LatestReviewSetupLane.Outcome.TIMED_OUT, slow.await())
                release.countDown()
            }

            assertTrue(released.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `timed out frozen still result is disposed when its worker returns`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val created = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val bitmapRef = AtomicReference<Bitmap>()
        try {
            val lane = LatestReviewSetupLane<String, FrozenStillReview>(
                dispatcher = dispatcher,
                terminalTimeoutMs = 100L,
                work = {
                    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    bitmapRef.set(bitmap)
                    created.countDown()
                    releaseWork.await(2, TimeUnit.SECONDS)
                    FrozenStillReview(ReviewBitmapLoad.Ready(ReviewBitmap(bitmap)), null)
                },
                release = { result ->
                    result.dispose()
                    disposed.countDown()
                },
            )

            runBlocking {
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.run(Any(), "still") {}
                }
                assertTrue(created.await(2, TimeUnit.SECONDS))
                assertSame(LatestReviewSetupLane.Outcome.TIMED_OUT, request.await())
                releaseWork.countDown()
            }

            assertTrue(disposed.await(2, TimeUnit.SECONDS))
            assertTrue(checkNotNull(bitmapRef.get()).isRecycled)
        } finally {
            releaseWork.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `replacement and owner disposal retire only unpublished frozen still pixels`() {
        val executor = Executors.newFixedThreadPool(2)
        val dispatcher = executor.asCoroutineDispatcher()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldDisposed = CountDownLatch(1)
        val oldBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val newBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val oldReview = ReviewBitmap(oldBitmap)
        val newReview = ReviewBitmap(newBitmap)
        val published = AtomicReference<FrozenStillReview>()
        try {
            val lane = LatestReviewSetupLane<String, FrozenStillReview>(
                dispatcher = dispatcher,
                work = { input ->
                    if (input == "old") {
                        oldStarted.countDown()
                        releaseOld.await(2, TimeUnit.SECONDS)
                        FrozenStillReview(ReviewBitmapLoad.Ready(oldReview), null)
                    } else {
                        FrozenStillReview(ReviewBitmapLoad.Ready(newReview), null)
                    }
                },
                release = { result ->
                    val retiredOld = (result.bitmap as? ReviewBitmapLoad.Ready)?.bitmap === oldReview
                    result.dispose()
                    if (retiredOld) oldDisposed.countDown()
                },
            )
            val oldOwner = Any()
            val replacementOwner = Any()

            runBlocking {
                val old = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.run(oldOwner, "old") {}
                }
                assertTrue(oldStarted.await(2, TimeUnit.SECONDS))
                val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                    lane.run(replacementOwner, "new", published::set)
                }
                assertSame(LatestReviewSetupLane.Outcome.PUBLISHED, replacement.await())
                assertSame(LatestReviewSetupLane.Outcome.RETIRED, old.await())
                lane.invalidate(replacementOwner) // a published result is caller-owned and remains live
                assertFalse(newBitmap.isRecycled)
                releaseOld.countDown()
            }

            assertTrue(oldDisposed.await(2, TimeUnit.SECONDS))
            assertTrue(oldBitmap.isRecycled)
            assertFalse(newBitmap.isRecycled)
            checkNotNull(published.get()).dispose()
            assertTrue(newBitmap.isRecycled)
        } finally {
            releaseOld.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `never-published review bitmap is recycled promptly`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val owned = ReviewBitmap(bitmap)

        owned.dispose()

        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `published bitmap survives Compose replacement and removal`() {
        val firstBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val secondBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val first = ReviewBitmap(firstBitmap).transferToComposition()
        val second = ReviewBitmap(secondBitmap).transferToComposition()
        var current by mutableStateOf<ReviewBitmap?>(first)

        compose.setContent {
            current?.let { review ->
                Image(
                    bitmap = review.image,
                    contentDescription = null,
                    modifier = Modifier.size(8.dp),
                )
            }
        }
        compose.waitForIdle()

        compose.runOnIdle {
            val previous = current
            current = second
            // Exact production ordering: state schedules replacement, then old state retires.
            previous?.dispose()
            assertSame(first, previous)
            assertFalse(firstBitmap.isRecycled)
        }
        compose.waitForIdle() // forces Compose to draw the replacement after retirement was requested

        compose.runOnIdle {
            val previous = current
            current = null
            // Overlay removal has the same rule: Compose may retain a draw reference until apply.
            previous?.dispose()
            assertSame(second, previous)
            assertFalse(secondBitmap.isRecycled)
        }
        compose.waitForIdle() // no recycled-bitmap draw exception during removal
    }
}
