package me.hletrd.telecampro.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * AGG5-37 / DB5-16: the debug capability dump is a set of facts, logged ONCE per process at INFO,
 * so Engine re-creations can no longer spend the reserved warning/error owner.
 */
@RunWith(RobolectricTestRunner::class)
class VendorTagInspectorTest {
    @Test
    fun `a dump claim is granted exactly once per owner`() {
        val claim = AtomicBoolean(false)

        assertTrue(VendorTagInspector.claimDump(claim))
        assertFalse(VendorTagInspector.claimDump(claim))
        assertFalse(VendorTagInspector.claimDump(claim))
    }

    @Test
    fun `repeated dumps add no rows and never spend the warning level`() {
        val manager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(CameraManager::class.java)

        VendorTagInspector.logAll(manager)
        val afterFirst = ShadowLog.getLogsForTag(VendorTagInspector.TAG).size
        VendorTagInspector.logAll(manager)
        VendorTagInspector.logAll(manager)

        val rows = ShadowLog.getLogsForTag(VendorTagInspector.TAG)
        assertEquals(afterFirst, rows.size)
        assertTrue(rows.none { it.type >= Log.WARN })
    }
}
