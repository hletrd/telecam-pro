package me.hletrd.telecampro.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletedFamilySweepTest {
    @Test
    fun `clean empty discovery is an authoritative complete sweep`() {
        val result = sweepDeletedFamilySiblings<String>(
            excluded = emptySet(),
            discover = { emptyList() },
            delete = { error("empty discovery must not delete") },
        )

        assertEquals(0, result.discovered)
        assertEquals(0, result.deleted)
        assertEquals(0, result.unresolved)
        assertFalse(result.queryFailed)
        assertTrue(result.complete)
    }

    @Test
    fun `already absent provider row counts as resolved deletion`() {
        val result = sweepDeletedFamilySiblings(
            excluded = emptySet(),
            discover = { listOf("content://media/images/41") },
            // MediaStoreWriter.delete returns true after its exact probe proves already-absent.
            delete = { true },
        )

        assertEquals(1, result.discovered)
        assertEquals(1, result.deleted)
        assertEquals(0, result.unresolved)
        assertFalse(result.queryFailed)
        assertTrue(result.complete)
    }

    @Test
    fun `undeletable discovered row remains explicit and incomplete`() {
        val result = sweepDeletedFamilySiblings(
            excluded = setOf("known"),
            discover = { listOf("known", "undeletable") },
            delete = { false },
        )

        assertEquals(1, result.discovered)
        assertEquals(0, result.deleted)
        assertEquals(1, result.unresolved)
        assertFalse(result.queryFailed)
        assertFalse(result.complete)
    }

    @Test
    fun `query failure is distinct from authoritative empty discovery`() {
        val result = sweepDeletedFamilySiblings<String>(
            excluded = emptySet(),
            discover = { error("provider query failed") },
            delete = { error("failed discovery must not delete") },
        )

        assertEquals(0, result.discovered)
        assertEquals(0, result.deleted)
        assertEquals(0, result.unresolved)
        assertTrue(result.queryFailed)
        assertFalse(result.complete)
    }
}
