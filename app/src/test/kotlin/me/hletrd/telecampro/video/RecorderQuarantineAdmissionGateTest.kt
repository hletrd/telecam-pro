package me.hletrd.telecampro.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import me.hletrd.telecampro.storage.OrphanDisposition
import me.hletrd.telecampro.storage.PendingJournalState
import me.hletrd.telecampro.storage.PendingProbe
import me.hletrd.telecampro.storage.markCompletionWithRetry
import me.hletrd.telecampro.storage.orphanDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderQuarantineAdmissionGateTest {

    @Test
    fun `recorder local close revokes an entered return and rejects every later phase`() {
        val gate = RecorderNativeOperationGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicReference<RecorderNativeOperationResult<Unit>>()
        val worker = Thread {
            returned.set(
                gate.run {
                    entered.countDown()
                    release.await()
                },
            )
        }

        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(gate.close())
        assertFalse(gate.isOpen())
        var laterPhase = false
        assertEquals(
            RecorderNativeOperationResult.Rejected,
            gate.run { laterPhase = true },
        )
        assertFalse(laterPhase)

        release.countDown()
        worker.join(5_000)
        val outcome = returned.get() as RecorderNativeOperationResult.Returned
        assertFalse(outcome.stillOpen)
        assertTrue(outcome.result.isSuccess)
        assertFalse(gate.close())
    }

    @Test
    fun `cleanup requires both successful native effect and open admission`() {
        val failure = IllegalStateException("native release failed")
        assertEquals(
            NativeCleanupOutcome.Completed,
            nativeCleanupOutcome(
                RecorderNativeOperationResult.Returned(Result.success(Unit), stillOpen = true),
            ),
        )
        assertEquals(
            NativeCleanupOutcome.Revoked,
            nativeCleanupOutcome(
                RecorderNativeOperationResult.Returned(Result.success(Unit), stillOpen = false),
            ),
        )
        assertEquals(
            NativeCleanupOutcome.Revoked,
            nativeCleanupOutcome(RecorderNativeOperationResult.Rejected),
        )
        val failed = nativeCleanupOutcome(
            RecorderNativeOperationResult.Returned(Result.failure(failure), stillOpen = true),
        ) as NativeCleanupOutcome.Failed
        assertTrue(failed.cause === failure)
    }

    @Test
    fun `every production native owner failure retains it and all later owners`() {
        val productionOrder = listOf(RecorderNativeOwnerOperation.AUDIO_INPUT_STOP) +
            RECORDER_POST_DRAIN_PRE_MUXER_NATIVE_OWNERS + RECORDER_POST_DRAIN_NATIVE_OWNERS
        assertEquals(RecorderNativeOwnerOperation.entries, productionOrder)

        productionOrder.forEachIndexed { failedIndex, failedOwner ->
            val released = mutableListOf<RecorderNativeOwnerOperation>()
            val attempted = mutableListOf<RecorderNativeOwnerOperation>()
            val firstFailure = runRecorderNativeOwnerSequence(productionOrder) { owner ->
                attempted += owner
                if (owner == failedOwner) {
                    // Models nativeCleanup's admitted throwing operation: it is unproved, so the
                    // actual owner and every later owner must remain strongly retained.
                    false
                } else {
                    released += owner
                    true
                }
            }

            assertEquals(failedOwner, firstFailure)
            assertEquals(productionOrder.take(failedIndex + 1), attempted)
            assertEquals(productionOrder.take(failedIndex), released)
            assertEquals(productionOrder.drop(failedIndex), productionOrder - released.toSet())

            val processAdmission = RecorderQuarantineAdmissionGate()
            val token = checkNotNull(processAdmission.snapshot(Any()))
            assertTrue(processAdmission.publish(token) { true })
            assertTrue(processAdmission.close())
            assertTrue(processAdmission.isQuarantined())
            assertTrue(processAdmission.snapshot(Any()) == null)
        }
    }

    @Test
    fun `all production native owners release through the terminal success return`() {
        val productionOrder = listOf(RecorderNativeOwnerOperation.AUDIO_INPUT_STOP) +
            RECORDER_POST_DRAIN_PRE_MUXER_NATIVE_OWNERS + RECORDER_POST_DRAIN_NATIVE_OWNERS
        val released = mutableListOf<RecorderNativeOwnerOperation>()

        assertEquals(
            null,
            runRecorderNativeOwnerSequence(productionOrder) { owner ->
                released += owner
                true
            },
        )
        assertEquals(productionOrder, released)
    }

    @Test
    fun `frozen storage validates then marks complete before successful publish`() {
        val events = mutableListOf<String>()
        val result = completeFrozenRecordingStorage(
            frozenStorage("clip", validation = FinalizedRecordingValidation.SKIPPED),
            RecordingStorageEffects(
                validateVideoTrack = { events += "validate:$it"; true },
                markComplete = { events += "complete:$it"; true },
                publish = { events += "publish:$it"; true },
                delete = { events += "delete:$it" },
            ),
        )

        assertTrue(result.saved)
        assertEquals(null, result.error)
        assertEquals(listOf("validate:clip", "complete:clip", "publish:clip"), events)
    }

    @Test
    fun `exhausted video COMPLETE retains valid row without publish delete or saved verdict`() {
        val events = mutableListOf<String>()
        val result = completeFrozenRecordingStorage(
            frozenStorage("clip"),
            RecordingStorageEffects(
                validateVideoTrack = { events += "validate:$it"; true },
                markComplete = {
                    val marker = markCompletionWithRetry(
                        maxAttempts = 3,
                        commit = { events += "marker:$it"; false },
                    )
                    marker.durable
                },
                publish = { events += "publish:$it"; true },
                delete = { events += "delete:$it" },
            ),
        )

        assertFalse(result.saved)
        assertEquals(null, result.error)
        assertEquals(
            VideoRecorder.StorageDisposition.RETAINED_MARKER_UNAVAILABLE,
            result.storageDisposition,
        )
        assertEquals(listOf("marker:clip", "marker:clip", "marker:clip"), events)
        assertEquals(
            OrphanDisposition.ADOPT,
            orphanDisposition(PendingJournalState.REGISTERED, PendingProbe.VALID),
        )
    }

    @Test
    fun `failed frozen validation deletes invalid row and reports a concrete failure`() {
        val events = mutableListOf<String>()
        val result = completeFrozenRecordingStorage(
            frozenStorage("invalid", validation = FinalizedRecordingValidation.SKIPPED),
            RecordingStorageEffects(
                validateVideoTrack = { events += "validate:$it"; false },
                markComplete = { events += "complete:$it"; true },
                publish = { events += "publish:$it"; true },
                delete = { events += "delete:$it" },
            ),
        )

        assertFalse(result.saved)
        assertEquals("Finalized video track validation failed", result.error?.message)
        assertEquals(listOf("validate:invalid", "delete:invalid"), events)
    }

    @Test
    fun `failed frozen validation preserves earlier video failure`() {
        val original = IllegalStateException("codec failed")
        val deleted = mutableListOf<String>()
        val result = completeFrozenRecordingStorage(
            frozenStorage(
                "failed",
                failure = original,
                validation = FinalizedRecordingValidation.SKIPPED,
            ),
            RecordingStorageEffects(
                validateVideoTrack = { false },
                markComplete = { true },
                publish = { true },
                delete = { deleted += it },
            ),
        )

        assertFalse(result.saved)
        assertSame(original, result.error)
        assertEquals(listOf("failed"), deleted)
    }

    @Test
    fun `missing output skips validation and deletion while producing truthful failure`() {
        var effectsCalled = false
        val result = completeFrozenRecordingStorage(
            frozenStorage<String>(null, validation = FinalizedRecordingValidation.SKIPPED),
            RecordingStorageEffects(
                validateVideoTrack = { effectsCalled = true; true },
                markComplete = { effectsCalled = true; true },
                publish = { effectsCalled = true; true },
                delete = { effectsCalled = true },
            ),
        )

        assertFalse(result.saved)
        assertEquals("Finalized video track validation failed", result.error?.message)
        assertFalse(effectsCalled)
    }

    @Test
    fun `incomplete native verdict deletes its registered pending row`() {
        val deleted = mutableListOf<String>()
        val result = completeFrozenRecordingStorage(
            frozenStorage("empty", muxerStarted = false),
            RecordingStorageEffects(
                validateVideoTrack = { true },
                markComplete = { true },
                publish = { true },
                delete = { deleted += it },
            ),
        )

        assertFalse(result.saved)
        assertEquals(null, result.error)
        assertEquals(listOf("empty"), deleted)
    }

    private fun <T> frozenStorage(
        output: T?,
        muxerStarted: Boolean = true,
        failure: Throwable? = null,
        validation: FinalizedRecordingValidation = FinalizedRecordingValidation.NOT_REQUIRED,
    ) = FrozenRecordingStorage(
        outputUri = output,
        muxerStarted = muxerStarted,
        wroteVideoSample = true,
        failure = failure,
        finalizedValidation = validation,
    )

    private fun awaitQuarantine(gate: RecorderQuarantineAdmissionGate): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!gate.isQuarantined() && System.nanoTime() < deadline) Thread.yield()
        return gate.isQuarantined()
    }

    @Test
    fun `quarantine invalidates snapshots and rejects every later commit`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = checkNotNull(gate.snapshot(Any()))
        var committed = false

        assertTrue(gate.close())

        assertFalse(gate.isCurrent(token))
        assertFalse(gate.commit(token) { committed = true })
        assertFalse(committed)
        assertTrue(gate.isQuarantined())
        assertFalse(gate.close())
        assertTrue(gate.snapshot(Any()) == null)
    }

    @Test
    fun `a current pending token commits exactly once`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = checkNotNull(gate.snapshot(Any()))
        var commits = 0

        assertTrue(gate.commit(token) { commits++ })

        assertEquals(1, commits)
        // The commit is a guarded execution, not a consumption: the lease stays current.
        assertTrue(gate.isCurrent(token))
        assertTrue(gate.commit(token) { commits++ })
        assertEquals(2, commits)
    }

    @Test
    fun `publication and quarantine race has exactly one linearized winner`() {
        repeat(100) {
            val gate = RecorderQuarantineAdmissionGate()
            val token = checkNotNull(gate.snapshot(Any()))
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val published = AtomicBoolean(false)
            val publicationAccepted = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            val publisher = Thread {
                ready.countDown()
                go.await()
                publicationAccepted.set(gate.publish(token) {
                    published.set(true)
                    true
                })
            }
            val closer = Thread {
                ready.countDown()
                go.await()
                closed.set(gate.close())
            }

            publisher.start()
            closer.start()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            publisher.join(5_000)
            closer.join(5_000)

            assertTrue(closed.get())
            assertTrue(publicationAccepted.get() == published.get())
            // Publication may linearize first, but it can never execute after the terminal close.
            if (!published.get()) assertFalse(gate.isCurrent(token))
            var lateCommit = false
            assertFalse(gate.commit(token) { lateCommit = true })
            assertFalse(lateCommit)
        }
    }

    @Test
    fun `pending and active recorder leases are process exclusive until abort or strict finish`() {
        val gate = RecorderQuarantineAdmissionGate()
        val owner = Any()
        val first = checkNotNull(gate.snapshot(owner))
        assertTrue(gate.snapshot(owner) == null)

        gate.abandonPending(first)
        val second = checkNotNull(gate.snapshot(owner))
        var published = false
        assertTrue(gate.publish(second) {
            published = true
            true
        })
        assertTrue(published)
        assertTrue(gate.snapshot(owner) == null)

        gate.finish(second)
        assertTrue(gate.snapshot(owner) != null)
    }

    @Test
    fun `same owner may hand standby to REC while foreign owners stay excluded`() {
        val gate = RecorderQuarantineAdmissionGate()
        val owner = Any()
        val foreignOwner = Any()
        val standby = checkNotNull(gate.reserveStandby(owner))

        assertTrue(gate.reserveStandby(foreignOwner) == null)
        assertTrue(gate.snapshot(foreignOwner) == null)
        val recording = checkNotNull(gate.snapshot(owner))
        assertTrue(gate.reserveStandby(owner) == null)

        gate.finishStandby(standby)
        assertTrue(gate.reserveStandby(foreignOwner) == null)
        assertTrue(gate.publish(recording) { true })
        assertTrue(gate.reserveStandby(foreignOwner) == null)

        gate.finish(recording)
        assertTrue(gate.reserveStandby(foreignOwner) != null)
    }

    @Test
    fun `terminal close converges while a native lease never returns`() {
        val gate = RecorderQuarantineAdmissionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acquisitionAccepted = AtomicBoolean(false)
        val acquisitionDone = AtomicBoolean(false)
        val closeDone = AtomicBoolean(false)
        val acquirer = Thread {
            acquisitionAccepted.set(gate.runNativeIfSafe {
                entered.countDown()
                release.await()
                acquisitionDone.set(true)
            })
        }
        val closer = Thread {
            gate.close()
            closeDone.set(true)
        }

        acquirer.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        closer.start()
        // Terminal ownership/admission converges without waiting for the native block whose hang is
        // exactly why quarantine exists.
        assertTrue(awaitQuarantine(gate))
        closer.join(1_000)
        assertTrue(closeDone.get())
        assertFalse(acquisitionDone.get())
        assertFalse(gate.awaitNativeAcquisitionsDrained(25, TimeUnit.MILLISECONDS))
        var lateAcquisition = false
        assertFalse(gate.runNativeIfSafe { lateAcquisition = true })
        assertFalse(lateAcquisition)
        assertFalse(gate.commit(UnsafeRecorderAdmissionToken(1L, this)) { })

        // A later native return remains revoked and merely makes bounded observation report drained.
        release.countDown()
        acquirer.join(5_000)
        assertTrue(acquisitionDone.get())
        assertFalse(acquisitionAccepted.get())
        assertTrue(gate.awaitNativeAcquisitionsDrained(1, TimeUnit.SECONDS))
    }

    @Test
    fun `typed native result distinguishes pre-entry rejection from revoked return`() {
        val gate = RecorderQuarantineAdmissionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<NativeAcquisitionResult>()
        val worker = Thread {
            result.set(
                gate.runNativeWithResult {
                    entered.countDown()
                    release.await()
                },
            )
        }

        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(gate.close())
        release.countDown()
        worker.join(5_000)

        assertEquals(NativeAcquisitionResult.RETURNED_REVOKED, result.get())
        var rejectedBlockRan = false
        assertEquals(
            NativeAcquisitionResult.REJECTED,
            gate.runNativeWithResult { rejectedBlockRan = true },
        )
        assertFalse(rejectedBlockRan)
    }

    @Test
    fun `native create return cannot race exact owner binding or admit a replacement`() {
        val gate = RecorderQuarantineAdmissionGate()
        val input = Any()
        val publicationEntered = CountDownLatch(1)
        val allowPublication = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val revoked = AtomicBoolean(false)
        val result = AtomicReference<NativeAcquisitionPublicationResult<Any>>()
        val creator = Thread {
            result.set(
                gate.runNativeWithPublication(
                    block = { input },
                    publicationOwner = { returned ->
                        assertSame(input, returned)
                        publicationEntered.countDown()
                        allowPublication.await()
                        NativeAcquisitionPublicationOwner(input) { revoked.set(true) }
                    },
                ),
            )
        }
        val closer = Thread {
            gate.close()
            closeDone.countDown()
        }
        val replacementEntered = AtomicBoolean(false)
        val replacementResult = AtomicReference<NativeAcquisitionResult>()
        val replacement = Thread {
            replacementResult.set(gate.runNativeWithResult { replacementEntered.set(true) })
        }

        creator.start()
        assertTrue(publicationEntered.await(5, TimeUnit.SECONDS))
        closer.start()
        assertFalse(closeDone.await(25, TimeUnit.MILLISECONDS))

        allowPublication.countDown()
        creator.join(5_000)
        closer.join(5_000)
        replacement.start()
        replacement.join(5_000)

        assertSame(input, result.get().value)
        assertTrue(revoked.get())
        assertFalse(replacementEntered.get())
        assertEquals(NativeAcquisitionResult.REJECTED, replacementResult.get())
    }

    @Test
    fun `native start return publishes under gate before terminal revocation`() {
        val gate = RecorderQuarantineAdmissionGate()
        val input = Any()
        val revoked = AtomicBoolean(false)
        val created = gate.runNativeWithPublication(
            block = { input },
            publicationOwner = { NativeAcquisitionPublicationOwner(input) { revoked.set(true) } },
        )
        val token = checkNotNull(created.token)
        val nativeReturned = CountDownLatch(1)
        val allowStartPublication = CountDownLatch(1)
        val publicationEntered = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val startResult = AtomicReference<NativeAcquisitionResult>()
        val starter = Thread {
            startResult.set(
                gate.runPublishedNativeWithResult(
                    token = token,
                    block = { nativeReturned.countDown() },
                    publish = {
                        publicationEntered.countDown()
                        allowStartPublication.await()
                        published.set(true)
                    },
                ),
            )
        }
        val closer = Thread {
            gate.close()
            closeDone.countDown()
        }

        starter.start()
        assertTrue(nativeReturned.await(5, TimeUnit.SECONDS))
        assertTrue(publicationEntered.await(5, TimeUnit.SECONDS))
        closer.start()
        assertFalse(closeDone.await(25, TimeUnit.MILLISECONDS))

        allowStartPublication.countDown()
        starter.join(5_000)
        closer.join(5_000)

        assertEquals(NativeAcquisitionResult.RETURNED_CURRENT, startResult.get())
        assertTrue(published.get())
        assertTrue(revoked.get())
        var laterNative = false
        assertFalse(gate.runNativeIfSafe { laterNative = true })
        assertFalse(laterNative)
    }

    @Test
    fun `stop timeout quarantine retains before abandon and excludes competing acquisition`() {
        val gate = RecorderQuarantineAdmissionGate()
        val input = Any()
        val revokeEntered = CountDownLatch(1)
        val allowAbandon = CountDownLatch(1)
        val abandoned = AtomicBoolean(false)
        val created = gate.runNativeWithPublication(
            block = { input },
            publicationOwner = {
                NativeAcquisitionPublicationOwner(input) {
                    // close() has already transferred this owner into its process-long retained set
                    // while still holding the gate. Model the controller's abandonment callback.
                    revokeEntered.countDown()
                    allowAbandon.await()
                    abandoned.set(true)
                }
            },
        )
        val token = checkNotNull(created.token)
        val timeoutDone = CountDownLatch(1)
        val timeout = Thread {
            assertTrue(gate.quarantineNativePublication(token))
            timeoutDone.countDown()
        }
        val replacementEntered = AtomicBoolean(false)
        val replacementResult = AtomicReference<NativeAcquisitionResult>()
        val replacement = Thread {
            replacementResult.set(gate.runNativeWithResult { replacementEntered.set(true) })
        }

        timeout.start()
        assertTrue(revokeEntered.await(5, TimeUnit.SECONDS))
        replacement.start()
        assertFalse(timeoutDone.await(25, TimeUnit.MILLISECONDS))
        assertFalse(abandoned.get())

        allowAbandon.countDown()
        timeout.join(5_000)
        replacement.join(5_000)

        assertTrue(abandoned.get())
        assertFalse(replacementEntered.get())
        assertEquals(NativeAcquisitionResult.REJECTED, replacementResult.get())
    }

    @Test
    fun `published native creator rejects after close without entering`() {
        val gate = RecorderQuarantineAdmissionGate()
        assertTrue(gate.close())
        var entered = false

        val result = gate.runNativeWithPublication(
            block = { entered = true; Any() },
            publicationOwner = { NativeAcquisitionPublicationOwner(it) {} },
        )

        assertEquals(NativeAcquisitionResult.REJECTED, result.acquisition)
        assertEquals(null, result.value)
        assertEquals(null, result.token)
        assertFalse(entered)
    }

    @Test
    fun `published native creator failure drains admission and permits retry`() {
        val gate = RecorderQuarantineAdmissionGate()
        val failure = IllegalStateException("create failed")

        val thrown = runCatching {
            gate.runNativeWithPublication<Any>(
                block = { throw failure },
                publicationOwner = { NativeAcquisitionPublicationOwner(it) {} },
            )
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertTrue(gate.awaitNativeAcquisitionsDrained(1, TimeUnit.SECONDS))
        val retry = gate.runNativeWithPublication(block = { "no owner" }, publicationOwner = { null })
        assertEquals(NativeAcquisitionResult.RETURNED_CURRENT, retry.acquisition)
        assertEquals("no owner", retry.value)
        assertEquals(null, retry.token)
    }

    @Test
    fun `stale publication rejects native work and creator failure leaves token current`() {
        val gate = RecorderQuarantineAdmissionGate()
        val created = gate.runNativeWithPublication(
            block = { Any() },
            publicationOwner = { NativeAcquisitionPublicationOwner(it) {} },
        )
        val token = checkNotNull(created.token)
        gate.finishNativePublication(token)
        var staleEntered = false
        assertEquals(
            NativeAcquisitionResult.REJECTED,
            gate.runPublishedNativeWithResult(token, { staleEntered = true }, {}),
        )
        assertFalse(staleEntered)
        assertFalse(gate.quarantineNativePublication(token))

        val retry = gate.runNativeWithPublication(
            block = { Any() },
            publicationOwner = { NativeAcquisitionPublicationOwner(it) {} },
        )
        val retryToken = checkNotNull(retry.token)
        val failure = IllegalStateException("start failed")
        assertSame(
            failure,
            runCatching {
                gate.runPublishedNativeWithResult(retryToken, { throw failure }, {})
            }.exceptionOrNull(),
        )
        var published = false
        assertEquals(
            NativeAcquisitionResult.RETURNED_CURRENT,
            gate.runPublishedNativeWithResult(retryToken, {}, { published = true }),
        )
        assertTrue(published)
    }

    @Test
    fun `interrupted drain observation returns false and restores interrupt status`() {
        val gate = RecorderQuarantineAdmissionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            gate.runNativeIfSafe {
                entered.countDown()
                release.await()
            }
        }
        val waiting = CountDownLatch(1)
        val returned = AtomicBoolean(true)
        val interrupted = AtomicBoolean(false)
        val observer = Thread {
            waiting.countDown()
            returned.set(gate.awaitNativeAcquisitionsDrained(1, TimeUnit.MINUTES))
            interrupted.set(Thread.currentThread().isInterrupted)
        }

        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        observer.start()
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        observer.interrupt()
        observer.join(5_000)

        assertFalse(observer.isAlive)
        assertFalse(returned.get())
        assertTrue(interrupted.get())
        release.countDown()
        worker.join(5_000)
    }

    @Test
    fun `pending native setup runs outside process lock and late return is revoked`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = checkNotNull(gate.snapshot(Any()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val worker = Thread {
            accepted.set(gate.runPendingNative(token) {
                entered.countDown()
                release.await()
            })
        }

        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // close() must not wait for the native setup that motivated quarantine.
        assertTrue(gate.close())
        assertFalse(gate.isCurrent(token))
        assertFalse(gate.publish(token) { true })
        assertFalse(gate.awaitNativeAcquisitionsDrained(25, TimeUnit.MILLISECONDS))

        release.countDown()
        worker.join(5_000)
        assertFalse(accepted.get())
        assertTrue(gate.awaitNativeAcquisitionsDrained(1, TimeUnit.SECONDS))
    }

    @Test
    fun `pending recorder token excludes replacement general native acquisition`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = checkNotNull(gate.snapshot(Any()))
        var replacementEntered = false

        assertTrue(gate.hasPendingRecorderSetup())
        assertFalse(gate.runNativeIfSafe { replacementEntered = true })
        assertFalse(replacementEntered)
        assertTrue(gate.runPendingNative(token) {})

        gate.abandonPending(token)
        assertFalse(gate.hasPendingRecorderSetup())
        assertTrue(gate.runNativeIfSafe { replacementEntered = true })
        assertTrue(replacementEntered)
    }

    @Test
    fun `setup owner replays at publication while replacement waits for strict finish`() {
        val gate = RecorderQuarantineAdmissionGate()
        val setupOwner = Any()
        val replacementOwner = Any()
        val token = checkNotNull(gate.snapshot(setupOwner))
        val setupOutcomes = mutableListOf<NativeAcquisitionReplayOutcome>()
        val replacementOutcomes = mutableListOf<NativeAcquisitionReplayOutcome>()

        assertTrue(gate.observeNativeAcquisitionReplay(setupOwner, setupOutcomes::add) != null)
        assertTrue(gate.observeNativeAcquisitionReplay(replacementOwner, replacementOutcomes::add) != null)

        assertTrue(gate.publish(token) { true })
        assertEquals(listOf(NativeAcquisitionReplayOutcome.AVAILABLE), setupOutcomes)
        assertTrue(replacementOutcomes.isEmpty())

        gate.finish(token)
        assertEquals(listOf(NativeAcquisitionReplayOutcome.AVAILABLE), replacementOutcomes)
    }

    @Test
    fun `active recorder admits its Engine native work and excludes foreign Engines until finish`() {
        val gate = RecorderQuarantineAdmissionGate()
        val recordingEngine = Any()
        val replacementEngine = Any()
        val token = checkNotNull(gate.snapshot(recordingEngine))
        assertTrue(gate.publish(token) { true })
        var recordingWorkEntered = false
        var replacementWorkEntered = false

        assertTrue(gate.runNativeIfSafe(recordingEngine) { recordingWorkEntered = true })
        assertTrue(recordingWorkEntered)
        assertFalse(gate.runNativeIfSafe(replacementEngine) { replacementWorkEntered = true })
        assertFalse(replacementWorkEntered)
        assertTrue(gate.generalNativeAcquisitionState(recordingEngine).let {
            !it.quarantined && !it.setupPending && !it.activeRecorderOwnedByForeignEngine
        })
        assertTrue(gate.generalNativeAcquisitionState(replacementEngine).activeRecorderOwnedByForeignEngine)

        gate.finish(token)
        assertTrue(gate.runNativeIfSafe(replacementEngine) { replacementWorkEntered = true })
        assertTrue(replacementWorkEntered)
    }

    @Test
    fun `clean setup retirement replays every waiter exactly once and cancellation stays stale`() {
        val gate = RecorderQuarantineAdmissionGate()
        val token = checkNotNull(gate.snapshot(Any()))
        val live = mutableListOf<NativeAcquisitionReplayOutcome>()
        val cancelled = mutableListOf<NativeAcquisitionReplayOutcome>()
        val cancellation = checkNotNull(gate.observeNativeAcquisitionReplay(Any(), cancelled::add))
        checkNotNull(gate.observeNativeAcquisitionReplay(Any(), live::add))
        cancellation.cancel()

        gate.abandonPending(token)
        gate.abandonPending(token)

        assertEquals(listOf(NativeAcquisitionReplayOutcome.AVAILABLE), live)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `quarantine terminal notifies waiters without granting replay`() {
        val gate = RecorderQuarantineAdmissionGate()
        checkNotNull(gate.snapshot(Any()))
        val outcomes = mutableListOf<NativeAcquisitionReplayOutcome>()
        checkNotNull(gate.observeNativeAcquisitionReplay(Any(), outcomes::add))

        assertTrue(gate.close())

        assertEquals(listOf(NativeAcquisitionReplayOutcome.QUARANTINED), outcomes)
        assertFalse(gate.runNativeIfSafe {})
    }

    @Test
    fun `stale token cannot enter pending native setup`() {
        val gate = RecorderQuarantineAdmissionGate()
        val current = checkNotNull(gate.snapshot(Any()))
        var entered = false

        assertFalse(
            gate.runPendingNative(UnsafeRecorderAdmissionToken(current.epoch + 1, Any())) {
                entered = true
            },
        )
        assertFalse(entered)
    }
}
