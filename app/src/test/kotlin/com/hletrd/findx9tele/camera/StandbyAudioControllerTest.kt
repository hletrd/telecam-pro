package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

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

    private data class Fixture(
        val controller: StandbyAudioController,
        val scheduled: ArrayDeque<() -> Unit>,
        val unavailable: MutableList<StandbyAudioUnavailable>,
    )

    private fun fixture(
        setup: StandbyAudioSetup,
        paused: () -> Boolean = { false },
        threadLauncher: StandbyThreadLauncher = StandbyThreadLauncher { _, task -> task(); true },
    ): Fixture {
        val scheduled = ArrayDeque<() -> Unit>()
        val unavailable = mutableListOf<StandbyAudioUnavailable>()
        return Fixture(
            controller = StandbyAudioController(
                audioGain = { 1f },
                onLevel = {},
                canStart = { true },
                // A ready fake exits after setup/start without entering the blocking read loop.
                recorderAbsent = { false },
                isPaused = paused,
                permissionGranted = { true },
                audioSetup = setup,
                threadLauncher = threadLauncher,
                retryScheduler = StandbyRetryScheduler { _, task -> scheduled.addLast(task); true },
                onUnavailable = unavailable::add,
            ),
            scheduled = scheduled,
            unavailable = unavailable,
        )
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
}
