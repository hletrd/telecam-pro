package com.hletrd.findx9tele.gl

import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicOwnerSlotTest {

    private class FakePipeline(var value: Int = 0)

    @Test
    fun `late old task can mutate only its captured pipeline`() {
        val first = FakePipeline()
        val slot = AtomicOwnerSlot(first) { FakePipeline() }
        val lateOldTask = { first.value++ }

        val replacement = slot.replaceIfOwned(first)
        lateOldTask()

        assertNotNull(replacement)
        assertEquals(1, first.value)
        assertEquals(0, replacement?.value)
        assertSame(replacement, slot.current())
    }

    @Test
    fun `late old callback cannot publish into replacement state`() {
        val first = FakePipeline()
        val slot = AtomicOwnerSlot(first) { FakePipeline() }
        val publications = mutableListOf<FakePipeline>()
        val lateCallback = {
            if (slot.owns(first)) publications += first
        }

        val replacement = slot.replaceIfOwned(first)
        lateCallback()

        assertNotNull(replacement)
        assertTrue(publications.isEmpty())
    }

    @Test
    fun `stale completion cannot replace a newer owner`() {
        val first = FakePipeline()
        val slot = AtomicOwnerSlot(first) { FakePipeline() }
        val replacement = slot.replaceIfOwned(first)

        assertNotNull(replacement)
        assertNull(slot.replaceIfOwned(first))
        assertSame(replacement, slot.current())
        assertFalse(slot.owns(first))
    }

    @Test
    fun `live provider resolves the replacement owner`() {
        val first = FakePipeline()
        val slot = AtomicOwnerSlot(first) { FakePipeline() }
        val provider = slot::current

        assertSame(first, provider())
        val replacement = slot.replaceIfOwned(first)
        assertSame(replacement, provider())
    }

    @Test
    fun `racing replacements install exactly one owner`() {
        repeat(100) {
            val first = FakePipeline()
            val slot = AtomicOwnerSlot(first) { FakePipeline() }
            val gate = CountDownLatch(1)
            val results = Collections.synchronizedList(mutableListOf<FakePipeline?>())
            val replace = {
                gate.await()
                results += slot.replaceIfOwned(first)
            }
            val one = Thread(replace)
            val two = Thread(replace)

            one.start()
            two.start()
            gate.countDown()
            one.join()
            two.join()

            assertEquals(1, results.count { it != null })
            assertTrue(slot.current() !== first)
        }
    }

    @Test
    fun `bounded stop requires both thread exit and checked resource release`() {
        assertEquals(GlStopOutcome.STOPPED, glStopOutcome(threadExited = true, resourcesReleased = true))
        assertEquals(GlStopOutcome.ABANDONED, glStopOutcome(threadExited = false, resourcesReleased = true))
        assertEquals(GlStopOutcome.ABANDONED, glStopOutcome(threadExited = true, resourcesReleased = false))
        assertEquals(GlStopOutcome.ABANDONED, glStopOutcome(threadExited = false, resourcesReleased = false))
    }

    @Test
    fun `queued input transaction rejects retired owner stopped engine and replaced surface`() {
        assertTrue(glInputTransactionMayProceed(true, true, true))
        assertFalse(glInputTransactionMayProceed(false, true, true))
        assertFalse(glInputTransactionMayProceed(true, false, true))
        assertFalse(glInputTransactionMayProceed(true, true, false))
    }

    @Test
    fun `abandoned owner replacement restarts only a live foreground preview`() {
        assertTrue(glReplacementMayRestartPreview(true, false, true, true))
        assertFalse(glReplacementMayRestartPreview(false, false, true, true))
        assertFalse(glReplacementMayRestartPreview(true, true, true, true))
        assertFalse(glReplacementMayRestartPreview(true, false, false, true))
        assertFalse(glReplacementMayRestartPreview(true, false, true, false))
    }
}
