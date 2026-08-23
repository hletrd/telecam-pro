package me.hletrd.telecampro.ui.review

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaReviewOwnershipTest {
    @get:Rule
    val compose = createComposeRule()

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
                assertTrue(replacement.await())
                assertFalse(old.await())
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
