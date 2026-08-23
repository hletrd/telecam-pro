package me.hletrd.telecampro.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEvidenceReplayStoreTest {

    @Test
    fun `armed true optics door armed true starts a new replay epoch`() {
        val store = MotionEvidenceReplayStore()
        val provider: (Long, Long) -> FloatArray? = { _, _ -> floatArrayOf(1f, 2f) }
        val armed = store.publish(
            armed = true,
            rotationProvider = provider,
            resetEvidence = true,
        )

        val afterOpticsDoor = store.publish(
            armed = true,
            rotationProvider = provider,
            resetEvidence = true,
        )
        val replay = store.snapshot()

        assertTrue(afterOpticsDoor.armed)
        assertTrue(afterOpticsDoor.evidenceEpoch > armed.evidenceEpoch)
        assertSame(provider, replay.rotationProvider)
        assertTrue(replay.evidenceEpoch == afterOpticsDoor.evidenceEpoch)
    }

    @Test
    fun `disarm advances epoch so an in-flight armed result is stale`() {
        val store = MotionEvidenceReplayStore()
        val provider: (Long, Long) -> FloatArray? = { _, _ -> null }
        val armed = store.publish(
            armed = true,
            rotationProvider = provider,
            resetEvidence = false,
        )

        val disarmed = store.publish(
            armed = false,
            rotationProvider = provider,
            resetEvidence = false,
        )

        assertFalse(disarmed.armed)
        assertTrue(disarmed.evidenceEpoch > armed.evidenceEpoch)
    }

    @Test
    fun `unchanged provider preserves epoch while a replacement provider advances it`() {
        val store = MotionEvidenceReplayStore()
        val firstProvider: (Long, Long) -> FloatArray? = { _, _ -> null }
        val secondProvider: (Long, Long) -> FloatArray? = { _, _ -> floatArrayOf(1f) }
        val initial = store.publish(true, firstProvider, resetEvidence = false)

        val unchanged = store.publish(true, firstProvider, resetEvidence = false)
        val replaced = store.publish(true, secondProvider, resetEvidence = false)

        assertTrue(unchanged.evidenceEpoch == initial.evidenceEpoch)
        assertTrue(replaced.evidenceEpoch > unchanged.evidenceEpoch)
        assertSame(secondProvider, store.snapshot().rotationProvider)
    }
}
