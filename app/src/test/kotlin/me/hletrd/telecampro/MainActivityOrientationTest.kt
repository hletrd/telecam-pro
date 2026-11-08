package me.hletrd.telecampro

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The launch window's orientation comes from the MANIFEST: the window manager shows the starting
 * window before onCreate runs, so a lock set only at runtime arrived after a sideways splash had
 * already been drawn (PMA110, 2026-11-09). Large screens are released at runtime instead.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityOrientationTest {
    @Test fun `the manifest declares portrait so a launch never opens sideways`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation)
    }
}
