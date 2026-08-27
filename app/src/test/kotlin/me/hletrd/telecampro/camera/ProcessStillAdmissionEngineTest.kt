package me.hletrd.telecampro.camera

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProcessStillAdmissionEngineTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `old DNG owner release reopens replacement Engine while detached callback stays inert`() {
        RobolectricEglSentinels.ensure()
        assertTrue(ProcessDngPreCaptureAdmission.owner.canAdmit())
        val oldEngine = CameraEngine(app)
        val replacement = CameraEngine(app)
        val oldEvents = CopyOnWriteArrayList<Boolean>()
        val replacementEvents = CopyOnWriteArrayList<Boolean>()
        var lease: DngPreCaptureAdmission.Lease? = null
        try {
            oldEngine.onStillCaptureAdmissionChanged = oldEvents::add
            lease = requireNotNull(ProcessDngPreCaptureAdmission.owner.tryAcquire())
            replacement.onStillCaptureAdmissionChanged = replacementEvents::add

            assertEquals(listOf(true, false), oldEvents.toList())
            assertEquals(listOf(false), replacementEvents.toList())

            oldEngine.detachCallbacks()
            val detachedOldEvents = oldEvents.toList()
            assertTrue(requireNotNull(lease).release())
            lease = null

            assertEquals(detachedOldEvents, oldEvents.toList())
            assertEquals(listOf(false, true), replacementEvents.toList())

            replacement.detachCallbacks()
            val detachedReplacementEvents = replacementEvents.toList()
            val later = requireNotNull(ProcessDngPreCaptureAdmission.owner.tryAcquire())
            assertTrue(later.release())
            assertEquals(detachedReplacementEvents, replacementEvents.toList())
        } finally {
            lease?.release()
            oldEngine.release()
            replacement.release()
        }
    }
}
