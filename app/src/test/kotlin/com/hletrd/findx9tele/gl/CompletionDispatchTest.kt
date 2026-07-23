package com.hletrd.findx9tele.gl

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionDispatchTest {

    @Test
    fun `accepted post completes after task exactly once`() {
        lateinit var queued: Runnable
        val work = AtomicInteger()
        val completions = mutableListOf<Result<Unit>>()

        val accepted = dispatchWithResult(
            post = { queued = it; true },
            block = { work.incrementAndGet() },
            onComplete = { completions += it },
        )

        assertTrue(accepted)
        assertEquals(0, work.get())
        assertEquals(0, completions.size)
        queued.run()
        queued.run() // Defensive duplicate execution still cannot duplicate completion delivery.
        assertEquals(2, work.get())
        assertEquals(1, completions.size)
        assertTrue(completions.single().isSuccess)
    }

    @Test
    fun `rejected post completes inline without running task`() {
        val work = AtomicInteger()
        val completions = mutableListOf<Result<Unit>>()

        val accepted = dispatchWithResult(
            post = { false },
            block = { work.incrementAndGet() },
            onComplete = { completions += it },
        )

        assertFalse(accepted)
        assertEquals(0, work.get())
        assertEquals(1, completions.size)
        assertTrue(completions.single().isFailure)
    }

    @Test
    fun `throwing post completes inline exactly once`() {
        val completions = mutableListOf<Result<Unit>>()

        val accepted = dispatchWithResult(
            post = { throw IllegalStateException("dead looper") },
            block = { error("must not run") },
            onComplete = { completions += it },
        )

        assertFalse(accepted)
        assertEquals(1, completions.size)
        assertEquals("dead looper", completions.single().exceptionOrNull()?.message)
    }

    @Test
    fun `task failure still completes once`() {
        lateinit var queued: Runnable
        val completions = mutableListOf<Result<Unit>>()
        dispatchWithResult(
            post = { queued = it; true },
            block = { throw IllegalArgumentException("EGL") },
            onComplete = { completions += it },
        )

        queued.run()
        assertEquals(1, completions.size)
        assertEquals("EGL", completions.single().exceptionOrNull()?.message)
    }

    @Test
    fun `egl detach binds fallback before destroy`() {
        val calls = mutableListOf<String>()

        detachEglOutput(
            hasFallback = true,
            makeFallbackCurrent = { calls += "preview" },
            makeNothingCurrent = { calls += "none" },
            destroy = { calls += "destroy" },
        )

        assertEquals(listOf("preview", "destroy"), calls)
    }

    @Test
    fun `egl detach falls back to no current before destroy`() {
        val calls = mutableListOf<String>()

        detachEglOutput(
            hasFallback = true,
            makeFallbackCurrent = {
                calls += "preview"
                throw IllegalStateException("stale preview")
            },
            makeNothingCurrent = { calls += "none" },
            destroy = { calls += "destroy" },
        )

        assertEquals(listOf("preview", "none", "destroy"), calls)
    }

    @Test
    fun `egl detach never destroys when unbind fails`() {
        val calls = mutableListOf<String>()

        val failure = runCatching {
            detachEglOutput(
                hasFallback = false,
                makeFallbackCurrent = {},
                makeNothingCurrent = {
                    calls += "none"
                    throw IllegalStateException("unbind")
                },
                destroy = { calls += "destroy" },
            )
        }.exceptionOrNull()

        assertEquals("unbind", failure?.message)
        assertEquals(listOf("none"), calls)
    }

    @Test
    fun `encoder candidate create bind and restore is one checked transaction`() {
        val calls = mutableListOf<String>()

        val output = prepareEglOutput(
            create = { calls += "create"; "encoder" },
            makeCandidateCurrent = { calls += "bind:$it" },
            restoreCurrent = { calls += "restore" },
            discardCandidate = { calls += "discard:$it" },
        )

        assertEquals("encoder", output)
        assertEquals(listOf("create", "bind:encoder", "restore"), calls)
    }

    @Test
    fun `candidate create failure has nothing to discard`() {
        val calls = mutableListOf<String>()

        val failure = runCatching {
            prepareEglOutput<String>(
                create = {
                    calls += "create"
                    throw IllegalStateException("create")
                },
                makeCandidateCurrent = { calls += "bind" },
                restoreCurrent = { calls += "restore" },
                discardCandidate = { calls += "discard" },
            )
        }.exceptionOrNull()

        assertEquals("create", failure?.message)
        assertEquals(listOf("create"), calls)
    }

    @Test
    fun `candidate bind failure discards and retains the primary error`() {
        val calls = mutableListOf<String>()

        val failure = runCatching {
            prepareEglOutput(
                create = { calls += "create"; "encoder" },
                makeCandidateCurrent = {
                    calls += "bind"
                    throw IllegalStateException("bind")
                },
                restoreCurrent = { calls += "restore" },
                discardCandidate = {
                    calls += "discard"
                    throw IllegalArgumentException("discard")
                },
            )
        }.exceptionOrNull()

        assertEquals("bind", failure?.message)
        assertEquals(listOf("discard"), failure?.suppressed?.map { it.message })
        assertEquals(listOf("create", "bind", "discard"), calls)
    }

    @Test
    fun `candidate restore failure discards the bound output`() {
        val calls = mutableListOf<String>()

        val failure = runCatching {
            prepareEglOutput(
                create = { "encoder" },
                makeCandidateCurrent = { calls += "bind" },
                restoreCurrent = {
                    calls += "restore"
                    throw IllegalStateException("restore")
                },
                discardCandidate = { calls += "discard" },
            )
        }.exceptionOrNull()

        assertEquals("restore", failure?.message)
        assertEquals(listOf("bind", "restore", "discard"), calls)
    }

    @Test
    fun `failed candidate discard retains its handle for checked retry`() {
        val retained = RetainedOutputs<String>()
        val calls = mutableListOf<String>()
        var firstAttempt = true

        retained.retain("candidate")
        val failure = runCatching {
            retained.releaseAll { output ->
                calls += output
                if (firstAttempt) {
                    firstAttempt = false
                    throw IllegalStateException("destroy")
                }
            }
        }.exceptionOrNull()
        retained.releaseAll { calls += it }

        assertEquals("destroy", failure?.message)
        assertEquals(listOf("candidate", "candidate"), calls)
    }

    @Test
    fun `terminal release reports any retained output stage failure`() {
        val retained = RetainedOutputs<String>()
        retained.retain("first")
        retained.retain("second")
        val calls = mutableListOf<String>()

        val released = retained.releaseAllBestEffort { output ->
            calls += output
            if (output == "first") throw IllegalStateException("destroy")
        }

        assertFalse(released)
        assertEquals(listOf("first", "second"), calls)
    }

    @Test
    fun `release proof requires unbind and destroy or terminal display`() {
        assertFalse(outputReleaseProven(false, surfacesDestroyed = true, displayTerminated = true))
        assertFalse(outputReleaseProven(true, surfacesDestroyed = false, displayTerminated = false))
        assertTrue(outputReleaseProven(true, surfacesDestroyed = true, displayTerminated = false))
        assertTrue(outputReleaseProven(true, surfacesDestroyed = false, displayTerminated = true))
    }

    @Test
    fun `failed EGL operation consumes and reports its own error`() {
        var reads = 0

        val failure = runCatching {
            requireEglSuccess(success = false, op = "eglSwapBuffers") {
                reads++
                0x300D
            }
        }.exceptionOrNull()

        assertEquals(1, reads)
        assertEquals("eglSwapBuffers: EGL error 0x300d", failure?.message)
    }

    @Test
    fun `revoked encoder admission discards prepared output without installing it`() {
        var valid = true
        var installed: String? = null
        var discarded: String? = null
        val admission = EncoderOutputAdmission(
            validity = { valid },
            commitBlock = { block ->
                if (!valid) false else true.also { block() }
            },
        )
        valid = false

        val accepted = installPreparedEncoderOutput(
            candidate = "egl-candidate",
            admission = admission,
            install = { installed = it },
            discard = { discarded = it },
        )

        assertFalse(accepted)
        assertEquals(null, installed)
        assertEquals("egl-candidate", discarded)
    }

    @Test
    fun `first real swap publishes ready then later failure is runtime`() {
        val attachments = mutableListOf<Result<Unit>>()
        val runtime = mutableListOf<Throwable>()
        val signal = EncoderOutputSignal(attachments::add, runtime::add)

        assertTrue(signal.isPending())
        assertTrue(signal.ready())
        assertFalse(signal.ready())
        assertFalse(signal.isPending())
        assertTrue(signal.fail(IllegalStateException("swap")))
        assertFalse(signal.fail(IllegalStateException("duplicate")))

        assertEquals(1, attachments.size)
        assertTrue(attachments.single().isSuccess)
        assertEquals(listOf("swap"), runtime.map { it.message })
    }

    @Test
    fun `failure before first swap is an attachment failure only`() {
        val attachments = mutableListOf<Result<Unit>>()
        val runtime = mutableListOf<Throwable>()
        val signal = EncoderOutputSignal(attachments::add, runtime::add)

        assertTrue(signal.fail(IllegalArgumentException("bind")))
        assertFalse(signal.ready())

        assertEquals("bind", attachments.single().exceptionOrNull()?.message)
        assertTrue(runtime.isEmpty())
    }

    @Test
    fun `no-frame timeout fails a still-pending attachment`() {
        val attachments = mutableListOf<Result<Unit>>()
        val runtime = mutableListOf<Throwable>()
        val signal = EncoderOutputSignal(attachments::add, runtime::add)

        assertTrue(signal.isPending())
        assertTrue(signal.fail(IllegalStateException("Encoder produced no frame before timeout")))

        assertEquals("Encoder produced no frame before timeout", attachments.single().exceptionOrNull()?.message)
        assertTrue(runtime.isEmpty())
    }

    @Test
    fun `rejected no-frame timeout scheduling fails attachment setup`() {
        var ran = false

        val failure = runCatching {
            scheduleCheckedDelay(
                postDelayed = { _, _ -> false },
                delayMs = 2_000L,
                action = { ran = true },
            )
        }.exceptionOrNull()

        assertEquals("Delayed GL task rejected", failure?.message)
        assertFalse(ran)
    }

    @Test
    fun `accepted delayed task schedules with its exact delay and runs on demand`() {
        var scheduled: Pair<Runnable, Long>? = null
        var ran = false

        scheduleCheckedDelay(
            postDelayed = { runnable, delayMs ->
                scheduled = runnable to delayMs
                true
            },
            delayMs = 250L,
            action = { ran = true },
        )

        assertEquals(250L, scheduled?.second)
        assertFalse("scheduling must not run the action inline", ran)
        scheduled?.first?.run()
        assertTrue(ran)
    }

    @Test
    fun `egl detach with no fallback unbinds to nothing before destroy`() {
        val order = mutableListOf<String>()

        detachEglOutput(
            hasFallback = false,
            makeFallbackCurrent = { order += "fallback" },
            makeNothingCurrent = { order += "none" },
            destroy = { order += "destroy" },
        )

        assertEquals(listOf("none", "destroy"), order)
    }

    @Test
    fun `terminal encoder signal reports nothing on a repeated cancel or late failure`() {
        val attachments = mutableListOf<Result<Unit>>()
        val runtime = mutableListOf<Throwable>()
        val signal = EncoderOutputSignal(attachments::add, runtime::add)

        assertTrue(signal.cancel(IllegalStateException("detach")))
        assertFalse(signal.cancel(IllegalStateException("late cancel")))
        assertFalse(signal.fail(IllegalStateException("late failure")))

        assertEquals(1, attachments.size)
        assertEquals("detach", attachments.single().exceptionOrNull()?.message)
        assertTrue(runtime.isEmpty())
    }

    @Test
    fun `abandoned retained outputs are never released`() {
        val outputs = RetainedOutputs<String>()
        outputs.retain("preview")
        outputs.retain("encoder")

        // Shutdown abandonment: a drain thread may still be inside native code, so the retained
        // handles must be forgotten, not released.
        outputs.abandon()

        val released = mutableListOf<String>()
        assertTrue(outputs.releaseAllBestEffort { released += it })
        assertTrue(released.isEmpty())
    }

    @Test
    fun `release hub reports its terminal state`() {
        val hub = ResourceReleaseHub()

        assertFalse(hub.isReleased())
        assertTrue(hub.release())
        assertTrue(hub.isReleased())
        assertFalse(hub.release())
    }

    @Test
    fun `encoder admission validity delegates to the process lease`() {
        var valid = true
        val admission = EncoderOutputAdmission(
            validity = { valid },
            commitBlock = { block ->
                block()
                true
            },
        )

        assertTrue(admission.isValid())
        valid = false
        assertFalse(admission.isValid())
    }

    @Test
    fun `ready and failure race keeps attachment exactly once`() {
        repeat(100) {
            val attachments = AtomicInteger()
            val runtime = AtomicInteger()
            val gate = CountDownLatch(1)
            val signal = EncoderOutputSignal(
                onAttached = { attachments.incrementAndGet() },
                onRuntimeFailure = { runtime.incrementAndGet() },
            )
            val ready = Thread { gate.await(); signal.ready() }
            val failure = Thread { gate.await(); signal.fail(IllegalStateException("swap")) }

            ready.start()
            failure.start()
            gate.countDown()
            ready.join()
            failure.join()

            assertEquals(1, attachments.get())
            assertTrue(runtime.get() in 0..1)
            assertFalse(signal.isActive())
        }
    }

    @Test
    fun `normal detach cancels pending readiness without runtime failure`() {
        val attachments = mutableListOf<Result<Unit>>()
        val runtime = mutableListOf<Throwable>()
        val pending = EncoderOutputSignal(attachments::add, runtime::add)

        assertTrue(pending.cancel(IllegalStateException("stopped")))
        assertEquals("stopped", attachments.single().exceptionOrNull()?.message)
        assertTrue(runtime.isEmpty())

        attachments.clear()
        val ready = EncoderOutputSignal(attachments::add, runtime::add)
        assertTrue(ready.ready())
        assertTrue(ready.cancel(IllegalStateException("clean stop")))
        assertEquals(1, attachments.size)
        assertTrue(attachments.single().isSuccess)
        assertTrue(runtime.isEmpty())
    }

    @Test
    fun `preview output publishes ready then reports one owned failure`() {
        val ready = AtomicInteger()
        val failures = mutableListOf<Throwable>()
        val signal = PreviewOutputSignal(
            onReady = { ready.incrementAndGet() },
            onFailure = failures::add,
        )

        assertTrue(signal.ready())
        assertFalse(signal.ready())
        assertTrue(signal.fail(IllegalStateException("swap")))
        assertFalse(signal.fail(IllegalStateException("duplicate")))

        assertEquals(1, ready.get())
        assertEquals(listOf("swap"), failures.map { it.message })
    }

    @Test
    fun `preview output can fail its first frame without publishing ready`() {
        val ready = AtomicInteger()
        val failures = mutableListOf<Throwable>()
        val signal = PreviewOutputSignal(
            onReady = { ready.incrementAndGet() },
            onFailure = failures::add,
        )

        assertTrue(signal.fail(IllegalStateException("first swap")))
        assertFalse(signal.ready())
        assertEquals(0, ready.get())
        assertEquals(listOf("first swap"), failures.map { it.message })
    }

    @Test
    fun `preview self redraw cannot publish ready before a real camera frame`() {
        val ready = AtomicInteger()
        val signal = PreviewOutputSignal(
            onReady = { ready.incrementAndGet() },
            onFailure = {},
        )

        assertFalse(signal.readyAfterSwap(realCameraFrame = false))
        assertEquals(0, ready.get())
        assertTrue(signal.readyAfterSwap(realCameraFrame = true))
        assertEquals(1, ready.get())
    }

    @Test
    fun `real frame acquisition prefers preview then falls back to encoder`() {
        assertEquals(
            FrameAcquisitionOwner.PREVIEW,
            frameAcquisitionOwner(previewAvailable = true, encoderActive = true),
        )
        assertEquals(
            FrameAcquisitionOwner.ENCODER,
            frameAcquisitionOwner(previewAvailable = false, encoderActive = true),
        )
        assertEquals(
            FrameAcquisitionOwner.NONE,
            frameAcquisitionOwner(previewAvailable = false, encoderActive = false),
        )
    }

    @Test
    fun `cancelled preview output cannot report a stale replacement failure`() {
        val ready = AtomicInteger()
        val failures = AtomicInteger()
        val signal = PreviewOutputSignal(
            onReady = { ready.incrementAndGet() },
            onFailure = { failures.incrementAndGet() },
        )

        assertTrue(signal.cancel())
        assertFalse(signal.ready())
        assertFalse(signal.fail(IllegalStateException("stale")))
        assertEquals(0, ready.get())
        assertEquals(0, failures.get())
    }

    @Test
    fun `bounded stop does not notify shared release subscribers`() {
        val releases = mutableListOf<String>()
        val boundary = ResourceReleaseHub()
        boundary.subscribe { releases += "first" }
        boundary.subscribe { releases += "second" }

        assertTrue(releases.isEmpty())
        assertTrue(boundary.release())
        assertFalse(boundary.release())
        boundary.subscribe { releases += "late" }

        assertEquals(listOf("first", "second", "late"), releases)
    }

    @Test
    fun `concurrent stop subscribers share one generation release`() {
        repeat(100) {
            val boundary = ResourceReleaseHub()
            val callbacks = AtomicInteger()
            val gate = CountDownLatch(1)
            val firstStop = Thread {
                gate.await()
                boundary.subscribe { callbacks.incrementAndGet() }
            }
            val secondStop = Thread {
                gate.await()
                boundary.subscribe { callbacks.incrementAndGet() }
            }
            val cleanup = Thread {
                gate.await()
                boundary.release()
            }

            firstStop.start()
            secondStop.start()
            cleanup.start()
            gate.countDown()
            firstStop.join()
            secondStop.join()
            cleanup.join()

            assertEquals(2, callbacks.get())
            assertFalse(boundary.release())
        }
    }

    @Test
    fun `concurrent stop fallbacks claim generation cleanup once`() {
        val boundary = ResourceReleaseHub()
        val cleanupCalls = AtomicInteger()
        val callbacks = AtomicInteger()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        boundary.subscribe { callbacks.incrementAndGet() }
        boundary.subscribe { callbacks.incrementAndGet() }
        val first = Thread {
            boundary.runCleanup {
                cleanupCalls.incrementAndGet()
                started.countDown()
                finish.await()
                true
            }
        }
        val second = Thread {
            started.await()
            boundary.runCleanup {
                cleanupCalls.incrementAndGet()
                true
            }
        }

        first.start()
        second.start()
        second.join()
        finish.countDown()
        first.join()

        assertEquals(1, cleanupCalls.get())
        assertEquals(2, callbacks.get())
    }
}
