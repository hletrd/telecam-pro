package me.hletrd.telecampro.storage

import me.hletrd.telecampro.camera.WbRouteKey
import me.hletrd.telecampro.camera.WbSessionShape
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The learned-WB-transform mirror (WbReferencePersistence.kt): lossless enough, and never fragile. */
class WbReferencePersistenceTest {
    private val maker = doubleArrayOf(1.125, -0.188, 0.063, -0.164, 1.172, -0.016, -0.023, -0.539, 1.563)
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    @Test fun `references round trip at five decimals`() {
        val refs = mapOf(
            WbRouteKey("0", WbSessionShape.MAKER_LOG, "4") to maker,
            WbRouteKey("4", WbSessionShape.VENDOR_TC, null) to identity,
        )
        val back = decodeWbReferences(encodeWbReferences(refs))
        assertEquals(refs.keys, back.keys)
        refs.forEach { (k, v) -> assertArrayEquals(v, back.getValue(k), 1e-5) }
    }

    @Test fun `malformed and implausible entries are skipped, the rest survive`() {
        val good = encodeWbReferences(mapOf(WbRouteKey("2", WbSessionShape.SDR, null) to maker))
        val raw = listOf(
            good,
            "garbage",
            "2|SDR|-=1,2,3",
            "2|NOPE|-=" + identity.joinToString(","),
            "3|SDR|-=" + DoubleArray(9).joinToString(","),
            "5|SDR|-=1,0,0,0,x,0,0,0,1",
        ).joinToString(";")
        val back = decodeWbReferences(raw)
        assertEquals(setOf(WbRouteKey("2", WbSessionShape.SDR, null)), back.keys)
        assertTrue(decodeWbReferences(null).isEmpty())
        assertTrue(decodeWbReferences("").isEmpty())
    }

    @Test fun `implausible transforms are never written`() {
        val raw = encodeWbReferences(mapOf(WbRouteKey("2", WbSessionShape.SDR, null) to DoubleArray(9)))
        assertEquals("", raw)
    }
}
