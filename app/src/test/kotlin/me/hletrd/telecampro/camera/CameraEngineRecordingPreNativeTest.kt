package me.hletrd.telecampro.camera

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import me.hletrd.telecampro.storage.PendingOutputDiscardResult
import me.hletrd.telecampro.storage.CaptureFamilyMedia
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Production-entry composition coverage for the pre-native half of CameraEngine REC admission. */
@RunWith(RobolectricTestRunner::class)
class CameraEngineRecordingPreNativeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val engines = CopyOnWriteArrayList<CameraEngine>()
    private val dispatchers = CopyOnWriteArrayList<RecordingPreNativeAllocationDispatcher>()
    private val providerReleases = CopyOnWriteArrayList<CountDownLatch>()

    init {
        RobolectricEglSentinels.ensure()
    }

    @After
    fun tearDown() {
        providerReleases.forEach { it.countDown() }
        engines.forEach { runCatching { it.release() } }
        dispatchers.forEach { it.shutdown() }
    }

    @Test
    fun `real start Stop retires blocked provider and durably discards stale row once`() {
        val providerEntered = CountDownLatch(1)
        val providerRelease = trackedRelease()
        val providerReturned = CountDownLatch(1)
        val discardCalled = CountDownLatch(1)
        val discarded = CopyOnWriteArrayList<Uri>()
        val micClaims = AtomicInteger()
        val results = CopyOnWriteArrayList<Boolean>()
        val resultCalled = CountDownLatch(1)
        val uri = Uri.parse("content://video/stale-stop")
        val dispatcher = trackedDispatcher()
        val engine = engine(
            overrides(
                allocate = { _, _ ->
                    providerEntered.countDown()
                    providerRelease.await()
                    providerReturned.countDown()
                    uri
                },
                dispatch = dispatcher::dispatch,
                afterMic = { _, _, _ -> micClaims.incrementAndGet(); false },
                discard = {
                    discarded += it
                    discardCalled.countDown()
                    PendingOutputDiscardResult.RECOVERY_MARKED
                },
            ),
        )

        engine.startRecording(recordAudio = true) {
            results += it
            resultCalled.countDown()
        }
        assertTrue(providerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))

        engine.stopRecording()
        assertTrue(resultCalled.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf(false), results.toList())
        assertEquals(0, micClaims.get())

        providerRelease.countDown()
        assertTrue(providerReturned.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(discardCalled.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf(uri), discarded.toList())
    }

    @Test
    fun `pre-native override defaults keep admission current and production timeout`() {
        val overrides = RecordingPreNativeEngineOverrides(
            allocatePendingVideo = { _, _ -> null },
            dispatchAllocation = { RecordingPreNativeSubmission(RecordingPreNativeDispatch.OVERFLOW) },
            scheduleDeadline = { _, _ -> null },
            afterMicrophoneClaim = { _, _, _ -> false },
            discardPendingOutput = { PendingOutputDiscardResult.RECOVERY_MARKED },
        )

        assertTrue(overrides.admissionCurrent())
        assertEquals(8_000L, overrides.allocationTimeoutMs)
    }

    @Test
    fun `real start timeout retires provider with exactly one result and failure status`() {
        val providerEntered = CountDownLatch(1)
        val providerRelease = trackedRelease()
        val discardCalled = CountDownLatch(1)
        val deadline = ManualDeadline()
        val results = CopyOnWriteArrayList<Boolean>()
        val statuses = CopyOnWriteArrayList<CameraStatusMessage>()
        val resultCalled = CountDownLatch(1)
        val dispatcher = trackedDispatcher()
        val engine = engine(
            overrides(
                allocate = { _, _ ->
                    providerEntered.countDown()
                    providerRelease.await()
                    Uri.parse("content://video/stale-timeout")
                },
                dispatch = dispatcher::dispatch,
                schedule = deadline::schedule,
                afterMic = { _, _, _ -> error("timeout must retire before microphone claim") },
                discard = {
                    discardCalled.countDown()
                    PendingOutputDiscardResult.RECOVERY_MARKED
                },
            ),
        )
        engine.onStatus = { it?.message?.let(statuses::add) }

        engine.startRecording(recordAudio = true) {
            results += it
            resultCalled.countDown()
        }
        assertTrue(providerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        deadline.fire()
        assertTrue(resultCalled.await(WAIT_SECONDS, TimeUnit.SECONDS))

        providerRelease.countDown()
        assertTrue(discardCalled.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf(false), results.toList())
        assertEquals(1, statuses.count { it == CameraStatusMessage.RECORDING_FAILED })
    }

    @Test
    fun `pause retires real start before microphone and late row enters discard recovery`() {
        val providerEntered = CountDownLatch(1)
        val providerRelease = trackedRelease()
        val discardCalled = CountDownLatch(1)
        val results = CopyOnWriteArrayList<Boolean>()
        val resultCalled = CountDownLatch(1)
        val dispatcher = trackedDispatcher()
        val engine = engine(
            overrides(
                allocate = { _, _ ->
                    providerEntered.countDown()
                    providerRelease.await()
                    Uri.parse("content://video/stale-pause")
                },
                dispatch = dispatcher::dispatch,
                afterMic = { _, _, _ -> error("pause must retire before microphone claim") },
                discard = {
                    discardCalled.countDown()
                    PendingOutputDiscardResult.RECOVERY_MARKED
                },
            ),
        )

        engine.startRecording(false) {
            results += it
            resultCalled.countDown()
        }
        assertTrue(providerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        engine.pause()
        assertTrue(resultCalled.await(WAIT_SECONDS, TimeUnit.SECONDS))
        providerRelease.countDown()
        assertTrue(discardCalled.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf(false), results.toList())
    }

    @Test
    fun `release retires real start and leaves post-release late row for launch recovery`() {
        val providerEntered = CountDownLatch(1)
        val providerRelease = trackedRelease()
        val providerReturned = CountDownLatch(1)
        val allocationFinished = CountDownLatch(1)
        val discardCalls = AtomicInteger()
        val results = CopyOnWriteArrayList<Boolean>()
        val resultCalled = CountDownLatch(1)
        val dispatcher = trackedDispatcher()
        val engine = engine(
            overrides(
                allocate = { _, _ ->
                    providerEntered.countDown()
                    providerRelease.await()
                    providerReturned.countDown()
                    Uri.parse("content://video/stale-release")
                },
                dispatch = { task ->
                    dispatcher.dispatch {
                        try {
                            task()
                        } finally {
                            allocationFinished.countDown()
                        }
                    }
                },
                afterMic = { _, _, _ -> error("release must retire before microphone claim") },
                discard = {
                    discardCalls.incrementAndGet()
                    PendingOutputDiscardResult.RECOVERY_MARKED
                },
            ),
        )

        engine.startRecording(false) {
            results += it
            resultCalled.countDown()
        }
        assertTrue(providerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        engine.release()
        engines.remove(engine)
        assertTrue(resultCalled.await(WAIT_SECONDS, TimeUnit.SECONDS))

        providerRelease.countDown()
        assertTrue(providerReturned.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(allocationFinished.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf(false), results.toList())
        // The Engine's storage dispatcher is terminal. The REGISTERED row deliberately remains for
        // the next launch's structural recovery instead of resurrecting callbacks after release.
        assertEquals(0, discardCalls.get())
    }

    @Test
    fun `dispatch rejection reports once without allocating and releases process admission`() {
        val allocations = AtomicInteger()
        val statuses = CopyOnWriteArrayList<CameraStatusMessage>()
        val firstResults = CopyOnWriteArrayList<Boolean>()
        val firstDone = CountDownLatch(1)
        val rejected = engine(
            overrides(
                allocate = { _, _ -> allocations.incrementAndGet(); null },
                dispatch = { RecordingPreNativeSubmission(RecordingPreNativeDispatch.OVERFLOW) },
                afterMic = { _, _, _ -> error("rejected dispatch cannot claim microphone") },
            ),
        )
        rejected.onStatus = { it?.message?.let(statuses::add) }

        rejected.startRecording(false) {
            firstResults += it
            firstDone.countDown()
        }
        assertTrue(firstDone.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(0, allocations.get())
        assertEquals(listOf(false), firstResults.toList())
        assertEquals(1, statuses.count { it == CameraStatusMessage.RECORDING_FAILED })

        // A leaked process token from the rejected Engine would refuse this second real entry before
        // its post-mic terminal. Reaching the hook proves admission was abandoned.
        val micClaims = AtomicInteger()
        val secondDone = CountDownLatch(1)
        val accepted = engine(
            overrides(
                allocate = { _, _ -> Uri.parse("content://video/after-rejection") },
                dispatch = ::runInline,
                afterMic = { _, _, _ -> micClaims.incrementAndGet(); false },
            ),
        )
        accepted.startRecording(false) { secondDone.countDown() }
        assertTrue(secondDone.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, micClaims.get())
    }

    @Test
    fun `finite allocator saturation rejects real start without running provider`() {
        val dispatcher = trackedDispatcher(workerCount = 1, backlogCapacity = 1)
        val release = trackedRelease()
        val workerEntered = CountDownLatch(1)
        assertEquals(
            RecordingPreNativeDispatch.ACCEPTED,
            dispatcher.dispatch {
                workerEntered.countDown()
                release.await()
            }.dispatch,
        )
        assertTrue(workerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(
            RecordingPreNativeDispatch.ACCEPTED,
            dispatcher.dispatch { release.await() }.dispatch,
        )
        val allocations = AtomicInteger()
        val done = CountDownLatch(1)
        val engine = engine(
            overrides(
                allocate = { _, _ -> allocations.incrementAndGet(); null },
                dispatch = dispatcher::dispatch,
                afterMic = { _, _, _ -> error("saturation cannot claim microphone") },
            ),
        )

        engine.startRecording(false) { done.countDown() }
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(0, allocations.get())
        assertEquals(1, dispatcher.activeTaskCount())
        assertEquals(1, dispatcher.queuedTaskCount())
    }

    @Test
    fun `superseded admission discards allocated row before microphone claim`() {
        val current = AtomicBoolean(true)
        val providerEntered = CountDownLatch(1)
        val providerRelease = trackedRelease()
        val discardCalled = CountDownLatch(1)
        val micClaims = AtomicInteger()
        val statuses = CopyOnWriteArrayList<CameraStatusMessage>()
        val done = CountDownLatch(1)
        val dispatcher = trackedDispatcher()
        val engine = engine(
            overrides(
                admissionCurrent = current::get,
                allocate = { _, _ ->
                    providerEntered.countDown()
                    providerRelease.await()
                    Uri.parse("content://video/superseded")
                },
                dispatch = dispatcher::dispatch,
                afterMic = { _, _, _ -> micClaims.incrementAndGet(); false },
                discard = {
                    discardCalled.countDown()
                    PendingOutputDiscardResult.RECOVERY_MARKED
                },
            ),
        )
        engine.onStatus = { it?.message?.let(statuses::add) }

        engine.startRecording(false) { done.countDown() }
        assertTrue(providerEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        current.set(false)
        providerRelease.countDown()
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(discardCalled.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(0, micClaims.get())
        assertEquals(1, statuses.count { it == CameraStatusMessage.CAMERA_RECONFIGURING })
    }

    @Test
    fun `two post-mic refusals release process mic and callback owners exactly once`() {
        val micClaims = AtomicInteger()
        val discards = CountDownLatch(2)
        val results = CopyOnWriteArrayList<Boolean>()
        val done = CountDownLatch(2)
        val engine = engine(
            overrides(
                allocate = { _, _ ->
                    Uri.parse("content://video/post-mic-${micClaims.get() + 1}")
                },
                dispatch = ::runInline,
                afterMic = { _, _, _ -> micClaims.incrementAndGet(); false },
                discard = {
                    discards.countDown()
                    PendingOutputDiscardResult.RECOVERY_MARKED
                },
            ),
        )

        repeat(2) {
            val attemptDone = CountDownLatch(1)
            engine.startRecording(recordAudio = true) { result ->
                results += result
                done.countDown()
                attemptDone.countDown()
            }
            assertTrue(attemptDone.await(WAIT_SECONDS, TimeUnit.SECONDS))
        }

        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(discards.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf(false, false), results.toList())
        assertEquals(2, micClaims.get())
    }

    @Test
    fun `real video admission publishes canonical family before provider allocation`() {
        val order = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        val engine = engine(
            overrides(
                allocate = { name, _ ->
                    order += "allocate:$name"
                    null
                },
                dispatch = ::runInline,
                afterMic = { _, _, _ -> false },
            ),
        )
        engine.onCaptureFamilyRegistered = { captureId, family, lateStill ->
            assertEquals(captureId.toLong(), family.sequence)
            assertEquals(CaptureFamilyMedia.VIDEO, family.media)
            assertFalse(lateStill)
            order += "family:${family.displayName("mp4")}"
        }

        engine.startRecording(recordAudio = false) { done.countDown() }

        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(2, order.size)
        assertTrue(order[0].startsWith("family:"))
        assertTrue(order[1].startsWith("allocate:"))
        assertEquals(order[0].removePrefix("family:"), order[1].removePrefix("allocate:"))
    }

    @Test
    fun `release classifies claimed setup before replacement Engine admission`() {
        val oldSetupEntered = CountDownLatch(1)
        val releaseOldSetup = CountDownLatch(1)
        val releaseReturned = CountDownLatch(1)
        val replacementClaimed = CountDownLatch(1)
        val old = engine(
            overrides(
                allocate = { _, _ -> Uri.parse("content://video/claimed-old") },
                dispatch = ::runInline,
                setupReleaseTimeoutMs = 25L,
                afterMic = { _, _, _ ->
                    oldSetupEntered.countDown()
                    releaseOldSetup.await()
                    false
                },
            ),
        )
        old.startRecording(false) {}
        assertTrue(oldSetupEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))

        Thread {
            old.release()
            releaseReturned.countDown()
        }.start()
        assertTrue(releaseReturned.await(WAIT_SECONDS, TimeUnit.SECONDS))
        engines.remove(old)

        val replacement = engine(
            overrides(
                allocate = { _, _ -> Uri.parse("content://video/claimed-replacement") },
                dispatch = ::runInline,
                afterMic = { _, _, _ -> replacementClaimed.countDown(); false },
            ),
        )
        replacement.startRecording(false) {}
        assertTrue(replacementClaimed.await(WAIT_SECONDS, TimeUnit.SECONDS))

        releaseOldSetup.countDown()
    }

    private fun engine(overrides: RecordingPreNativeEngineOverrides): CameraEngine =
        CameraEngine(app, overrides).also(engines::add)

    private fun overrides(
        admissionCurrent: () -> Boolean = { true },
        allocate: (String, String) -> Uri?,
        dispatch: ((() -> Unit) -> RecordingPreNativeSubmission),
        schedule: (Long, () -> Unit) -> RecordingTeardownCancellation? = { _, _ ->
            RecordingTeardownCancellation {}
        },
        setupReleaseTimeoutMs: Long = 14_000L,
        afterMic: (Uri, Int, Boolean) -> Boolean,
        discard: (Uri) -> PendingOutputDiscardResult = {
            PendingOutputDiscardResult.RECOVERY_MARKED
        },
    ) = RecordingPreNativeEngineOverrides(
        admissionCurrent = admissionCurrent,
        allocatePendingVideo = allocate,
        dispatchAllocation = dispatch,
        scheduleDeadline = schedule,
        allocationTimeoutMs = 1L,
        setupReleaseTimeoutMs = setupReleaseTimeoutMs,
        afterMicrophoneClaim = afterMic,
        discardPendingOutput = discard,
    )

    private fun trackedDispatcher(
        workerCount: Int = 1,
        backlogCapacity: Int = 1,
    ): RecordingPreNativeAllocationDispatcher =
        RecordingPreNativeAllocationDispatcher(workerCount, backlogCapacity).also(dispatchers::add)

    private fun trackedRelease(): CountDownLatch = CountDownLatch(1).also(providerReleases::add)

    private fun runInline(task: () -> Unit): RecordingPreNativeSubmission {
        task()
        return RecordingPreNativeSubmission(RecordingPreNativeDispatch.ACCEPTED)
    }

    private class ManualDeadline {
        private val action = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        private val canceled = AtomicBoolean(false)

        fun schedule(delayMs: Long, timeout: () -> Unit): RecordingTeardownCancellation {
            assertTrue(delayMs > 0L)
            action.set(timeout)
            return RecordingTeardownCancellation { canceled.set(true) }
        }

        fun fire() {
            assertFalse(canceled.get())
            checkNotNull(action.get()).invoke()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 5L
    }
}
