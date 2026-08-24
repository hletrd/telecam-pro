package me.hletrd.telecampro.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFinalizationPublicationTest {
    @Test
    fun `held native ownership publishes one start and one terminal edge`() {
        val edges = mutableListOf<Boolean>()
        val publication = RecordingFinalizationPublication(edges::add)

        assertFalse(publication.current())
        publication.set(true)
        publication.set(true)

        assertTrue(publication.current())
        assertEquals(listOf(true), edges)

        publication.set(false)
        publication.set(false)

        assertFalse(publication.current())
        assertEquals(listOf(true, false), edges)
    }
}
