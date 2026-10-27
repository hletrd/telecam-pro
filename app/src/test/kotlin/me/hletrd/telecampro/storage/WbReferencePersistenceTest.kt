package me.hletrd.telecampro.storage

import me.hletrd.telecampro.camera.WbReferenceSample
import me.hletrd.telecampro.camera.WbRouteKey
import me.hletrd.telecampro.camera.WbSessionShape
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The learned-WB-transform mirror (WbReferencePersistence.kt): lossless enough, and never fragile. */
class WbReferencePersistenceTest {
    private val maker = doubleArrayOf(1.125, -0.188, 0.063, -0.164, 1.172, -0.016, -0.023, -0.539, 1.563)
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private val daylight = doubleArrayOf(1.453, -0.477, 0.016, -0.211, 1.305, -0.094, 0.039, -0.750, 1.711)

    private fun one(t: DoubleArray, kelvin: Double? = null) = listOf(WbReferenceSample(kelvin, t))

    @Test fun `references round trip at five decimals, Kelvin to the unit`() {
        val refs = mapOf(
            WbRouteKey("0", WbSessionShape.MAKER_LOG, "4") to one(maker),
            WbRouteKey("4", WbSessionShape.VENDOR_TC, null) to one(identity),
            WbRouteKey("0", WbSessionShape.SDR, "5") to listOf(
                WbReferenceSample(2873.4, maker),
                WbReferenceSample(5162.0, daylight),
            ),
        )
        val back = decodeWbReferences(encodeWbReferences(refs))
        assertEquals(refs.keys, back.keys)
        refs.forEach { (k, samples) ->
            val got = back.getValue(k).sortedBy { it.kelvin ?: 0.0 }
            assertEquals(samples.size, got.size)
            samples.sortedBy { it.kelvin ?: 0.0 }.zip(got).forEach { (want, have) ->
                assertEquals(want.kelvin?.let(Math::round)?.toDouble(), have.kelvin)
                assertArrayEquals(want.transform, have.transform, 1e-5)
            }
        }
    }

    @Test fun `a pre-Kelvin entry still loads, as a sample with no white point`() {
        val legacy = "0|SDR|5=" + maker.joinToString(",")
        val back = decodeWbReferences(legacy).getValue(WbRouteKey("0", WbSessionShape.SDR, "5"))
        assertEquals(1, back.size)
        assertNull(back.single().kelvin)
        assertArrayEquals(maker, back.single().transform, 1e-9)
    }

    @Test fun `malformed and implausible entries are skipped, the rest survive`() {
        val good = encodeWbReferences(mapOf(WbRouteKey("2", WbSessionShape.SDR, null) to one(maker)))
        val raw = listOf(
            good,
            "garbage",
            "2|SDR|-=1,2,3",
            "2|NOPE|-=" + identity.joinToString(","),
            "3|SDR|-=" + DoubleArray(9).joinToString(","),
            "5|SDR|-=1,0,0,0,x,0,0,0,1",
            "6|SDR|-@=" + identity.joinToString(","),
            "7|SDR|-@-5=" + identity.joinToString(","),
        ).joinToString(";")
        val back = decodeWbReferences(raw)
        assertEquals(setOf(WbRouteKey("2", WbSessionShape.SDR, null)), back.keys)
        assertTrue(decodeWbReferences(null).isEmpty())
        assertTrue(decodeWbReferences("").isEmpty())
    }

    @Test fun `implausible transforms are never written`() {
        val raw = encodeWbReferences(mapOf(WbRouteKey("2", WbSessionShape.SDR, null) to one(DoubleArray(9))))
        assertEquals("", raw)
    }

    @Test fun `swept routes round trip and junk is dropped`() {
        val swept = setOf(WbRouteKey("0", WbSessionShape.SDR, "5"), WbRouteKey("2", WbSessionShape.HLG10, null))
        assertEquals(swept, decodeWbSwept(encodeWbSwept(swept)))
        assertEquals(swept, decodeWbSwept(encodeWbSwept(swept) + ";nope;;"))
        assertTrue(decodeWbSwept(null).isEmpty())
    }
}
