package com.hletrd.findx9tele.gl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisGenerationOwnerTest {

    @Test
    fun `retired generation cannot publish or acquire new work`() {
        val owner = AnalysisGenerationOwner()

        assertTrue(owner.tryAcquire())
        owner.retire()

        assertFalse(owner.mayPublish())
        owner.release()
        assertFalse(owner.tryAcquire())
    }

    @Test
    fun `retired completion cannot clear replacement busy guard`() {
        val retired = AnalysisGenerationOwner()
        val replacement = AnalysisGenerationOwner()

        assertTrue(retired.tryAcquire())
        retired.retire()
        assertTrue(replacement.tryAcquire())

        retired.release()

        assertTrue(replacement.isBusy())
        assertFalse(replacement.tryAcquire())
        replacement.release()
        assertFalse(replacement.isBusy())
        assertTrue(replacement.tryAcquire())
    }

    @Test
    fun `retire racing acquisition never leaves retired owner busy`() {
        repeat(100) {
            val owner = AnalysisGenerationOwner()
            val acquire = Thread { owner.tryAcquire() }
            val retire = Thread { owner.retire() }

            acquire.start()
            retire.start()
            acquire.join()
            retire.join()

            owner.release()
            assertFalse(owner.mayPublish())
            assertFalse(owner.isBusy())
            assertFalse(owner.tryAcquire())
        }
    }
}
