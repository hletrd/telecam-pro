package me.hletrd.telecampro.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Review's Open in Gallery hands the one capture to the phone's viewer (UI review 2026-11-14). */
@RunWith(RobolectricTestRunner::class)
class MediaViewerLaunchTest {
    private val base: Context = ApplicationProvider.getApplicationContext()

    private class RecordingContext(base: Context, private val missing: Boolean = false) : ContextWrapper(base) {
        var launched: Intent? = null

        override fun startActivity(intent: Intent) {
            if (missing) throw ActivityNotFoundException()
            launched = intent
        }
    }

    @Test fun `a still and a clip open read-only in the viewer by family type`() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val context = RecordingContext(base)
        assertEquals(ExternalLaunchOutcome.LAUNCHED, launchMediaViewer(context, uri, video = false))
        val intent = context.launched!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("image/*", intent.type)
        assertTrue("read grant", intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue("never a write grant", intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)

        launchMediaViewer(context, Uri.parse("content://media/external/video/media/7"), video = true)
        assertEquals("video/*", context.launched!!.type)
    }

    @Test fun `no viewer is reported, not thrown`() {
        val outcome = launchMediaViewer(RecordingContext(base, missing = true), Uri.parse("content://media/x/1"), video = false)
        assertEquals(ExternalLaunchOutcome.UNRESOLVED, outcome)
    }
}
