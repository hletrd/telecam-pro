package me.hletrd.telecampro

import android.Manifest
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.ui.CameraViewModel
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
class MainActivityPendingAudioStateTest {

    @Test
    fun `Bundle serializer restores each pending action while rationale owns it`() {
        PendingAudioAction.entries.forEach { action ->
            val saved = Bundle()
            savePendingAudioRequestState(saved, action, rationaleVisible = true)

            assertEquals(
                PendingAudioRequestState(action, rationaleVisible = true),
                restorePendingAudioRequestState(saved),
            )
        }
    }

    @Test
    fun `Bundle serializer restores system permission continuation without reopening rationale`() {
        val saved = Bundle()
        savePendingAudioRequestState(
            saved,
            PendingAudioAction.START_RECORDING,
            rationaleVisible = false,
        )

        assertEquals(
            PendingAudioRequestState(
                PendingAudioAction.START_RECORDING,
                rationaleVisible = false,
            ),
            restorePendingAudioRequestState(saved),
        )
    }

    @Test
    fun `rationale continuation preserves action and terminal result consumes exactly once`() {
        PendingAudioAction.entries.forEach { action ->
            val rationale = PendingAudioRequestState(action, rationaleVisible = true)
            val system = continuePendingAudioRequest(rationale)
            assertEquals(PendingAudioRequestState(action, rationaleVisible = false), system)

            val first = consumePendingAudioRequest(system)
            assertEquals(action, first.action)
            assertNull(first.remaining)

            val duplicate = consumePendingAudioRequest(first.remaining)
            assertNull(duplicate.action)
            assertNull(duplicate.remaining)
        }
    }

    @Test
    fun `terminal continuation clears saved owner and corrupt action fails closed`() {
        val saved = Bundle()
        savePendingAudioRequestState(saved, PendingAudioAction.ENABLE_AUDIO, rationaleVisible = true)
        savePendingAudioRequestState(saved, action = null, rationaleVisible = false)
        assertNull(restorePendingAudioRequestState(saved))

        saved.putString("pending_audio_action", "UNKNOWN")
        assertNull(restorePendingAudioRequestState(saved))
    }

    @Test
    fun `real Activity save and recreation preserve then consume every pending owner once`() {
        RobolectricEglSentinels.ensure()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)

        PendingAudioAction.entries.forEach { action ->
            listOf(true, false).forEach { rationaleVisible ->
                val seed = Bundle().also {
                    savePendingAudioRequestState(it, action, rationaleVisible)
                }
                val first = Robolectric.buildActivity(MainActivity::class.java).create(seed)
                val recreatedState = Bundle()
                try {
                    val vm = ViewModelProvider(first.get())[CameraViewModel::class.java]
                    assertTrue("$action/$rationaleVisible restore lost input ownership", vm.state.value.cameraInputBlocked)
                    first.saveInstanceState(recreatedState)
                    assertEquals(
                        PendingAudioRequestState(action, rationaleVisible),
                        restorePendingAudioRequestState(recreatedState),
                    )
                } finally {
                    first.destroy()
                }

                val second = Robolectric.buildActivity(MainActivity::class.java).create(recreatedState)
                try {
                    val activity = second.get()
                    val vm = ViewModelProvider(activity)[CameraViewModel::class.java]
                    assertTrue("$action/$rationaleVisible recreation lost input ownership", vm.state.value.cameraInputBlocked)
                    assertEquals(action, activity.consumePendingAudioRequestOwner())
                    assertNull(activity.consumePendingAudioRequestOwner())
                    assertFalse(vm.state.value.cameraInputBlocked)

                    val terminal = Bundle()
                    second.saveInstanceState(terminal)
                    assertNull(restorePendingAudioRequestState(terminal))
                } finally {
                    second.destroy()
                }
            }
        }
    }
}
