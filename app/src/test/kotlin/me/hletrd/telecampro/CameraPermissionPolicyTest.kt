package me.hletrd.telecampro

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPermissionPolicyTest {

    @Test
    fun freshInstallRemainsRequestable() {
        assertEquals(
            CameraPermissionDisposition.REQUESTABLE,
            classifyCameraPermission(
                granted = false,
                requestedBefore = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun canceledFirstPromptRemainsRequestable() {
        val requestedBefore = updatedCameraPermissionRequestHistory(false, result = null)
        assertEquals(
            CameraPermissionDisposition.REQUESTABLE,
            classifyCameraPermission(
                granted = false,
                requestedBefore = requestedBefore,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun firstDenialRemainsRequestableWhileAndroidShowsRationale() {
        val requestedBefore = updatedCameraPermissionRequestHistory(false, result = false)
        assertEquals(
            CameraPermissionDisposition.REQUESTABLE,
            classifyCameraPermission(
                granted = false,
                requestedBefore = requestedBefore,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun fixedDenialRequiresSettings() {
        val requestedBefore = updatedCameraPermissionRequestHistory(false, result = false)
        assertEquals(
            CameraPermissionDisposition.SETTINGS_REQUIRED,
            classifyCameraPermission(
                granted = false,
                requestedBefore = requestedBefore,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun grantWinsEvenIfAStaleFixedFlagIsObserved() {
        assertEquals(
            CameraPermissionDisposition.GRANTED,
            classifyCameraPermission(
                granted = true,
                requestedBefore = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun grantClearsPriorRequestHistory() {
        assertEquals(false, updatedCameraPermissionRequestHistory(true, result = true))
    }

    /**
     * The defect this pins (device-verified 2026-07-28 on a fresh install): declining the mic at the
     * REC prompt returned to an idle viewfinder and recorded nothing, even though `VideoRecorder`
     * records video-only without the permission. The press must still produce a clip.
     */
    @Test
    fun decliningTheMicStillRecordsTheTakeVideoOnly() {
        assertEquals(
            MicrophoneDeclineOutcome.AUDIO_OFF_AND_RECORD,
            microphoneDeclineOutcome(PendingAudioAction.START_RECORDING),
        )
    }

    @Test
    fun decliningAnExplicitAudioEnableJustLeavesAudioOff() {
        assertEquals(
            MicrophoneDeclineOutcome.AUDIO_OFF,
            microphoneDeclineOutcome(PendingAudioAction.ENABLE_AUDIO),
        )
    }

    /** A denial with no recorded intent (dismissed twice, restored state) must not start anything. */
    @Test
    fun decliningWithNoPendingIntentStartsNothing() {
        assertEquals(MicrophoneDeclineOutcome.AUDIO_OFF, microphoneDeclineOutcome(null))
    }

    @Test
    fun recordingStartNeedsTheMicOnlyWhenAudioIsWanted() {
        assertEquals(
            true,
            microphonePermissionRequired(
                PendingAudioAction.START_RECORDING,
                videoMode = true,
                recording = false,
                recordAudio = true,
            ),
        )
        // Audio off — including immediately after a denial — runs straight through with no prompt,
        // which is what keeps declining from becoming a two-press ritual.
        assertEquals(
            false,
            microphonePermissionRequired(
                PendingAudioAction.START_RECORDING,
                videoMode = true,
                recording = false,
                recordAudio = false,
            ),
        )
    }

    @Test
    fun stoppingARecordingNeverPromptsForTheMic() {
        assertEquals(
            false,
            microphonePermissionRequired(
                PendingAudioAction.START_RECORDING,
                videoMode = true,
                recording = true,
                recordAudio = true,
            ),
        )
    }

    @Test
    fun photoModeNeverPromptsForTheMic() {
        assertEquals(
            false,
            microphonePermissionRequired(
                PendingAudioAction.START_RECORDING,
                videoMode = false,
                recording = false,
                recordAudio = true,
            ),
        )
    }

    /** Turning audio ON is always a real microphone request, whatever the capture state. */
    @Test
    fun enablingAudioAlwaysRequiresTheMic() {
        assertEquals(
            true,
            microphonePermissionRequired(
                PendingAudioAction.ENABLE_AUDIO,
                videoMode = false,
                recording = false,
                recordAudio = false,
            ),
        )
    }
}
