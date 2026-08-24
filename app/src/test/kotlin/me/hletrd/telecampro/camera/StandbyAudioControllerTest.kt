package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import me.hletrd.telecampro.video.RecorderQuarantineAdmissionGate

// The production name of the thread-backed process-busy retry fallback (StandbyAudioController.kt).
private const val FALLBACK_THREAD_NAME = "StandbyAudioRetryFallback"

class StandbyAudioControllerTest {
    private class FakeInput(private val failStart: Boolean = false) : StandbyAudioInput {
        var starts = 0
        var stops = 0
        var releases = 0

        override fun start() {
            starts++
            if (failStart) error("injected start failure")
        }

        override fun read(samples: ShortArray): Int = error("read is not admitted in this fixture")
        override fun stop() { stops++ }
        override fun release() { releases++ }
    }

    @Test
    fun `standby input defaults to one interleaved channel`() {
        assertEquals(1, FakeInput().channelCount)
    }

    private data class Fixture(
        val controller: StandbyAudioController,
        val scheduled: ArrayDeque<() -> Unit>,
        val fallbackScheduled: ArrayDeque<() -> Unit>,
        val available: MutableList<Unit>,
        val unavailable: MutableList<StandbyAudioUnavailable>,
        val retained: MutableList<QuarantinedStandbyInput>,
        val unsafeNative: MutableList<Unit>,
    )

    private fun fixture(
        setup: StandbyAudioSetup,
        paused: () -> Boolean = { false },
        recorderAbsent: () -> Boolean = { false },
        canStart: () -> Boolean = { true },
        threadLauncher: StandbyThreadLauncher = StandbyThreadLauncher { _, task -> task(); true },
        reserveProcessAdmission: () -> (() -> Unit)? = { {} },
        nativeProcessGate: StandbyNativeProcessGate =
            RecorderStandbyNativeProcessGate(RecorderQuarantineAdmissionGate()),
        retryScheduler: StandbyRetryScheduler? = null,
        processBusyRetryFallback: StandbyRetryScheduler? = null,
        stopDispatcher: StandbyStopDispatcher = StandbyStopDispatcher { task -> task() },
        stopDeadlineScheduler: RecordingTeardownScheduler = RecordingTeardownScheduler { _, _ ->
            RecordingTeardownCancellation {}
        },
        stopTimeoutMs: Long = 1_500L,
        retainEffect: (QuarantinedStandbyInput) -> Unit = {},
        unsafeEffect: () -> Unit = {},
    ): Fixture {
        val scheduled = ArrayDeque<() -> Unit>()
        val fallbackScheduled = ArrayDeque<() -> Unit>()
        val available = mutableListOf<Unit>()
        val unavailable = mutableListOf<StandbyAudioUnavailable>()
        val retained = mutableListOf<QuarantinedStandbyInput>()
        val unsafeNative = mutableListOf<Unit>()
        return Fixture(
            controller = StandbyAudioController(
                audioGain = { 1f },
                onLevels = {},
                canStart = canStart,
                // A ready fake exits after setup/start without entering the blocking read loop.
                recorderAbsent = recorderAbsent,
                isPaused = paused,
                permissionGranted = { true },
                audioSetup = setup,
                threadLauncher = threadLauncher,
                retryScheduler = retryScheduler ?: StandbyRetryScheduler { _, task ->
                    scheduled.addLast(task)
                    true
                },
                processBusyRetryFallback = processBusyRetryFallback ?: StandbyRetryScheduler { _, task ->
                    fallbackScheduled.addLast(task)
                    true
                },
                stopDispatcher = stopDispatcher,
                stopDeadlineScheduler = stopDeadlineScheduler,
                stopTimeoutMs = stopTimeoutMs,
                onAvailable = { available += Unit },
                onUnavailable = unavailable::add,
                onUnsafeNative = {
                    unsafeNative += Unit
                    unsafeEffect()
                },
                reserveProcessAdmission = reserveProcessAdmission,
                nativeProcessGate = nativeProcessGate,
                retainQuarantinedInput = { owner ->
                    retained += owner
                    retainEffect(owner)
                },
            ),
            scheduled = scheduled,
            fallbackScheduled = fallbackScheduled,
            available = available,
            unavailable = unavailable,
            retained = retained,
            unsafeNative = unsafeNative,
        )
    }

    @Test
    fun `foreign process recorder blocks standby before native setup and retries quietly`() {
        var setupCalls = 0
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupCalls++
                error("process admission must dominate setup")
            },
            reserveProcessAdmission = { null },
        )

        fixture.controller.setEnabled(true)

        assertEquals(0, setupCalls)
        assertEquals(1, fixture.scheduled.size)
        assertTrue(fixture.unavailable.isEmpty())
    }

    @Test
    fun `rejected process-busy schedule falls back and recovers after lease release`() {
        var processBusy = true
        var setupCalls = 0
        val input = FakeInput()
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupCalls++
                StandbyAudioSetupResult.Ready(input)
            },
            reserveProcessAdmission = { if (processBusy) null else ({}) },
            retryScheduler = StandbyRetryScheduler { _, _ -> false },
        )

        fixture.controller.setEnabled(true)
        assertEquals(0, setupCalls)
        assertEquals(1, fixture.fallbackScheduled.size)
        assertTrue(fixture.unavailable.isEmpty())

        processBusy = false
        fixture.fallbackScheduled.removeFirst().invoke()

        assertEquals(1, setupCalls)
        assertEquals(1, input.starts)
        assertEquals(1, input.stops)
        assertEquals(1, input.releases)
        assertTrue(fixture.unavailable.isEmpty())
    }

    @Test
    fun `throwing process-busy scheduler uses the same quiet fallback`() {
        var processBusy = true
        var setupCalls = 0
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupCalls++
                StandbyAudioSetupResult.Ready(FakeInput())
            },
            reserveProcessAdmission = { if (processBusy) null else ({}) },
            retryScheduler = StandbyRetryScheduler { _, _ -> error("injected scheduler throw") },
        )

        fixture.controller.setEnabled(true)
        assertEquals(1, fixture.fallbackScheduled.size)
        assertTrue(fixture.unavailable.isEmpty())

        processBusy = false
        fixture.fallbackScheduled.removeFirst().invoke()

        assertEquals(1, setupCalls)
        assertTrue(fixture.unavailable.isEmpty())
    }

    @Test
    fun `rejected primary and fallback report infrastructure state without audio failure charge`() {
        val fixture = fixture(
            setup = StandbyAudioSetup { error("busy admission must dominate setup") },
            reserveProcessAdmission = { null },
            retryScheduler = StandbyRetryScheduler { _, _ -> false },
            processBusyRetryFallback = StandbyRetryScheduler { _, _ -> false },
        )

        fixture.controller.setEnabled(true)

        assertEquals(
            StandbyAudioUnavailable(
                StandbyAudioFailureReason.RETRY_SCHEDULER,
                failedGenerations = 0,
            ),
            fixture.unavailable.single(),
        )
        assertTrue(fixture.scheduled.isEmpty())
        assertTrue(fixture.fallbackScheduled.isEmpty())
    }

    @Test
    fun `disable pause and REC handoff cancel a pending process-busy fallback`() {
        var disabledSetupCalls = 0
        val disabled = fixture(
            setup = StandbyAudioSetup {
                disabledSetupCalls++
                StandbyAudioSetupResult.Ready(FakeInput())
            },
            reserveProcessAdmission = { null },
            retryScheduler = StandbyRetryScheduler { _, _ -> false },
        )
        disabled.controller.setEnabled(true)
        disabled.controller.disable()
        disabled.fallbackScheduled.removeFirst().invoke()
        assertEquals(0, disabledSetupCalls)

        var paused = false
        var pausedSetupCalls = 0
        val pausedFixture = fixture(
            setup = StandbyAudioSetup {
                pausedSetupCalls++
                StandbyAudioSetupResult.Ready(FakeInput())
            },
            paused = { paused },
            reserveProcessAdmission = { null },
            retryScheduler = StandbyRetryScheduler { _, _ -> false },
        )
        pausedFixture.controller.setEnabled(true)
        paused = true
        pausedFixture.fallbackScheduled.removeFirst().invoke()
        assertEquals(0, pausedSetupCalls)

        var recordingSetupCalls = 0
        val recording = fixture(
            setup = StandbyAudioSetup {
                recordingSetupCalls++
                StandbyAudioSetupResult.Ready(FakeInput())
            },
            reserveProcessAdmission = { null },
            retryScheduler = StandbyRetryScheduler { _, _ -> false },
        )
        recording.controller.setEnabled(true)
        assertTrue(recording.controller.beginRecording().admitted)
        recording.fallbackScheduled.removeFirst().invoke()
        assertEquals(0, recordingSetupCalls)
        assertTrue(recording.unavailable.isEmpty())
    }

    @Test
    fun `process-busy retries do not consume the AudioRecord generation budget`() {
        var processBusy = true
        var setupFailures = 0
        val ordinaryRetries = ArrayDeque<() -> Unit>()
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupFailures++
                StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.CONSTRUCTION)
            },
            reserveProcessAdmission = { if (processBusy) null else ({}) },
            retryScheduler = StandbyRetryScheduler { _, task ->
                if (processBusy) {
                    false
                } else {
                    // Once the lease clears, ordinary AudioRecord failures use the normal queue.
                    true.also { ordinaryRetries.addLast(task) }
                }
            },
        )

        fixture.controller.setEnabled(true)
        repeat(5) {
            fixture.fallbackScheduled.removeFirst().invoke()
            assertTrue(fixture.unavailable.isEmpty())
        }

        processBusy = false
        fixture.fallbackScheduled.removeFirst().invoke()
        assertEquals(1, setupFailures)
        assertTrue(fixture.unavailable.isEmpty())
        repeat(3) { ordinaryRetries.removeFirst().invoke() }

        assertEquals(4, setupFailures)
        assertEquals(
            StandbyAudioUnavailable(StandbyAudioFailureReason.CONSTRUCTION, failedGenerations = 4),
            fixture.unavailable.single(),
        )
    }

    @Test
    fun `standby releases its process lease only after input release`() {
        val events = mutableListOf<String>()
        val input = object : StandbyAudioInput {
            override fun start() { events += "start" }
            override fun read(samples: ShortArray): Int = error("fixture exits before read")
            override fun stop() { events += "stop" }
            override fun release() { events += "input-release" }
        }
        val fixture = fixture(
            setup = StandbyAudioSetup { StandbyAudioSetupResult.Ready(input) },
            reserveProcessAdmission = {
                events += "process-reserve"
                { events += "process-release" }
            },
        )

        fixture.controller.setEnabled(true)

        assertEquals(
            listOf("process-reserve", "start", "stop", "input-release", "process-release"),
            events,
        )
    }

    @Test
    fun `REC handoff stops a blocked read off caller and releases the exact process owner`() {
        val readEntered = CountDownLatch(1)
        val unblockRead = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val processReleased = CountDownLatch(1)
        val stopTasks = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val input = object : StandbyAudioInput {
            override fun start() { synchronized(events) { events += "start" } }
            override fun read(samples: ShortArray): Int {
                readEntered.countDown()
                assertTrue(unblockRead.await(2, TimeUnit.SECONDS))
                return -1
            }
            override fun stop() {
                synchronized(events) { events += "stop" }
                unblockRead.countDown()
            }
            override fun release() { synchronized(events) { events += "input-release" } }
        }
        val fixture = fixture(
            setup = StandbyAudioSetup { StandbyAudioSetupResult.Ready(input) },
            recorderAbsent = { true },
            threadLauncher = StandbyThreadLauncher { name, task ->
                Thread(
                    {
                        try {
                            task()
                        } finally {
                            workerFinished.countDown()
                        }
                    },
                    name,
                ).apply { isDaemon = true }.start()
                true
            },
            stopDispatcher = StandbyStopDispatcher { task -> stopTasks.addLast(task) },
            reserveProcessAdmission = {
                synchronized(events) { events += "process-reserve" }
                val releaseProcess: () -> Unit = {
                    synchronized(events) { events += "process-release" }
                    processReleased.countDown()
                }
                releaseProcess
            },
        )

        fixture.controller.setEnabled(true)
        assertTrue(readEntered.await(2, TimeUnit.SECONDS))

        val claim = fixture.controller.beginRecording()
        assertTrue(claim.admitted)
        val release = checkNotNull(claim.release)
        assertEquals(1, stopTasks.size)
        assertFalse(release.await(20, TimeUnit.MILLISECONDS))
        assertFalse(processReleased.await(20, TimeUnit.MILLISECONDS))

        stopTasks.removeFirst().invoke()

        assertTrue(release.await(2, TimeUnit.SECONDS))
        assertTrue(workerFinished.await(2, TimeUnit.SECONDS))
        assertTrue(processReleased.await(2, TimeUnit.SECONDS))
        assertEquals(
            listOf("process-reserve", "start", "stop", "input-release", "process-release"),
            synchronized(events) { events.toList() },
        )
    }

    @Test
    fun `disable uses the same blocked-read stop owner without charging failure budget`() {
        val readEntered = CountDownLatch(1)
        val unblockRead = CountDownLatch(1)
        val released = CountDownLatch(1)
        val stopTasks = ArrayDeque<() -> Unit>()
        val input = object : StandbyAudioInput {
            val stops = AtomicInteger()
            override fun start() = Unit
            override fun read(samples: ShortArray): Int {
                readEntered.countDown()
                assertTrue(unblockRead.await(2, TimeUnit.SECONDS))
                return -1
            }
            override fun stop() {
                stops.incrementAndGet()
                unblockRead.countDown()
            }
            override fun release() { released.countDown() }
        }
        val fixture = fixture(
            setup = StandbyAudioSetup { StandbyAudioSetupResult.Ready(input) },
            recorderAbsent = { true },
            threadLauncher = StandbyThreadLauncher { name, task ->
                Thread({ task() }, name).apply { isDaemon = true }.start()
                true
            },
            stopDispatcher = StandbyStopDispatcher { task -> stopTasks.addLast(task) },
        )

        fixture.controller.setEnabled(true)
        assertTrue(readEntered.await(2, TimeUnit.SECONDS))
        fixture.controller.disable()
        fixture.controller.disable()

        assertEquals(1, stopTasks.size)
        stopTasks.removeFirst().invoke()
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(1, input.stops.get())
        assertTrue(fixture.unavailable.isEmpty())
        assertTrue(fixture.scheduled.isEmpty())
    }

    @Test
    fun `late stop task remains bound to its original input owner`() {
        val stopTasks = ArrayDeque<() -> Unit>()
        val stopped = mutableListOf<String>()
        val first = Any()
        val second = Any()
        val dispatcher = StandbyStopDispatcher { task -> stopTasks.addLast(task) }
        val firstOwner = StandbyInputTerminationOwner(
            generationId = 1L,
            stopDispatcher = dispatcher,
            stop = { value: Any -> stopped += if (value === first) "first" else "wrong" },
        )
        val secondOwner = StandbyInputTerminationOwner(
            generationId = 2L,
            stopDispatcher = dispatcher,
            stop = { value: Any -> stopped += if (value === second) "second" else "wrong" },
        )
        assertTrue(firstOwner.bind(first))
        assertTrue(firstOwner.beginStart(first))
        firstOwner.finishStart(first, succeeded = true)
        firstOwner.requestStop()
        assertTrue(secondOwner.bind(second))
        assertTrue(secondOwner.beginStart(second))
        secondOwner.finishStart(second, succeeded = true)

        stopTasks.removeFirst().invoke()
        firstOwner.finishAndRelease(first) {}
        secondOwner.finishAndRelease(second) {}

        assertEquals(listOf("first", "second"), stopped)
    }

    @Test
    fun `failed async stop dispatch falls back after the worker has begun waiting`() {
        val dispatchEntered = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val workerEntered = CountDownLatch(1)
        val workerDone = CountDownLatch(1)
        val input = Any()
        val events = mutableListOf<String>()
        val owner = StandbyInputTerminationOwner(
            generationId = 73L,
            stopDispatcher = StandbyStopDispatcher {
                dispatchEntered.countDown()
                releaseDispatch.await(2, TimeUnit.SECONDS)
                error("injected dispatcher rejection")
            },
            stop = { value: Any -> events += if (value === input) "stop" else "wrong-stop" },
        )

        assertEquals(73L, owner.generationId)
        assertTrue(owner.bind(input))
        assertTrue(owner.beginStart(input))
        owner.finishStart(input, succeeded = true)

        val requestThread = Thread(owner::requestStop, "standby-stop-request")
        requestThread.start()
        assertTrue(dispatchEntered.await(2, TimeUnit.SECONDS))

        val worker = Thread(
            {
                workerEntered.countDown()
                owner.finishAndRelease(input) { value ->
                    events += if (value === input) "release" else "wrong-release"
                }
                workerDone.countDown()
            },
            "standby-stop-fallback",
        )
        worker.start()
        assertTrue(workerEntered.await(2, TimeUnit.SECONDS))
        assertTrue(awaitThreadWait(worker))
        releaseDispatch.countDown()

        requestThread.join(2_000)
        assertTrue(workerDone.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("stop", "release"), events)

        // Terminal ownership is idempotent and rejects a replacement binding.
        owner.finishAndRelease(input) { events += "duplicate-release" }
        assertFalse(owner.bind(Any()))
        assertEquals(listOf("stop", "release"), events)
    }

    @Test
    fun `interrupted worker restores its flag after the owned async stop completes`() {
        val stopTasks = ArrayDeque<() -> Unit>()
        val input = Any()
        val workerDone = CountDownLatch(1)
        val owner = StandbyInputTerminationOwner(
            generationId = 74L,
            stopDispatcher = StandbyStopDispatcher { task -> stopTasks.addLast(task) },
            stop = { _: Any -> },
        )
        assertTrue(owner.bind(input))
        assertTrue(owner.beginStart(input))
        owner.finishStart(input, succeeded = true)
        owner.requestStop()

        val worker = Thread(
            {
                owner.finishAndRelease(input) {}
                if (Thread.currentThread().isInterrupted) workerDone.countDown()
            },
            "standby-stop-interrupted-wait",
        )
        worker.start()
        assertTrue(awaitThreadWait(worker))
        worker.interrupt()
        // Let the interrupted waiter enter its next bounded await before the exact stop completes.
        assertTrue(awaitThreadWait(worker))
        stopTasks.removeFirst().invoke()

        assertTrue(workerDone.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `stop timeout wakes an already waiting worker without releasing abandoned input`() {
        val stopTasks = ArrayDeque<() -> Unit>()
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val deadline = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        val input = Any()
        val releases = AtomicInteger()
        val timedOutOwners = mutableListOf<StandbyInputTerminationOwner<Any>>()
        val owner = StandbyInputTerminationOwner(
            generationId = 75L,
            stopDispatcher = StandbyStopDispatcher { task -> stopTasks.addLast(task) },
            stop = { value: Any ->
                assertTrue(value === input)
                stopEntered.countDown()
                releaseStop.await(2, TimeUnit.SECONDS)
            },
            stopDeadlineScheduler = RecordingTeardownScheduler { _, action ->
                deadline.set(action)
                RecordingTeardownCancellation { deadline.compareAndSet(action, null) }
            },
            stopTimeoutMs = 1L,
            onStopTimeout = { value, exactOwner ->
                assertTrue(value === input)
                timedOutOwners += exactOwner
                exactOwner.abandon(value)
            },
        )
        assertTrue(owner.bind(input))
        assertTrue(owner.beginStart(input))
        owner.finishStart(input, succeeded = true)
        owner.requestStop()

        val worker = Thread(
            { owner.finishAndRelease(input) { releases.incrementAndGet() } },
            "standby-timeout-waiter",
        )
        worker.start()
        assertTrue(awaitThreadWait(worker))

        val stopThread = Thread(stopTasks.removeFirst(), "standby-timeout-stop")
        stopThread.start()
        assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
        checkNotNull(deadline.getAndSet(null)).invoke()

        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertTrue(owner.isAbandoned())
        assertEquals(listOf(owner), timedOutOwners)
        assertEquals(0, releases.get())

        releaseStop.countDown()
        stopThread.join(2_000)
        assertFalse(stopThread.isAlive)
        assertEquals(0, releases.get())
    }

    @Test
    fun `default timeout fallback abandons the exact input`() {
        val stopTasks = ArrayDeque<() -> Unit>()
        val timeout = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val input = Any()
        val owner = StandbyInputTerminationOwner(
            generationId = 76L,
            stopDispatcher = StandbyStopDispatcher { stopTasks.addLast(it) },
            stop = { _: Any ->
                stopEntered.countDown()
                releaseStop.await(2, TimeUnit.SECONDS)
            },
            stopDeadlineScheduler = RecordingTeardownScheduler { _, action ->
                timeout.set(action)
                RecordingTeardownCancellation {}
            },
            stopTimeoutMs = 1L,
        )
        assertTrue(owner.bind(input))
        assertTrue(owner.beginStart(input))
        owner.finishStart(input, succeeded = true)
        owner.requestStop()

        val stop = stopTasks.removeFirst()
        val worker = Thread(stop)
        worker.start()
        assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
        checkNotNull(timeout.get()).invoke()

        assertTrue(owner.isAbandoned())
        releaseStop.countDown()
        worker.join(2_000)
    }

    private fun awaitThreadWait(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING) {
                return true
            }
            Thread.yield()
        }
        return false
    }

    @Test
    fun `first PCM reports recovery exactly once`() {
        var keepReading = true
        val input = object : StandbyAudioInput {
            override fun start() = Unit
            override fun read(samples: ShortArray): Int {
                samples[0] = 100
                samples[1] = -100
                keepReading = false
                return 2
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        val fixture = fixture(
            setup = StandbyAudioSetup { StandbyAudioSetupResult.Ready(input) },
            recorderAbsent = { keepReading },
        )

        fixture.controller.setEnabled(true)

        assertEquals(1, fixture.available.size)
        assertTrue(fixture.unavailable.isEmpty())
    }

    @Test
    fun `every setup phase uses one shared bounded generation budget`() {
        val setupFailures = listOf(
            StandbyAudioFailureReason.INVALID_BUFFER,
            StandbyAudioFailureReason.CONSTRUCTION,
            StandbyAudioFailureReason.UNINITIALIZED,
        )

        setupFailures.forEach { reason ->
            var attempts = 0
            val fixture = fixture(
                setup = StandbyAudioSetup {
                    attempts++
                    StandbyAudioSetupResult.Failure(reason)
                },
            )

            fixture.controller.setEnabled(true)
            assertEquals("first $reason generation", 1, attempts)
            assertTrue("transient $reason must not report unavailable", fixture.unavailable.isEmpty())
            repeat(2) {
                fixture.scheduled.removeFirst().invoke()
                assertTrue("$reason must stay transient within budget", fixture.unavailable.isEmpty())
            }
            fixture.scheduled.removeFirst().invoke()

            assertEquals(4, attempts)
            assertEquals(
                listOf(StandbyAudioUnavailable(reason, failedGenerations = 4)),
                fixture.unavailable,
            )
            assertTrue(fixture.scheduled.isEmpty())
        }
    }

    @Test
    fun `one shot setup failure recovers without unavailable state`() {
        var attempts = 0
        val recoveredInput = FakeInput()
        val fixture = fixture(
            setup = StandbyAudioSetup {
                attempts++
                if (attempts == 1) {
                    StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.CONSTRUCTION)
                } else {
                    StandbyAudioSetupResult.Ready(recoveredInput)
                }
            },
        )

        fixture.controller.setEnabled(true)
        assertEquals(1, attempts)
        assertTrue(fixture.unavailable.isEmpty())
        fixture.scheduled.removeFirst().invoke()

        assertEquals(2, attempts)
        assertEquals(1, recoveredInput.starts)
        assertEquals(1, recoveredInput.stops)
        assertEquals(1, recoveredInput.releases)
        assertTrue(fixture.unavailable.isEmpty())
        assertTrue(fixture.scheduled.isEmpty())
    }

    @Test
    fun `start failure is retried and reports only after exhaustion`() {
        val inputs = mutableListOf<FakeInput>()
        val fixture = fixture(
            setup = StandbyAudioSetup {
                StandbyAudioSetupResult.Ready(FakeInput(failStart = true).also(inputs::add))
            },
        )

        fixture.controller.setEnabled(true)
        repeat(3) {
            assertTrue(fixture.unavailable.isEmpty())
            fixture.scheduled.removeFirst().invoke()
        }

        assertEquals(4, inputs.size)
        assertTrue(inputs.all { it.starts == 1 && it.stops == 1 && it.releases == 1 })
        assertEquals(
            StandbyAudioUnavailable(StandbyAudioFailureReason.START, failedGenerations = 4),
            fixture.unavailable.single(),
        )
    }

    @Test
    fun `thread launch failure consumes the same bounded budget`() {
        var launches = 0
        val fixture = fixture(
            setup = StandbyAudioSetup { StandbyAudioSetupResult.Ready(FakeInput()) },
            threadLauncher = StandbyThreadLauncher { _, _ -> launches++; false },
        )

        fixture.controller.setEnabled(true)
        repeat(3) {
            assertTrue(fixture.unavailable.isEmpty())
            fixture.scheduled.removeFirst().invoke()
        }

        assertEquals(4, launches)
        assertEquals(
            StandbyAudioUnavailable(StandbyAudioFailureReason.THREAD_LAUNCH, failedGenerations = 4),
            fixture.unavailable.single(),
        )
    }

    @Test
    fun `disable and pause cancel an already scheduled setup retry`() {
        var disableAttempts = 0
        val disabled = fixture(
            setup = StandbyAudioSetup {
                disableAttempts++
                StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.INVALID_BUFFER)
            },
        )
        disabled.controller.setEnabled(true)
        disabled.controller.disable()
        disabled.scheduled.removeFirst().invoke()
        assertEquals(1, disableAttempts)
        assertTrue(disabled.unavailable.isEmpty())

        var paused = false
        var pauseAttempts = 0
        val pausedFixture = fixture(
            setup = StandbyAudioSetup {
                pauseAttempts++
                StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.INVALID_BUFFER)
            },
            paused = { paused },
        )
        pausedFixture.controller.setEnabled(true)
        paused = true
        pausedFixture.scheduled.removeFirst().invoke()
        assertEquals(1, pauseAttempts)
        assertTrue(pausedFixture.unavailable.isEmpty())
    }

    @Test
    fun `recording handoff rejects an already scheduled setup retry`() {
        var attempts = 0
        val fixture = fixture(
            setup = StandbyAudioSetup {
                attempts++
                StandbyAudioSetupResult.Failure(StandbyAudioFailureReason.UNINITIALIZED)
            },
        )
        fixture.controller.setEnabled(true)

        val claim = fixture.controller.beginRecording()
        assertTrue(claim.admitted)
        assertFalse(claim.release != null)
        fixture.scheduled.removeFirst().invoke()

        assertEquals(1, attempts)
        assertTrue(fixture.unavailable.isEmpty())
    }

    @Test
    fun `queued generation rechecks terminal admission before creating AudioRecord`() {
        var allowed = true
        var setupCalls = 0
        var queuedTask: (() -> Unit)? = null
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupCalls++
                StandbyAudioSetupResult.Ready(FakeInput())
            },
            canStart = { allowed },
            threadLauncher = StandbyThreadLauncher { _, task ->
                queuedTask = task
                true
            },
        )
        fixture.controller.setEnabled(true)

        allowed = false
        checkNotNull(queuedTask).invoke()

        assertEquals(0, setupCalls)
    }

    @Test
    fun `admission revoked during setup releases input without starting it`() {
        var allowed = true
        val input = FakeInput()
        val fixture = fixture(
            setup = StandbyAudioSetup {
                allowed = false
                StandbyAudioSetupResult.Ready(input)
            },
            canStart = { allowed },
        )

        fixture.controller.setEnabled(true)

        assertEquals(0, input.starts)
        assertEquals(1, input.releases)
    }

    @Test
    fun `native create return revoked by quarantine retains exact input without cleanup`() {
        val input = FakeInput()
        var setupCalls = 0
        val processGate = RecorderQuarantineAdmissionGate()
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupCalls++
                processGate.close()
                StandbyAudioSetupResult.Ready(input)
            },
            nativeProcessGate = RecorderStandbyNativeProcessGate(processGate),
        )

        fixture.controller.setEnabled(true)
        fixture.controller.setEnabled(true)

        assertEquals(1, setupCalls)
        assertEquals(0, input.starts)
        assertEquals(0, input.stops)
        assertEquals(0, input.releases)
        assertEquals(1, fixture.retained.size)
        assertTrue(fixture.retained.single().input === input)
        assertEquals(1L, fixture.retained.single().terminationOwner.generationId)
        assertTrue(fixture.retained.single().terminationOwner.isAbandoned())
        assertEquals(1, fixture.unsafeNative.size)
        // The revoked generation completed logically, so REC cannot inherit a stale release latch.
        val claim = fixture.controller.beginRecording()
        assertTrue(claim.admitted)
        assertEquals(null, claim.release)
    }

    @Test
    fun `native start return revoked by quarantine retains started input without stop or release`() {
        val processGate = RecorderQuarantineAdmissionGate()
        val input = object : StandbyAudioInput {
            var starts = 0
            var stops = 0
            var releases = 0
            override fun start() {
                starts++
                processGate.close()
            }
            override fun read(samples: ShortArray): Int = error("read is not admitted")
            override fun stop() { stops++ }
            override fun release() { releases++ }
        }
        val fixture = fixture(
            setup = StandbyAudioSetup { StandbyAudioSetupResult.Ready(input) },
            nativeProcessGate = RecorderStandbyNativeProcessGate(processGate),
        )

        fixture.controller.setEnabled(true)
        fixture.controller.setEnabled(true)

        assertEquals(1, input.starts)
        assertEquals(0, input.stops)
        assertEquals(0, input.releases)
        assertEquals(1, fixture.retained.size)
        assertTrue(fixture.retained.single().input === input)
        assertTrue(fixture.retained.single().terminationOwner.isAbandoned())
        assertEquals(1, fixture.unsafeNative.size)
    }

    @Test
    fun `hung standby stop deadline quarantines once and makes late return inert`() {
        val processGate = me.hletrd.telecampro.video.RecorderQuarantineAdmissionGate()
        val processOwner = Any()
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val deadline = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        val stopThreads = mutableListOf<Thread>()
        var setupCalls = 0
        val input = object : StandbyAudioInput {
            var starts = 0
            var stops = 0
            var releases = 0

            override fun start() { starts++ }
            override fun read(samples: ShortArray): Int {
                readEntered.countDown()
                releaseRead.await(5, TimeUnit.SECONDS)
                return -1
            }
            override fun stop() {
                stops++
                stopEntered.countDown()
                releaseStop.await(5, TimeUnit.SECONDS)
                stopReturned.countDown()
            }
            override fun release() { releases++ }
        }
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupCalls++
                StandbyAudioSetupResult.Ready(input)
            },
            recorderAbsent = { true },
            canStart = { !processGate.isQuarantined() },
            threadLauncher = StandbyThreadLauncher { name, task ->
                Thread(
                    {
                        try {
                            task()
                        } finally {
                            workerFinished.countDown()
                        }
                    },
                    name,
                ).apply { isDaemon = true }.start()
                true
            },
            reserveProcessAdmission = {
                processGate.reserveStandby(processOwner)?.let { token ->
                    { processGate.finishStandby(token) }
                }
            },
            nativeProcessGate = RecorderStandbyNativeProcessGate(processGate),
            stopDispatcher = StandbyStopDispatcher { task ->
                stopThreads += Thread(task, "blocked-standby-stop").apply {
                    isDaemon = true
                    start()
                }
            },
            stopDeadlineScheduler = RecordingTeardownScheduler { _, action ->
                deadline.set(action)
                RecordingTeardownCancellation { deadline.compareAndSet(action, null) }
            },
            stopTimeoutMs = 1L,
        )

        fixture.controller.setEnabled(true)
        assertTrue(readEntered.await(2, TimeUnit.SECONDS))
        val claim = fixture.controller.beginRecording()
        assertTrue(claim.admitted)
        assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
        val logicalRelease = checkNotNull(claim.release)
        assertFalse(logicalRelease.await(20, TimeUnit.MILLISECONDS))

        checkNotNull(deadline.getAndSet(null)).invoke()

        assertTrue(logicalRelease.await(2, TimeUnit.SECONDS))
        assertEquals(1, fixture.retained.size)
        assertTrue(fixture.retained.single().input === input)
        assertTrue(fixture.retained.single().terminationOwner.isAbandoned())
        assertEquals(1, fixture.unsafeNative.size)
        assertTrue(processGate.isQuarantined())
        assertEquals(null, processGate.snapshot(Any()))
        assertEquals(null, processGate.reserveStandby(Any()))

        // Pause/replacement/re-enable cannot create a second microphone after terminal quarantine.
        fixture.controller.disable()
        fixture.controller.setEnabled(true)
        assertEquals(1, setupCalls)
        assertEquals(1, input.starts)
        assertEquals(1, input.stops)

        // A native stop and read returning after the deadline cannot authorize input release.
        releaseStop.countDown()
        assertTrue(stopReturned.await(2, TimeUnit.SECONDS))
        releaseRead.countDown()
        assertTrue(workerFinished.await(2, TimeUnit.SECONDS))
        stopThreads.forEach { it.join(2_000) }
        assertEquals(0, input.releases)
        assertEquals(1, fixture.retained.size)
        assertEquals(1, fixture.unsafeNative.size)
    }

    @Test
    fun `disable clears standby intent before any generation starts`() {
        var setupCalls = 0
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupCalls++
                StandbyAudioSetupResult.Ready(FakeInput())
            },
        )

        fixture.controller.setEnabled(false)

        assertEquals(0, setupCalls)
        assertTrue(fixture.scheduled.isEmpty())
        assertTrue(fixture.unavailable.isEmpty())
    }

    @Test
    fun `aborted recording admission restarts the standby meter immediately`() {
        var setupCalls = 0
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupCalls++
                StandbyAudioSetupResult.Ready(FakeInput())
            },
        )
        fixture.controller.setEnabled(true)
        assertEquals(1, setupCalls)

        assertTrue(fixture.controller.beginRecording().admitted)
        fixture.controller.abortRecording()

        // The claim never changed intent, so the abort restores it and re-arms a fresh generation.
        assertEquals(2, setupCalls)
        assertTrue(fixture.unavailable.isEmpty())
    }

    @Test
    fun `finished recording rechecks intent instead of restoring it`() {
        var setupCalls = 0
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupCalls++
                StandbyAudioSetupResult.Ready(FakeInput())
            },
        )
        fixture.controller.setEnabled(true)
        assertTrue(fixture.controller.beginRecording().admitted)

        fixture.controller.finishRecording()

        // beginRecording consumed the standby intent; finish only releases recorder admission, so
        // the restart recheck finds no wanted meter until arming re-enables it explicitly.
        assertEquals(1, setupCalls)
        fixture.controller.setEnabled(true)
        assertEquals(2, setupCalls)
    }

    @Test
    fun `throwing audio setup charges the construction budget like a failure result`() {
        var attempts = 0
        val fixture = fixture(
            setup = StandbyAudioSetup {
                attempts++
                error("injected setup throw")
            },
        )

        fixture.controller.setEnabled(true)
        repeat(3) {
            assertTrue(fixture.unavailable.isEmpty())
            fixture.scheduled.removeFirst().invoke()
        }

        assertEquals(4, attempts)
        assertEquals(
            StandbyAudioUnavailable(StandbyAudioFailureReason.CONSTRUCTION, failedGenerations = 4),
            fixture.unavailable.single(),
        )
    }

    @Test
    fun `throwing read ends the generation as a terminal dead route`() {
        class ThrowingReadInput : StandbyAudioInput {
            var stops = 0
            var releases = 0
            override fun start() = Unit
            override fun read(samples: ShortArray): Int = error("injected read throw")
            override fun stop() { stops++ }
            override fun release() { releases++ }
        }

        val inputs = mutableListOf<ThrowingReadInput>()
        val fixture = fixture(
            setup = StandbyAudioSetup {
                StandbyAudioSetupResult.Ready(ThrowingReadInput().also(inputs::add))
            },
            recorderAbsent = { true },
        )

        fixture.controller.setEnabled(true)
        repeat(3) {
            assertTrue(fixture.unavailable.isEmpty())
            fixture.scheduled.removeFirst().invoke()
        }

        assertEquals(4, inputs.size)
        assertTrue(inputs.all { it.stops == 1 && it.releases == 1 })
        assertEquals(
            StandbyAudioUnavailable(StandbyAudioFailureReason.TERMINAL_READ, failedGenerations = 4),
            fixture.unavailable.single(),
        )
    }

    @Test
    fun `zero length read retries the loop without ending the generation`() {
        var reads = 0
        var keepReading = true
        val input = object : StandbyAudioInput {
            override fun start() = Unit
            override fun read(samples: ShortArray): Int {
                reads++
                return if (reads == 1) {
                    0 // a transient empty read must spin the loop, not kill the route
                } else {
                    samples[0] = 120
                    keepReading = false
                    1
                }
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        val fixture = fixture(
            setup = StandbyAudioSetup { StandbyAudioSetupResult.Ready(input) },
            recorderAbsent = { keepReading },
        )

        fixture.controller.setEnabled(true)

        assertEquals(2, reads)
        assertEquals(1, fixture.available.size)
        assertTrue(fixture.unavailable.isEmpty())
        assertTrue(fixture.scheduled.isEmpty())
    }

    @Test
    fun `negative read on a wanted route is terminal and consumes the budget`() {
        val fixture = fixture(
            setup = StandbyAudioSetup {
                StandbyAudioSetupResult.Ready(object : StandbyAudioInput {
                    override fun start() = Unit
                    override fun read(samples: ShortArray): Int = -3
                    override fun stop() = Unit
                    override fun release() = Unit
                })
            },
            recorderAbsent = { true },
        )

        fixture.controller.setEnabled(true)
        repeat(3) {
            assertTrue(fixture.unavailable.isEmpty())
            fixture.scheduled.removeFirst().invoke()
        }

        assertEquals(
            StandbyAudioUnavailable(StandbyAudioFailureReason.TERMINAL_READ, failedGenerations = 4),
            fixture.unavailable.single(),
        )
    }

    @Test
    fun `negative wake-up after REC handoff stops cleanly without failure charge`() {
        var claim: StandbyMeterOwnership.RecordingClaim<*>? = null
        var controllerRef: StandbyAudioController? = null
        val input = object : StandbyAudioInput {
            var stops = 0
            var releases = 0
            override fun start() = Unit
            override fun read(samples: ShortArray): Int {
                // REC claims the mic while this read blocks; the negative wake-up that follows is
                // the handoff, not a dead route — no budget charge, no retry.
                claim = checkNotNull(controllerRef).beginRecording()
                return -1
            }
            override fun stop() { stops++ }
            override fun release() { releases++ }
        }
        val fixture = fixture(
            setup = StandbyAudioSetup { StandbyAudioSetupResult.Ready(input) },
            recorderAbsent = { true },
        )
        controllerRef = fixture.controller

        fixture.controller.setEnabled(true)

        assertTrue(checkNotNull(claim).admitted)
        assertEquals(1, input.stops)
        assertEquals(1, input.releases)
        assertTrue(fixture.unavailable.isEmpty())
        assertTrue(fixture.scheduled.isEmpty())
    }

    @Test
    fun `re-enable during a live generation restarts one fresh generation after completion`() {
        var setupCalls = 0
        var keepReading = true
        var controllerRef: StandbyAudioController? = null
        val firstInput = object : StandbyAudioInput {
            override fun start() = Unit
            override fun read(samples: ShortArray): Int {
                // The user toggles the meter while this generation is live: the reserve sees an
                // active owner and latches a restart instead of racing a second AudioRecord.
                checkNotNull(controllerRef).setEnabled(true)
                keepReading = false
                samples[0] = 40
                return 1
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        val fixture = fixture(
            setup = StandbyAudioSetup {
                setupCalls++
                StandbyAudioSetupResult.Ready(if (setupCalls == 1) firstInput else FakeInput())
            },
            recorderAbsent = { keepReading },
        )
        controllerRef = fixture.controller

        fixture.controller.setEnabled(true)

        assertEquals(2, setupCalls)
        assertTrue(fixture.unavailable.isEmpty())
    }

    @Test
    fun `primary constructor defaults admit and complete a generation standalone`() {
        // Omits reserveProcessAdmission/nativeProcessGate/processBusyRetryFallback so the
        // production defaults themselves execute: unguarded admission (with its no-op release),
        // direct native acquisition, and the thread-backed fallback constructed at init.
        val input = FakeInput()
        var levels = 0
        val controller = StandbyAudioController(
            audioGain = { 1f },
            onLevels = { levels++ },
            canStart = { true },
            recorderAbsent = { false },
            isPaused = { false },
            permissionGranted = { true },
            audioSetup = StandbyAudioSetup { StandbyAudioSetupResult.Ready(input) },
            threadLauncher = StandbyThreadLauncher { _, task -> task(); true },
            retryScheduler = StandbyRetryScheduler { _, _ -> true },
            onAvailable = {},
            onUnavailable = {},
        )

        controller.setEnabled(true)

        assertEquals(1, input.starts)
        assertEquals(1, input.stops)
        assertEquals(1, input.releases)
        assertTrue(levels >= 1)
    }

    @Test
    fun `default process-busy fallback thread delivers the retry after the backoff`() {
        val processBusy = AtomicBoolean(true)
        val setupCalls = AtomicInteger()
        // One zero-level per completed generation: the busy probe, then the real run.
        val zeroLevels = CountDownLatch(2)
        val input = FakeInput()
        val controller = StandbyAudioController(
            audioGain = { 1f },
            onLevels = { if (it.rms.isEmpty()) zeroLevels.countDown() },
            canStart = { true },
            recorderAbsent = { false },
            isPaused = { false },
            permissionGranted = { true },
            audioSetup = StandbyAudioSetup {
                setupCalls.incrementAndGet()
                StandbyAudioSetupResult.Ready(input)
            },
            threadLauncher = StandbyThreadLauncher { _, task -> task(); true },
            // The main lane is dead; only the default named fallback thread can carry the retry.
            retryScheduler = StandbyRetryScheduler { _, _ -> false },
            onAvailable = {},
            onUnavailable = {},
            reserveProcessAdmission = { if (processBusy.get()) null else ({}) },
        )

        controller.setEnabled(true)
        assertEquals(0, setupCalls.get())
        processBusy.set(false)

        assertTrue(zeroLevels.await(5, TimeUnit.SECONDS))
        assertEquals(1, setupCalls.get())
        assertEquals(1, input.starts)
        assertEquals(1, input.releases)
    }

    @Test
    fun `interrupting the fallback thread abandons its queued retry`() {
        awaitNoFallbackThread()
        val fallbackRetryObserved = AtomicInteger()
        val setupCalls = AtomicInteger()
        val controller = StandbyAudioController(
            audioGain = { 1f },
            onLevels = {},
            canStart = { true },
            recorderAbsent = { false },
            isPaused = {
                // The retry closure checks pause first; seeing it from the fallback thread proves
                // the sleep completed and the task ran — the interrupt must prevent exactly that.
                if (Thread.currentThread().name == FALLBACK_THREAD_NAME) {
                    fallbackRetryObserved.incrementAndGet()
                }
                false
            },
            permissionGranted = { true },
            audioSetup = StandbyAudioSetup {
                setupCalls.incrementAndGet()
                StandbyAudioSetupResult.Ready(FakeInput())
            },
            threadLauncher = StandbyThreadLauncher { _, task -> task(); true },
            retryScheduler = StandbyRetryScheduler { _, _ -> false },
            onAvailable = {},
            onUnavailable = {},
            reserveProcessAdmission = { null },
        )

        controller.setEnabled(true)
        val fallback = checkNotNull(findFallbackThread()) {
            "the default fallback thread must be parked in its backoff sleep"
        }

        fallback.interrupt()
        fallback.join(5_000)

        assertFalse(fallback.isAlive)
        assertEquals(0, fallbackRetryObserved.get())
        assertEquals(0, setupCalls.get())
    }

    private fun findFallbackThread(): Thread? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            Thread.getAllStackTraces().keys
                .firstOrNull { it.name == FALLBACK_THREAD_NAME && it.isAlive }
                ?.let { return it }
            Thread.sleep(1)
        }
        return null
    }

    private fun awaitNoFallbackThread() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val alive = Thread.getAllStackTraces().keys
                .filter { it.name == FALLBACK_THREAD_NAME && it.isAlive }
            if (alive.isEmpty()) return
            alive.forEach { it.join(50) }
        }
        error("a previous test's fallback thread never exited")
    }
}
