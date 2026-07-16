package com.hletrd.findx9tele.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactlyOnceResourceOwnerTest {

    @Test
    fun `install get and release transfer ownership exactly once`() {
        val owner = ExactlyOnceResourceOwner<Any>()
        val resource = Any()
        val released = mutableListOf<Any>()

        owner.install(resource)
        assertSame(resource, owner.get())
        assertTrue(owner.release(released::add))

        assertNull(owner.get())
        assertEquals(listOf(resource), released)
    }

    @Test
    fun `duplicate release is a no-op even when first releaser throws`() {
        val owner = ExactlyOnceResourceOwner<String>()
        var releases = 0
        owner.install("surface")

        val failure = runCatching {
            owner.release {
                releases++
                throw IllegalStateException("native release")
            }
        }.exceptionOrNull()

        assertEquals("native release", failure?.message)
        assertFalse(owner.release { releases++ })
        assertEquals(1, releases)
    }

    @Test
    fun `partial setup failure releases installed surface before codec cleanup`() {
        val owner = ExactlyOnceResourceOwner<String>()
        val events = mutableListOf<String>()
        owner.install("input-surface")

        val setup = runCatching { throw IllegalStateException("codec start") }
        setup.onFailure {
            owner.releaseThen(
                releaser = { events += "release:$it" },
                afterRelease = { events += "codec cleanup" },
            )
        }

        assertEquals(listOf("release:input-surface", "codec cleanup"), events)
        assertNull(owner.get())
    }

    @Test
    fun `clean cleanup releases surface before stop release and ownership clear`() {
        val owner = ExactlyOnceResourceOwner<String>()
        val events = mutableListOf<String>()
        owner.install("input-surface")

        owner.releaseThen(
            releaser = { events += "surface.release" },
            afterRelease = {
                events += "codec.stop"
                events += "codec.release"
                events += "codec = null"
            },
        )

        assertEquals(
            listOf("surface.release", "codec.stop", "codec.release", "codec = null"),
            events,
        )
    }

    @Test
    fun `wedged drain abandons without invoking native release`() {
        val owner = ExactlyOnceResourceOwner<String>()
        var releases = 0
        owner.install("input-surface")

        assertTrue(owner.abandon())
        assertNull(owner.get())
        assertFalse(owner.release { releases++ })
        assertFalse(owner.abandon())
        assertEquals(0, releases)
    }
}
