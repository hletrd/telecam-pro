package me.hletrd.telecampro

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityPendingAudioStateTest {

    @Test
    fun `Activity recreation restores each pending action while rationale owns it`() {
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
    fun `Activity recreation restores system permission continuation without reopening rationale`() {
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
    fun `terminal continuation clears saved owner and corrupt action fails closed`() {
        val saved = Bundle()
        savePendingAudioRequestState(saved, PendingAudioAction.ENABLE_AUDIO, rationaleVisible = true)
        savePendingAudioRequestState(saved, action = null, rationaleVisible = false)
        assertNull(restorePendingAudioRequestState(saved))

        saved.putString("pending_audio_action", "UNKNOWN")
        assertNull(restorePendingAudioRequestState(saved))
    }
}
