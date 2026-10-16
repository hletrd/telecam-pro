package me.hletrd.telecampro.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControllerRestorabilityTest {
    @Test
    fun `terminal camera failure revokes restoration before external publication`() {
        val restorability = CameraControllerRestorability()
        val failure = IllegalStateException("camera evicted")
        var published: Throwable? = null

        reportTerminalCameraFailure(restorability, failure) { observed ->
            assertFalse(restorability.canRestore())
            published = observed
        }

        assertSame(failure, published)
    }

    @Test
    fun `restorability is monotonic`() {
        val restorability = CameraControllerRestorability()
        assertTrue(restorability.canRestore())

        restorability.markTerminal()
        restorability.markTerminal()

        assertFalse(restorability.canRestore())
    }
}
