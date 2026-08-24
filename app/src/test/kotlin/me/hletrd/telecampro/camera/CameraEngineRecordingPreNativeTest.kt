package me.hletrd.telecampro.camera

import android.app.Application
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import me.hletrd.telecampro.storage.PendingOutputDiscardResult
import me.hletrd.telecampro.storage.CaptureFamilyMedia
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import me.hletrd.telecampro.video.UnsafeRecorderQuarantine
import me.hletrd.telecampro.video.EncoderSelection
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
    fun `real start executes production encoder admission before test native effects`() {
        val decisions = CopyOnWriteArrayList<RecordingEncoderAdmission>()
        val statuses = CopyOnWriteArrayList<CameraStatusMessage>()
        val results = CopyOnWriteArrayList<Boolean>()
        val done = CountDownLatch(1)
        val allocationCalls = AtomicInteger(0)
        val overrides = RecordingPreNativeEngineOverrides(
            allocatePendingVideo = { _, _ -> allocationCalls.incrementAndGet(); null },
            dispatchAllocation = { RecordingPreNativeSubmission(RecordingPreNativeDispatch.OVERFLOW) },
            scheduleDeadline = { _, _ -> null },
            afterMicrophoneClaim = { _, _, _ -> false },
            discardPendingOutput = { PendingOutputDiscardResult.RECOVERY_MARKED },
            useProductionEncoderAdmission = true,
            onEncoderAdmission = decisions::add,
        )
        val engine = engine(overrides)
        installAcceptedSession(engine)
        engine.onStatus = { it?.message?.let(statuses::add) }

        engine.startRecording(recordAudio = false) { result ->
            results += result
            done.countDown()
        }

        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf(false), results.toList())
        assertEquals(1, decisions.size)
        assertEquals(CameraStatusMessage.SELECTED_FPS_UNAVAILABLE, decisions.single().failure)
        assertEquals(1, statuses.count { it == CameraStatusMessage.SELECTED_FPS_UNAVAILABLE })
        assertEquals(0, allocationCalls.get())
    }

    @Test
    fun `production REC snapshot excludes a concurrent pipeline packet`() {
        val snapshotEntered = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val inputs = CopyOnWriteArrayList<RecordingAdmissionInputs>()
        val resultDone = CountDownLatch(1)
        val pipelineDone = CountDownLatch(1)
        val overrides = RecordingPreNativeEngineOverrides(
            allocatePendingVideo = { _, _ -> null },
            dispatchAllocation = { RecordingPreNativeSubmission(RecordingPreNativeDispatch.OVERFLOW) },
            scheduleDeadline = { _, _ -> null },
            afterMicrophoneClaim = { _, _, _ -> false },
            discardPendingOutput = { PendingOutputDiscardResult.RECOVERY_MARKED },
            useProductionEncoderAdmission = true,
            beforeEncoderAdmissionSnapshot = {
                snapshotEntered.countDown()
                releaseSnapshot.await(WAIT_SECONDS, TimeUnit.SECONDS)
            },
            onEncoderAdmissionInputs = inputs::add,
        )
        val engine = engine(overrides)
        installAcceptedSession(engine)
        val hevc = EncoderSelection(
            VideoCodec.HEVC,
            "test-main10",
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            hardwareAccelerated = true,
            main10 = true,
        )
        val avc = EncoderSelection(
            VideoCodec.AVC,
            "test-main",
            MediaFormat.MIMETYPE_VIDEO_AVC,
            hardwareAccelerated = true,
            main10 = false,
        )
        engine.setVideoPipeline(listOf(hevc), ColorTransfer.HLG, VideoCodec.HEVC)

        engine.startRecording(recordAudio = false) { resultDone.countDown() }
        assertTrue(snapshotEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val pipelineThread = Thread {
            try {
                engine.setVideoPipeline(listOf(avc), ColorTransfer.SDR, VideoCodec.AVC)
            } finally {
                pipelineDone.countDown()
            }
        }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (pipelineThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, pipelineThread.state)
        releaseSnapshot.countDown()

        assertTrue(resultDone.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(pipelineDone.await(WAIT_SECONDS, TimeUnit.SECONDS))
        pipelineThread.join()
        assertEquals(1, inputs.size)
        assertEquals(VideoCodec.HEVC, inputs.single().codec)
        assertEquals(listOf(VideoCodec.HEVC), inputs.single().candidates.map { it.codec })
        assertEquals(VideoCodec.AVC, field(engine, "videoCodec"))
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

    @Test
    fun `same Engine resume during claimed setup replays retained preview after clean retirement`() {
        val setupEntered = CountDownLatch(1)
        val releaseSetup = CountDownLatch(1)
        val replayed = CountDownLatch(1)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val engine = engine(
            overrides(
                allocate = { _, _ -> Uri.parse("content://video/resume-replay") },
                dispatch = ::runInline,
                afterMic = { _, _, _ ->
                    setupEntered.countDown()
                    releaseSetup.await()
                    false
                },
                onReplay = replayed::countDown,
            ),
        )
        try {
            engine.startRecording(false) {}
            assertTrue(setupEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))

            engine.onPreviewSurfaceAvailable(surface, 1080, 1920)
            engine.pause()
            engine.resume()
            assertFalse(replayed.await(50, TimeUnit.MILLISECONDS))

            releaseSetup.countDown()
            assertTrue(replayed.await(WAIT_SECONDS, TimeUnit.SECONDS))
        } finally {
            releaseSetup.countDown()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun `replacement Engine retains surface and replays only after foreign setup retires`() {
        val oldSetupEntered = CountDownLatch(1)
        val releaseOldSetup = CountDownLatch(1)
        val replacementReplayed = CountDownLatch(1)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val old = engine(
            overrides(
                allocate = { _, _ -> Uri.parse("content://video/foreign-setup") },
                dispatch = ::runInline,
                afterMic = { _, _, _ ->
                    oldSetupEntered.countDown()
                    releaseOldSetup.await()
                    false
                },
            ),
        )
        val replacement = engine(
            overrides(
                allocate = { _, _ -> null },
                dispatch = ::runInline,
                afterMic = { _, _, _ -> false },
                onReplay = replacementReplayed::countDown,
            ),
        )
        try {
            old.startRecording(false) {}
            assertTrue(oldSetupEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))

            replacement.onPreviewSurfaceAvailable(surface, 1080, 1920)
            assertFalse(replacementReplayed.await(50, TimeUnit.MILLISECONDS))

            releaseOldSetup.countDown()
            assertTrue(replacementReplayed.await(WAIT_SECONDS, TimeUnit.SECONDS))
        } finally {
            releaseOldSetup.countDown()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun `replacement surface arriving after foreign recorder publish waits for strict finish`() {
        val foreignOwner = Any()
        val foreignToken = checkNotNull(UnsafeRecorderQuarantine.snapshotAdmission(foreignOwner))
        assertTrue(UnsafeRecorderQuarantine.publishAdmission(foreignToken) { true })
        val replacementReplayed = CountDownLatch(1)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val replacement = engine(
            overrides(
                allocate = { _, _ -> null },
                dispatch = ::runInline,
                afterMic = { _, _, _ -> false },
                onReplay = replacementReplayed::countDown,
            ),
        )
        try {
            replacement.onPreviewSurfaceAvailable(surface, 1080, 1920)
            assertFalse(replacementReplayed.await(50, TimeUnit.MILLISECONDS))

            UnsafeRecorderQuarantine.finishAdmission(foreignToken)
            assertTrue(replacementReplayed.await(WAIT_SECONDS, TimeUnit.SECONDS))
        } finally {
            UnsafeRecorderQuarantine.finishAdmission(foreignToken)
            surface.release()
            texture.release()
        }
    }

    @Test
    fun `token acquired after advisory but before GL native entry replays after retirement`() {
        val foreignToken = java.util.concurrent.atomic.AtomicReference<
            me.hletrd.telecampro.video.UnsafeRecorderAdmissionToken?
        >()
        val nativeBarrierEntered = CountDownLatch(1)
        val replayed = CountDownLatch(1)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val engine = engine(
            overrides(
                allocate = { _, _ -> null },
                dispatch = ::runInline,
                afterMic = { _, _, _ -> false },
                onReplay = replayed::countDown,
                beforeGlNative = {
                    if (foreignToken.get() == null) {
                        val token = checkNotNull(UnsafeRecorderQuarantine.snapshotAdmission(Any()))
                        check(UnsafeRecorderQuarantine.publishAdmission(token) { true })
                        foreignToken.compareAndSet(null, token)
                    }
                    nativeBarrierEntered.countDown()
                },
            ),
        )
        try {
            engine.onPreviewSurfaceAvailable(surface, 1080, 1920)
            assertTrue(nativeBarrierEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertFalse(replayed.await(100, TimeUnit.MILLISECONDS))

            UnsafeRecorderQuarantine.finishAdmission(checkNotNull(foreignToken.get()))
            assertTrue(replayed.await(WAIT_SECONDS, TimeUnit.SECONDS))
        } finally {
            foreignToken.get()?.let(UnsafeRecorderQuarantine::finishAdmission)
            surface.release()
            texture.release()
        }
    }

    @Test
    fun `released Engine cancels replay after advisory native-entry race`() {
        val foreignToken = java.util.concurrent.atomic.AtomicReference<
            me.hletrd.telecampro.video.UnsafeRecorderAdmissionToken?
        >()
        val nativeBarrierEntered = CountDownLatch(1)
        val staleReplay = CountDownLatch(1)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val engine = engine(
            overrides(
                allocate = { _, _ -> null },
                dispatch = ::runInline,
                afterMic = { _, _, _ -> false },
                onReplay = staleReplay::countDown,
                beforeGlNative = {
                    if (foreignToken.get() == null) {
                        val token = checkNotNull(UnsafeRecorderQuarantine.snapshotAdmission(Any()))
                        check(UnsafeRecorderQuarantine.publishAdmission(token) { true })
                        foreignToken.compareAndSet(null, token)
                    }
                    nativeBarrierEntered.countDown()
                },
            ),
        )
        try {
            engine.onPreviewSurfaceAvailable(surface, 1080, 1920)
            assertTrue(nativeBarrierEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            engine.release()
            engines.remove(engine)

            UnsafeRecorderQuarantine.finishAdmission(checkNotNull(foreignToken.get()))
            assertFalse(staleReplay.await(100, TimeUnit.MILLISECONDS))
        } finally {
            foreignToken.get()?.let(UnsafeRecorderQuarantine::finishAdmission)
            surface.release()
            texture.release()
        }
    }

    @Test
    fun `same Engine preview bind pending race rebinds active graph after publication`() {
        val token = java.util.concurrent.atomic.AtomicReference<
            me.hletrd.telecampro.video.UnsafeRecorderAdmissionToken?
        >()
        val previewBarrierEntered = CountDownLatch(1)
        val bindAttempts = CountDownLatch(2)
        val fullGraphReplays = AtomicInteger()
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val engine = engine(
            overrides(
                allocate = { _, _ -> null },
                dispatch = ::runInline,
                afterMic = { _, _, _ -> false },
                beforePreviewOutputNative = { owner ->
                    if (token.get() == null) {
                        token.compareAndSet(
                            null,
                            UnsafeRecorderQuarantine.snapshotAdmission(checkNotNull(owner)),
                        )
                    }
                    previewBarrierEntered.countDown()
                },
                onPreviewBind = bindAttempts::countDown,
                onFullGraphReplay = fullGraphReplays::incrementAndGet,
            ),
        )
        try {
            engine.onPreviewSurfaceAvailable(surface, 1080, 1920)
            assertTrue(previewBarrierEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            val ownedToken = checkNotNull(token.get())
            assertTrue(UnsafeRecorderQuarantine.publishAdmission(ownedToken) { true })

            assertTrue(bindAttempts.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(0, fullGraphReplays.get())
        } finally {
            token.get()?.let(UnsafeRecorderQuarantine::finishAdmission)
            surface.release()
            texture.release()
        }
    }

    @Test
    fun `released replacement cancels recorder setup replay`() {
        val oldSetupEntered = CountDownLatch(1)
        val releaseOldSetup = CountDownLatch(1)
        val staleReplay = CountDownLatch(1)
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val old = engine(
            overrides(
                allocate = { _, _ -> Uri.parse("content://video/released-replacement") },
                dispatch = ::runInline,
                afterMic = { _, _, _ ->
                    oldSetupEntered.countDown()
                    releaseOldSetup.await()
                    false
                },
            ),
        )
        val replacement = engine(
            overrides(
                allocate = { _, _ -> null },
                dispatch = ::runInline,
                afterMic = { _, _, _ -> false },
                onReplay = staleReplay::countDown,
            ),
        )
        try {
            old.startRecording(false) {}
            assertTrue(oldSetupEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            replacement.onPreviewSurfaceAvailable(surface, 1080, 1920)

            replacement.release()
            engines.remove(replacement)
            releaseOldSetup.countDown()

            assertFalse(staleReplay.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseOldSetup.countDown()
            surface.release()
            texture.release()
        }
    }

    private fun engine(overrides: RecordingPreNativeEngineOverrides): CameraEngine =
        CameraEngine(app, overrides).also(engines::add)

    private fun installAcceptedSession(engine: CameraEngine) {
        val controller = CameraController(app)
        val acceptedType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "AcceptedCameraSession" }
        val accepted = acceptedType.declaredConstructors
            .single { it.parameterTypes.size == 4 }
            .apply { isAccessible = true }
            .newInstance(controller, 0L, PhotoSessionOutputs(), false)
        setField(engine, "controller", controller)
        setField(engine, "readyController", controller)
        setField(engine, "acceptedCameraSession", accepted)
        setField(engine, "cameraReady", true)
        setField(engine, "previewReady", true)
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

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
        onReplay: (() -> Unit)? = null,
        beforeGlNative: (() -> Unit)? = null,
        beforePreviewOutputNative: ((Any?) -> Unit)? = null,
        onPreviewBind: (() -> Unit)? = null,
        onFullGraphReplay: (() -> Unit)? = null,
    ) = RecordingPreNativeEngineOverrides(
        admissionCurrent = admissionCurrent,
        allocatePendingVideo = allocate,
        dispatchAllocation = dispatch,
        scheduleDeadline = schedule,
        allocationTimeoutMs = 1L,
        setupReleaseTimeoutMs = setupReleaseTimeoutMs,
        afterMicrophoneClaim = afterMic,
        discardPendingOutput = discard,
        onNativeAcquisitionReplay = onReplay,
        beforeGlNativeAcquisition = beforeGlNative,
        beforePreviewOutputNativeAcquisition = beforePreviewOutputNative,
        onPreviewBindAttempt = onPreviewBind,
        onFullGraphReplayAttempt = onFullGraphReplay,
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
