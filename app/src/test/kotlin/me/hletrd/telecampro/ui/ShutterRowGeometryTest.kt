package me.hletrd.telecampro.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutterRowGeometryTest {
    @Test
    fun `supported narrow rows keep gallery snapshot and shutter disjoint`() {
        // CameraScreen supplies 12 dp padding on each side before ShutterRow measures maxWidth.
        for (windowWidth in listOf(320f, 340f, 360f, 411f)) {
            val rowWidth = windowWidth - 24f
            val resolved = snapshotOffsetForRow(rowWidth)
            assertNotNull(resolved)
            val offset = checkNotNull(resolved)
            val centre = rowWidth / 2f
            val galleryRight = 52f
            val snapshotLeft = centre - offset - 24f
            val snapshotRight = centre - offset + 24f
            val shutterLeft = centre - 38f

            assertTrue("$windowWidth dp: gallery overlaps snapshot", galleryRight <= snapshotLeft)
            assertTrue("$windowWidth dp: snapshot overlaps shutter", snapshotRight <= shutterLeft)
            assertTrue("$windowWidth dp: snapshot target is off-row", snapshotLeft >= 0f)
        }
    }

    @Test
    fun `impossibly narrow row omits secondary snapshot instead of overlapping`() {
        assertNull(snapshotOffsetForRow(250f))
        assertNull(snapshotOffsetForRow(Float.NaN))
    }
}
