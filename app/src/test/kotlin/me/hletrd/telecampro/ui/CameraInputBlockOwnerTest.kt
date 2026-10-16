package me.hletrd.telecampro.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraInputBlockOwnerTest {
    @Test
    fun `one modal release cannot clear another owner`() {
        var owners = emptySet<CameraInputBlockOwner>()
        owners = cameraInputBlockOwnersAfter(owners, CameraInputBlockOwner.MICROPHONE_PERMISSION, true)
        owners = cameraInputBlockOwnersAfter(owners, CameraInputBlockOwner.COMPOSE_MODAL, true)
        owners = cameraInputBlockOwnersAfter(owners, CameraInputBlockOwner.COMPOSE_MODAL, false)

        assertTrue(CameraInputBlockOwner.MICROPHONE_PERMISSION in owners)
        assertTrue(owners.isNotEmpty())

        owners = cameraInputBlockOwnersAfter(owners, CameraInputBlockOwner.MICROPHONE_PERMISSION, false)
        assertFalse(owners.isNotEmpty())
    }

    @Test
    fun `repeated acquire and release are idempotent`() {
        val owner = CameraInputBlockOwner.MEDIA_PERMISSION
        val acquired = cameraInputBlockOwnersAfter(emptySet(), owner, true)
        assertTrue(cameraInputBlockOwnersAfter(acquired, owner, true) === acquired)
        val released = cameraInputBlockOwnersAfter(acquired, owner, false)
        assertTrue(cameraInputBlockOwnersAfter(released, owner, false) === released)
    }
}
