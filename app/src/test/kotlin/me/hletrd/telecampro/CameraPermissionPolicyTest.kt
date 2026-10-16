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

    // --- visual-media access level -------------------------------------------------------------

    @Test
    fun noGrantIsNoAccess() {
        assertEquals(
            VisualMediaAccess.NONE,
            visualMediaAccessLevel(
                imagesGranted = false,
                videoGranted = false,
                userSelectedGranted = false,
            ),
        )
    }

    /** "Select photos" grants ONLY user-selected — real access, but to a set the user chose. */
    @Test
    fun userSelectedAloneIsPartial() {
        assertEquals(
            VisualMediaAccess.PARTIAL,
            visualMediaAccessLevel(
                imagesGranted = false,
                videoGranted = false,
                userSelectedGranted = true,
            ),
        )
    }

    /** Images and Video are granular: neither broad grant authorizes the other collection. */
    @Test
    fun oneBroadGrantRetainsItsCollectionIdentity() {
        assertEquals(
            VisualMediaAccess.IMAGES_ONLY,
            visualMediaAccessLevel(
                imagesGranted = true,
                videoGranted = false,
                userSelectedGranted = true,
            ),
        )
        assertEquals(
            VisualMediaAccess.VIDEO_ONLY,
            visualMediaAccessLevel(
                imagesGranted = false,
                videoGranted = true,
                userSelectedGranted = false,
            ),
        )
    }

    @Test
    fun bothBroadGrantsAreFull() {
        assertEquals(
            VisualMediaAccess.FULL,
            visualMediaAccessLevel(
                imagesGranted = true,
                videoGranted = true,
                userSelectedGranted = false,
            ),
        )
    }

    /** hasVisualMediaAccess must stay exactly "level != NONE" for all eight grant combinations. */
    @Test
    fun accessPredicateAgreesWithLevelOnEveryCombination() {
        for (images in listOf(false, true)) {
            for (video in listOf(false, true)) {
                for (userSelected in listOf(false, true)) {
                    assertEquals(
                        "images=$images video=$video userSelected=$userSelected",
                        visualMediaAccessLevel(images, video, userSelected) != VisualMediaAccess.NONE,
                        hasVisualMediaAccess(images, video, userSelected),
                    )
                }
            }
        }
    }

    /** Every grant combination produces exactly the missing broad grants plus a missing API-34 grant. */
    @Test
    fun exactRequestSetCoversEveryGrantCombination() {
        for (images in listOf(false, true)) {
            for (video in listOf(false, true)) {
                for (userSelected in listOf(false, true)) {
                    val expected = buildList {
                        if (!images) add(VisualMediaPermission.IMAGES)
                        if (!video) add(VisualMediaPermission.VIDEO)
                        if ((!images || !video) && !userSelected) {
                            add(VisualMediaPermission.USER_SELECTED)
                        }
                    }
                    assertEquals(
                        "images=$images video=$video userSelected=$userSelected",
                        expected,
                        visualMediaPermissionsToRequest(
                            imagesGranted = images,
                            videoGranted = video,
                            userSelectedGranted = userSelected,
                            userSelectedPermissionAvailable = true,
                        ),
                    )
                    assertEquals(
                        "request decision images=$images video=$video userSelected=$userSelected",
                        expected.isNotEmpty(),
                        shouldRequestVisualMediaAccess(
                            visualMediaAccessLevel(images, video, userSelected),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun api33RequestNeverIncludesUserSelected() {
        assertEquals(
            listOf(VisualMediaPermission.VIDEO),
            visualMediaPermissionsToRequest(
                imagesGranted = true,
                videoGranted = false,
                userSelectedGranted = false,
                userSelectedPermissionAvailable = false,
            ),
        )
    }

    // --- the empty-gallery re-request (Play review policy:H1) ----------------------------------

    /**
     * THE REGRESSION FENCE. A partial grant must still re-open the picker: the empty-gallery tap
     * only fires when the restore found nothing, so "we already have access" is precisely the wrong
     * conclusion — it was what stranded a "Select photos" user with no way to widen their selection.
     */
    @Test
    fun partialGrantStillRequestsSoTheUserCanWidenTheSelection() {
        assertEquals(true, shouldRequestVisualMediaAccess(VisualMediaAccess.PARTIAL))
    }

    @Test
    fun noAccessRequests() {
        assertEquals(true, shouldRequestVisualMediaAccess(VisualMediaAccess.NONE))
    }

    /** Full access must never re-ask — there is nothing left to grant, so a prompt would be nag. */
    @Test
    fun fullAccessNeverRequestsAgain() {
        assertEquals(false, shouldRequestVisualMediaAccess(VisualMediaAccess.FULL))
    }

    /**
     * Guards the exact bug: had the call site kept using hasVisualMediaAccess, PARTIAL and FULL
     * would decide alike. They must not.
     */
    @Test
    fun partialAndFullDecideDifferently() {
        assertEquals(
            true,
            shouldRequestVisualMediaAccess(VisualMediaAccess.PARTIAL) !=
                shouldRequestVisualMediaAccess(VisualMediaAccess.FULL),
        )
    }
}
