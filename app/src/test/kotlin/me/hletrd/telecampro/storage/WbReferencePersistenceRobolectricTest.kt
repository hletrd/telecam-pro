package me.hletrd.telecampro.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.camera.WbReferenceSample
import me.hletrd.telecampro.camera.WbReferenceSnapshot
import me.hletrd.telecampro.camera.WbRouteKey
import me.hletrd.telecampro.camera.WbSessionShape
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The prefs-backed mirror round-trips through its own file, apart from the settings blob. */
@RunWith(RobolectricTestRunner::class)
class WbReferencePersistenceRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `save then load returns the learned transforms from the dedicated file`() {
        val store = WbReferencePersistence(context)
        assertTrue(store.load().samples.isEmpty())
        val key = WbRouteKey("0", WbSessionShape.MAKER_LOG, "4")
        val transform = doubleArrayOf(0.984, 0.086, -0.070, -0.109, 1.312, -0.203, 0.008, -0.344, 1.336)
        val snapshot = WbReferenceSnapshot(mapOf(key to listOf(WbReferenceSample(3200.0, transform))), setOf(key))
        assertTrue(store.save(snapshot))
        val back = WbReferencePersistence(context).load()
        assertEquals(setOf(key), back.samples.keys)
        assertEquals(3200.0, back.samples.getValue(key).single().kelvin)
        assertArrayEquals(transform, back.samples.getValue(key).single().transform, 1e-5)
        assertEquals(setOf(key), back.swept)
        // Its own file: the camera settings blob is untouched.
        assertTrue(
            context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE).all.isEmpty(),
        )
    }
}
