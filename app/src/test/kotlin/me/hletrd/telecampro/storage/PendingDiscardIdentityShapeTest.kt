package me.hletrd.telecampro.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AGG4-58 / DOC4-5: every launch that keeps a row re-asserts `IS_PENDING = 1`, and MediaProvider
 * answers that placement update by renaming the backing file to `.pending-<expiry>-<name>` — which
 * changes `_data` and the modified generation each time. The frozen destructive identity must
 * therefore consist only of fields that survive that rename; adding `_data` or the modified
 * generation would strip every kept row of its exact discard authority after its first re-arm.
 */
class PendingDiscardIdentityShapeTest {
    @Test
    fun `the frozen discard identity holds only rename-stable fields`() {
        val fields = PendingDiscardIdentity::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf(
                "volumeName",
                "providerVersion",
                "rowId",
                "generationAdded",
                "displayName",
                "relativePath",
                "mimeType",
                "ownerPackageName",
                "familyIdentity",
                "dateTaken",
            ),
            fields,
        )
    }
}
