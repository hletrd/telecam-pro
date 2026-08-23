package me.hletrd.telecampro.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RejectedOutputOwnerTest {
    @Test
    fun `unresolved rejected outputs are bounded and close admission`() {
        val owner = BoundedRejectedOutputOwner<String>(maxUnresolved = 2) {
            PendingOutputDiscardResult.UNRESOLVED
        }

        owner.discard("heif")
        owner.discard("jpeg")
        owner.discard("dng")

        assertEquals(2, owner.unresolvedCount())
        assertFalse(owner.canAdmit())
    }

    @Test
    fun `retry retires only outputs with durable discard ownership`() {
        val durable = mutableSetOf<String>()
        val owner = BoundedRejectedOutputOwner<String>(maxUnresolved = 3) { output ->
            if (output in durable) PendingOutputDiscardResult.RECOVERY_MARKED
            else PendingOutputDiscardResult.UNRESOLVED
        }
        owner.discard("video")
        owner.discard("still")
        durable += "video"

        assertEquals(1, owner.retryUnresolved())
        assertTrue(owner.canAdmit())
        assertEquals(1, owner.unresolvedCount())
    }
}
