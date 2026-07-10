package com.hletrd.findx9tele.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [meteringRect]'s edge clamp and fraction ceiling. The historical hazard: coerceIn(min, max)
 * throws once the requested rect reaches the active-array size (min > max), so a fraction at or
 * beyond ~1.0 must clamp instead of crashing every tap-to-focus.
 */
class MeteringRectTest {

    // A 4000×3000 active array, origin at 0 (the common case on this sensor).
    private val l = 0
    private val t = 0
    private val r = 4000
    private val b = 3000

    private fun rect(cx: Int, cy: Int, fraction: Float): IntArray? =
        meteringRect(l, t, r, b, cx, cy, fraction)

    @Test
    fun centeredTap_staysCentered() {
        val box = rect(2000, 1500, 0.10f)!!
        val (left, top, w, h) = box.let { arrayOf(it[0], it[1], it[2], it[3]) }
        assertEquals(400, w)
        assertEquals(300, h)
        assertEquals(2000, left + w / 2)
        assertEquals(1500, top + h / 2)
    }

    @Test
    fun cornerTap_clampsFullyInsideActiveArray() {
        for ((cx, cy) in listOf(0 to 0, 4000 to 3000, 0 to 3000, 4000 to 0)) {
            val box = rect(cx, cy, 0.16f)!!
            assertTrue(box[0] >= l)
            assertTrue(box[1] >= t)
            assertTrue(box[0] + box[2] <= r)
            assertTrue(box[1] + box[3] <= b)
        }
    }

    @Test
    fun fractionAtOrBeyondOne_clampsInsteadOfThrowing() {
        // Would previously throw IllegalArgumentException from coerceIn (min > max).
        for (f in listOf(0.95f, 1.0f, 4.0f)) {
            val box = rect(2000, 1500, f)!!
            assertTrue(box[2] < r - l)
            assertTrue(box[3] < b - t)
            assertTrue(box[0] >= l && box[0] + box[2] <= r)
            assertTrue(box[1] >= t && box[1] + box[3] <= b)
        }
    }

    @Test
    fun nonPositiveFraction_stillYieldsAMinimalRect() {
        val box = rect(2000, 1500, 0f)!!
        assertTrue(box[2] >= 1)
        assertTrue(box[3] >= 1)
    }

    @Test
    fun degenerateActiveArray_returnsNull() {
        assertNull(meteringRect(100, 100, 100, 300, 100, 200, 0.1f)) // zero width
        assertNull(meteringRect(100, 100, 300, 100, 200, 100, 0.1f)) // zero height
    }

    @Test
    fun offsetActiveArray_respectsItsOrigin() {
        // Active arrays don't always start at 0 — the clamp must use the array's own edges.
        val box = meteringRect(200, 100, 4200, 3100, 250, 150, 0.2f)!!
        assertTrue(box[0] >= 200)
        assertTrue(box[1] >= 100)
        assertTrue(box[0] + box[2] <= 4200)
        assertTrue(box[1] + box[3] <= 3100)
    }
}
