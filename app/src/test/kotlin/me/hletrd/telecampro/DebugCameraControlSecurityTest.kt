package me.hletrd.telecampro

import android.content.ComponentName
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DebugCameraControlSecurityTest {
    @Test fun `merged debug manifest protects the exported control hook with DUMP`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val info = app.packageManager.getActivityInfo(
            ComponentName(app, DebugCameraControlActivity::class.java),
            0,
        )

        assertTrue(info.exported)
        assertEquals("android.permission.DUMP", info.permission)
    }

    @Test fun `protected component forwards no command extras to ordinary launcher`() {
        DebugCameraControlMailbox.consume()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent = Intent(app, DebugCameraControlActivity::class.java)
            .putExtra(EXTRA_ZSL_SPIKE, true)
            .putExtra(EXTRA_DEBUG_ZOOM, 3f)
        val controller = Robolectric.buildActivity(DebugCameraControlActivity::class.java, intent)
            .create()
        try {
            val started = shadowOf(controller.get()).nextStartedActivity
            assertEquals(ComponentName(app, MainActivity::class.java), started.component)
            assertFalse(started.hasExtra(EXTRA_ZSL_SPIKE))
            assertFalse(started.hasExtra(EXTRA_DEBUG_ZOOM))
            assertEquals(
                DebugCameraControlCommand(zslSpike = true, zoomRatio = 3f),
                DebugCameraControlMailbox.consume(),
            )
        } finally {
            controller.destroy()
            DebugCameraControlMailbox.consume()
        }
    }

    @Test fun `ordinary exported launcher extras are inert`() {
        DebugCameraControlMailbox.consume()
        RobolectricEglSentinels.ensure()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val intent = Intent(app, MainActivity::class.java)
            .putExtra(EXTRA_ZSL_SPIKE, true)
            .putExtra(EXTRA_DEBUG_ZOOM, 3f)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        try {
            val vm = ViewModelProvider(controller.get())[me.hletrd.telecampro.ui.CameraViewModel::class.java]
            assertEquals(1f, vm.state.value.controls.zoomRatio)
            assertNull(DebugCameraControlMailbox.consume())
        } finally {
            controller.destroy()
        }
    }

    @Test fun `mailbox merges protected commands until live Activity consumes them`() {
        DebugCameraControlMailbox.consume()
        assertTrue(DebugCameraControlMailbox.publish(DebugCameraControlCommand(zslSpike = false)))
        assertTrue(DebugCameraControlMailbox.publish(DebugCameraControlCommand(zoomRatio = 4f)))
        assertEquals(
            DebugCameraControlCommand(zslSpike = false, zoomRatio = 4f),
            DebugCameraControlMailbox.consume(),
        )
    }
}

