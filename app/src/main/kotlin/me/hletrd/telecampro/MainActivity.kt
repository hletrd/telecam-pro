package me.hletrd.telecampro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import me.hletrd.telecampro.ui.controls.MinTouchTarget48
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.CameraScreen
import me.hletrd.telecampro.ui.CameraViewModel
import me.hletrd.telecampro.ui.theme.CameraColors
import me.hletrd.telecampro.ui.theme.TeleCamProTheme

class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()

    // Compose-observable permission state, held on the Activity so onResume (return from the system
    // Settings screen) can re-check and flip the gate without the user re-launching.
    private var hasCameraPermission by mutableStateOf(false)
    private var hasMicrophonePermission by mutableStateOf(false)
    // True once the user has denied with "don't ask again": the runtime dialog no longer appears, so
    // the CTA must deep-link into App Settings instead of a dead re-request (designer UX-6 / M8).
    private var cameraPermanentlyDenied by mutableStateOf(false)
    private var pendingAudioAction by mutableStateOf<PendingAudioAction?>(null)
    private var showMicrophoneRationale by mutableStateOf(false)
    // Edge ownership is Activity-local: if a modal opens between DOWN and UP, the matching release
    // still reaches the ViewModel and both edges stay consumed instead of wedging a press state.
    private val ownedShutterKeys = mutableSetOf<Int>()
    private val ownedHalfPressKeys = mutableSetOf<Int>()
    private val ownedQuickKeys = mutableSetOf<Int>()

    // --- Unattended-timelapse screen dim (perf review #10) -------------------------------------
    // FLAG_KEEP_SCREEN_ON stays for the whole activity lifetime (a run killed by screen-off is a
    // lost shoot), but during a timelapse RUN the forced-on panel is a ~1 W-class drain, one to two
    // orders above every other standing cost measured in the perf review. Standard camera-app
    // pattern: after [TIMELAPSE_DIM_GRACE_MS] without interaction, override window brightness to
    // [TIMELAPSE_DIM_BRIGHTNESS] (screen stays alive — the run is visibly still going); ANY
    // interaction ([onUserInteraction]) restores full brightness and re-arms the grace timer.
    // Run end / leaving STARTED always restores.
    private val timelapseDimHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timelapseDimArmed = false
    private var timelapseDimmed = false
    private val timelapseDimRunnable = Runnable { applyTimelapseDim(true) }

    private fun onTimelapseRunningChanged(running: Boolean) {
        timelapseDimArmed = running
        timelapseDimHandler.removeCallbacks(timelapseDimRunnable)
        if (running) {
            timelapseDimHandler.postDelayed(timelapseDimRunnable, TIMELAPSE_DIM_GRACE_MS)
        } else {
            applyTimelapseDim(false)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!timelapseDimArmed) return // cheap early-out on every ordinary touch/key
        applyTimelapseDim(false)
        timelapseDimHandler.removeCallbacks(timelapseDimRunnable)
        timelapseDimHandler.postDelayed(timelapseDimRunnable, TIMELAPSE_DIM_GRACE_MS)
    }

    private fun applyTimelapseDim(dim: Boolean) {
        if (timelapseDimmed == dim) return
        timelapseDimmed = dim
        window.attributes = window.attributes.also {
            it.screenBrightness = if (dim) {
                TIMELAPSE_DIM_BRIGHTNESS
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    /**
     * DEBUG-only shell hook: API 36 rejects adb-shell broadcasts to NOT_EXPORTED receivers
     * (device-confirmed 2026-07-25 — result=0, enqueued, never delivered), so shell-driven debug
     * toggles ride intent extras on this exported launcher activity instead. Deliver to the
     * RUNNING instance with FLAG_ACTIVITY_SINGLE_TOP:
     *   adb shell am start -n me.hletrd.telecampro.debug/me.hletrd.telecampro.MainActivity \
     *       -f 0x20000000 --ez zsl_spike true        # cycle-8 S4a streaming spike on/off
     *   ... -f 0x20000000 --ef debug_zoom 3.0         # zoom injection (device-test hook)
     * Inert in release builds (the ViewModel methods re-check BuildConfig.DEBUG).
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.hasExtra("zsl_spike")) vm.debugSetZslSpike(intent.getBooleanExtra("zsl_spike", false))
        if (intent.hasExtra("debug_zoom")) vm.debugApplyZoom(intent.getFloatExtra("debug_zoom", -1f))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleDebugIntent(intent)
        // Drop obscured touches before they reach camera/settings/delete consent surfaces. This is
        // Android's view-level tapjacking defense and needs no overlay permission or network access.
        window.decorView.filterTouchesWhenObscured = true
        refreshPermissionState()
        // Timelapse-run edges drive the unattended screen dim. The finally arm covers leaving
        // STARTED mid-run (background/keyguard): the timer is cancelled and brightness restored, and
        // re-entering STARTED re-collects the current run state and re-arms if still running.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    // Dim only while the run is live AND no modal is up (review 2026-08-01):
                    // cameraInputBlocked mirrors sheet/Fn/review, and review-during-run is exactly
                    // staring-without-touching — the frame under critical-focus inspection must
                    // not fade to 5% ten seconds in. Modal close re-arms the grace timer.
                    vm.state.map { it.timelapseRunning && !it.cameraInputBlocked }
                        .distinctUntilChanged()
                        .collect { dimEligible -> onTimelapseRunningChanged(dimEligible) }
                } finally {
                    onTimelapseRunningChanged(false)
                }
            }
        }

        setContent {
            TeleCamProTheme {
                val state by vm.state.collectAsState()

                val cameraLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { results ->
                    // An empty map is a canceled/interrupted request, not a denial. Preserve history
                    // so a fresh install is never mislabeled as permanently denied.
                    recordCameraPermissionResult(results[Manifest.permission.CAMERA])
                    refreshPermissionState()
                }
                val mediaAccessLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { _ ->
                    // Re-check LIVE state instead of the result map: Android 14+'s "Select photos"
                    // choice reports the full permissions denied while granting USER_SELECTED, and
                    // that partial grant is access (hasVisualMediaAccess). On any access, re-run
                    // the capture restore so a previous install's rows can seed review; a decline
                    // simply keeps the historic own-rows-only behavior — nothing to record.
                    if (hasVisualMediaPermission()) vm.onGalleryAccessRequested()
                }
                val microphoneLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    hasMicrophonePermission = hasPermission(Manifest.permission.RECORD_AUDIO)
                    val action = pendingAudioAction
                    pendingAudioAction = null
                    if (!granted) {
                        declineMicrophone(action)
                        return@rememberLauncherForActivityResult
                    }
                    when (action) {
                        PendingAudioAction.ENABLE_AUDIO -> vm.onToggleRecordAudio(true)
                        PendingAudioAction.START_RECORDING -> vm.onToggleRecording()
                        null -> Unit
                    }
                }

                LaunchedEffect(Unit) {
                    if (!hasCameraPermission && !cameraPermanentlyDenied) {
                        cameraLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    }
                }

                if (hasCameraPermission) {
                    val permissionAwareActions = remember(state.mode, state.isRecording, state.recordAudio, hasMicrophonePermission) {
                        object : CameraActions by vm {
                            override fun onToggleRecording() {
                                requestMicrophoneThen(
                                    action = PendingAudioAction.START_RECORDING,
                                    block = vm::onToggleRecording,
                                )
                            }

                            override fun onToggleRecordAudio(enabled: Boolean) {
                                if (!enabled) {
                                    // The operator chose silence. Clear the denial reason so a later
                                    // grant does NOT override their own choice.
                                    permissionPreferences.edit(commit = true) {
                                        putBoolean(AUDIO_OFF_BY_DENIAL_KEY, false)
                                    }
                                    vm.onToggleRecordAudio(false)
                                    return
                                }
                                requestMicrophoneThen(
                                    action = PendingAudioAction.ENABLE_AUDIO,
                                ) { vm.onToggleRecordAudio(true) }
                            }

                            // Declared EXPLICITLY rather than left to `by vm`: a device-verified
                            // AbstractMethodError (2026-08-02) showed this object shipping without
                            // the delegated member after the interface gained it, crashing the
                            // first composition. The three siblings below are explicit anyway, so
                            // stating this one costs nothing and cannot regress that way again.
                            override fun onExposureMeterVisibilityChanged(visible: Boolean) =
                                vm.onExposureMeterVisibilityChanged(visible)

                            override fun onScopesVisibilityChanged(visible: Boolean) =
                                vm.onScopesVisibilityChanged(visible)

                            override fun onGalleryAccessRequested() {
                                // Contextual media-access request (2026-08-01): the empty-gallery
                                // tap IS the context, so the system dialog needs no rationale
                                // sheet. With access already in force, fall through to the VM's
                                // re-restore (covers a grant made in Settings mid-session).
                                if (hasVisualMediaPermission()) {
                                    vm.onGalleryAccessRequested()
                                } else {
                                    mediaAccessLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.READ_MEDIA_IMAGES,
                                            Manifest.permission.READ_MEDIA_VIDEO,
                                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    // The camera is permitted but the PLATFORM refuses it for this app (appops /
                    // policy). checkSelfPermission answers granted, so nothing else in the app
                    // notices; without this the operator gets normal viewfinder chrome over a black
                    // frame and a dead shutter, with no way to learn why (device-found on a Lenovo
                    // TB331FC, 2026-08-02). Reuses the permission screen rather than inventing a
                    // banner — same class of state, and docs/UX_POLICY.md forbids warning chips.
                    if (state.cameraPolicyBlocked) {
                        PermissionGate(
                            permanentlyDenied = true,
                            policyBlocked = true,
                            onRequest = {},
                            onOpenSettings = ::openAppSettings,
                            onOpenPrivacy = ::openPrivacyPolicy,
                        )
                    } else {
                    CameraScreen(state = state, actions = permissionAwareActions, modifier = Modifier.fillMaxSize())
                    }
                    if (showMicrophoneRationale && !state.cameraPolicyBlocked) {
                        MicrophonePermissionRationale(
                            onContinue = {
                                showMicrophoneRationale = false
                                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onDismiss = {
                                showMicrophoneRationale = false
                                val action = pendingAudioAction
                                pendingAudioAction = null
                                declineMicrophone(action)
                            },
                        )
                    }
                } else {
                    PermissionGate(
                        permanentlyDenied = cameraPermanentlyDenied,
                        onRequest = { cameraLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
                        onOpenSettings = ::openAppSettings,
                        onOpenPrivacy = ::openPrivacyPolicy,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onStart()
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from App Settings so granting there flips the gate immediately.
        refreshPermissionState()
    }

    override fun onStop() {
        if (ownedShutterKeys.isNotEmpty()) {
            ownedShutterKeys.clear()
            vm.onHardwareFullKey(active = false)
        }
        if (ownedHalfPressKeys.isNotEmpty()) {
            ownedHalfPressKeys.clear()
            vm.onHardwareHalfPress(active = false)
        }
        if (ownedQuickKeys.isNotEmpty()) {
            ownedQuickKeys.clear()
            vm.onHardwareQuickButton(active = false)
        }
        vm.onStop()
        super.onStop()
    }

    // Volume keys AND the hardware camera button's FULL press fire the shutter (KEYCODE_CAMERA): at
    // 300 mm even a light screen tap visibly shakes the rig, so a hardware key (volume remote / selfie
    // grip / the camera-control button) is the only vibration-free release short of the self-timer. The
    // button's slide + light-press gestures arrive separately as non-standard OPPO keycodes and are
    // handled in dispatchKeyEvent (zoom / AF). Keys are consumed on DOWN and UP so holding one never
    // turns the media volume into a burst of beeps mid-shot; repeatCount gates auto-repeat.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isShutterKey(keyCode)) {
            val decision = cameraKeyDecision(
                hasCameraPermission = hasCameraPermission,
                cameraInputBlocked = vm.state.value.cameraInputBlocked || showMicrophoneRationale,
                alreadyOwned = keyCode in ownedShutterKeys,
                edge = if (event.repeatCount == 0) CameraKeyEdge.DOWN else CameraKeyEdge.REPEAT,
            )
            if (decision.start) {
                val activeTransition = updateAggregateCameraKeyOwnership(ownedShutterKeys, keyCode, ownedAfter = true)
                if (activeTransition == true) {
                    val s = vm.state.value
                    // The hardware shutter deliberately never opens the rationale dialog — a
                    // physical press should start the take, not a modal — so when the mic is wanted
                    // but absent it drops audio and records video-only, reaching the same outcome
                    // the touch path's decline reaches. The decision is the pure, unit-tested
                    // [hardwareShutterAudioDrop]: it additionally requires the full-press action to
                    // actually BE the shutter (the key is user-reassignable — a Zoom In press must
                    // not flip audio off), and this drop is recorded as a DENIAL consequence so a
                    // later Settings grant restores it, exactly like the touch decline. The STATUS
                    // text differs from the decline's on purpose: here the user was never asked and
                    // so has denied nothing.
                    if (hardwareShutterAudioDrop(
                            fullKeyAction = s.volumeKeyAction,
                            videoMode = s.mode == CaptureMode.VIDEO,
                            recording = s.isRecording,
                            recordAudio = s.recordAudio,
                            hasMicrophonePermission = hasMicrophonePermission,
                        )
                    ) {
                        permissionPreferences.edit(commit = true) {
                            putBoolean(AUDIO_OFF_BY_DENIAL_KEY, true)
                        }
                        vm.onToggleRecordAudio(false)
                        vm.onAppStatus("Recording without audio")
                    }
                }
                activeTransition?.let { vm.onHardwareFullKey(active = it) }
            }
            return if (decision.consume) true else super.onKeyDown(keyCode, event)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isShutterKey(keyCode)) {
            val decision = cameraKeyDecision(
                hasCameraPermission = hasCameraPermission,
                cameraInputBlocked = vm.state.value.cameraInputBlocked || showMicrophoneRationale,
                alreadyOwned = keyCode in ownedShutterKeys,
                edge = CameraKeyEdge.UP,
            )
            if (decision.release) {
                updateAggregateCameraKeyOwnership(ownedShutterKeys, keyCode, ownedAfter = false)
                    ?.let { vm.onHardwareFullKey(active = it) }
            }
            return if (decision.consume) true else super.onKeyUp(keyCode, event)
        }
        return super.onKeyUp(keyCode, event)
    }

    // The Find X9 Ultra camera-control button's capacitive gestures ride the `cs_press` sensor. As
    // live-verified 2026-07-09, the slide reaches a focused third-party app as the STANDARD
    // KEYCODE_ZOOM_IN/OUT (repeating ~20 Hz) — the OPPO codes 767/769/782 seen in one earlier
    // session are config-dependent and kept only as aliases; the light press is currently NOT
    // delivered at all (the KEYCODE_FOCUS/782 handlers stay armed if it ever arrives). Slides →
    // stepped zoom via the eased target; press/half-press → the configurable HardwareKeyActions.
    // RestrictedApi: overriding ComponentActivity.dispatchKeyEvent trips AndroidX's library-group
    // restriction lint, but this override is the ONLY delivery point for the camera-control
    // button's key events (verified: removing the suppress yields 5 RestrictedApi errors and
    // there is no public alternative hook for pre-IME key interception here).
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // DEBUG: trace every non-standard key so the camera-button gestures can be re-mapped from a
        // live session — the codes seen once (767/769/782) may not be the full story (user reports
        // slide/half-press dead while full press works).
        if (BuildConfig.DEBUG && !isShutterKey(event.keyCode) && event.keyCode != KeyEvent.KEYCODE_BACK) {
            android.util.Log.i("BtnDbg", "key code=${event.keyCode} action=${event.action} perm=$hasCameraPermission")
        }
        when (event.keyCode) {
            // Half presses own their matching release even if a modal opens mid-press.
            KeyEvent.KEYCODE_FOCUS, KEY_CAM_HALF_PRESS, KEY_CAM_HALF_PRESS_ALT -> {
                val edge = when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) CameraKeyEdge.DOWN else CameraKeyEdge.REPEAT
                    KeyEvent.ACTION_UP -> CameraKeyEdge.UP
                    else -> return super.dispatchKeyEvent(event)
                }
                val decision = cameraKeyDecision(
                    hasCameraPermission = hasCameraPermission,
                    cameraInputBlocked = vm.state.value.cameraInputBlocked || showMicrophoneRationale,
                    alreadyOwned = event.keyCode in ownedHalfPressKeys,
                    edge = edge,
                )
                if (decision.start) {
                    val transition = updateAggregateCameraKeyOwnership(ownedHalfPressKeys, event.keyCode, ownedAfter = true)
                    if (transition == true) {
                        val s = vm.state.value
                        // Same denial-recorded audio drop as the full-key path (onKeyDown): the
                        // half-press action is user-assignable to SHUTTER, and without this the
                        // half-press REC start bypassed the mic decision entirely — recording
                        // video-only with audio still ARMED in the UI. Live since 2026-07-31 (767).
                        if (hardwareShutterAudioDrop(
                                fullKeyAction = s.halfPressAction,
                                videoMode = s.mode == CaptureMode.VIDEO,
                                recording = s.isRecording,
                                recordAudio = s.recordAudio,
                                hasMicrophonePermission = hasMicrophonePermission,
                            )
                        ) {
                            permissionPreferences.edit(commit = true) {
                                putBoolean(AUDIO_OFF_BY_DENIAL_KEY, true)
                            }
                            vm.onToggleRecordAudio(false)
                            vm.onAppStatus("Recording without audio")
                        }
                    }
                    transition?.let(vm::onHardwareHalfPress)
                }
                if (decision.release) {
                    updateAggregateCameraKeyOwnership(ownedHalfPressKeys, event.keyCode, ownedAfter = false)
                        ?.let(vm::onHardwareHalfPress)
                }
                if (decision.consume) return true
            }
            KEY_CAM_QUICK -> {
                val edge = when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) CameraKeyEdge.DOWN else CameraKeyEdge.REPEAT
                    KeyEvent.ACTION_UP -> CameraKeyEdge.UP
                    else -> return super.dispatchKeyEvent(event)
                }
                val decision = cameraKeyDecision(
                    hasCameraPermission = hasCameraPermission,
                    cameraInputBlocked = vm.state.value.cameraInputBlocked || showMicrophoneRationale,
                    alreadyOwned = event.keyCode in ownedQuickKeys,
                    edge = edge,
                )
                if (decision.start) {
                    val transition = updateAggregateCameraKeyOwnership(ownedQuickKeys, event.keyCode, ownedAfter = true)
                    if (transition == true) {
                        val s = vm.state.value
                        // Same denial-recorded audio drop as the full-key and half-press paths.
                        if (hardwareShutterAudioDrop(
                                fullKeyAction = s.quickButtonAction,
                                videoMode = s.mode == CaptureMode.VIDEO,
                                recording = s.isRecording,
                                recordAudio = s.recordAudio,
                                hasMicrophonePermission = hasMicrophonePermission,
                            )
                        ) {
                            permissionPreferences.edit(commit = true) {
                                putBoolean(AUDIO_OFF_BY_DENIAL_KEY, true)
                            }
                            vm.onToggleRecordAudio(false)
                            vm.onAppStatus("Recording without audio")
                        }
                    }
                    transition?.let(vm::onHardwareQuickButton)
                }
                if (decision.release) {
                    updateAggregateCameraKeyOwnership(ownedQuickKeys, event.keyCode, ownedAfter = false)
                        ?.let(vm::onHardwareQuickButton)
                }
                if (decision.consume) return true
            }
            else -> if (hasCameraPermission && !vm.state.value.cameraInputBlocked && !showMicrophoneRationale) {
                when (event.keyCode) {
                    // Live-captured 2026-07-09: the camera-control button's slide arrives as the
                    // STANDARD KEYCODE_ZOOM_IN/OUT (168/169), repeating ~20 Hz while the finger
                    // slides. 767 was kept here as a speculative slide-in alias until 2026-07-31,
                    // when it was measured to be the HALF-PRESS (see KEY_CAM_HALF_PRESS_ALT) — the
                    // alias silently ate the light press as a zoom nudge. 769 remains a speculative
                    // slide-out alias only because nothing has ever been measured on it.
                    KeyEvent.KEYCODE_ZOOM_IN -> {
                        if (event.action == KeyEvent.ACTION_DOWN) vm.onHardwareZoomStep(ZOOM_STEP)
                        return true
                    }
                    KeyEvent.KEYCODE_ZOOM_OUT, KEY_CAM_SLIDE_OUT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) vm.onHardwareZoomStep(1f / ZOOM_STEP)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isShutterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_CAMERA

    private fun requestMicrophoneThen(action: PendingAudioAction, block: () -> Unit) {
        val s = vm.state.value
        val needsMicrophone = microphonePermissionRequired(
            action = action,
            videoMode = s.mode == CaptureMode.VIDEO,
            recording = s.isRecording,
            recordAudio = s.recordAudio,
        )
        if (!needsMicrophone || hasMicrophonePermission) {
            block()
            return
        }
        pendingAudioAction = action
        showMicrophoneRationale = true
    }

    /**
     * The microphone was declined — at our own rationale ("Not now") or at the system dialog.
     *
     * A START_RECORDING intent STILL GETS ITS RECORDING, silently. [VideoRecorder] already records
     * video-only whenever RECORD_AUDIO is absent (`doAudio = recordAudio && hasRecordPermission()`
     * → `expectedTracks = 1`), so dropping the press refuses a take the pipeline can fully deliver.
     * Dropping it also stranded anyone who simply never wants audio: turning [recordAudio] off is
     * what makes the NEXT press skip the prompt, so declining used to be a two-press ritual whose
     * first press vanished behind a transient status line (device-verified 2026-07-28 on a fresh
     * install: "Not now" returned to an idle viewfinder with no tally and no clip).
     *
     * Audio is disabled BEFORE starting so the UI toggle and the recorder's own permission gate
     * agree on one answer instead of showing an armed mic over a silent clip; [_state] updates
     * synchronously, so the start below observes it.
     */
    private fun declineMicrophone(action: PendingAudioAction?) {
        // WHY audio went off, not just that it did — see [audioRestoredByMicrophoneGrant]. Without
        // this the refusal outlived itself: a later Settings grant left clips silent and the level
        // meter hidden, and the shutter never re-prompted because the flag it checks was the very
        // flag that was off.
        permissionPreferences.edit(commit = true) { putBoolean(AUDIO_OFF_BY_DENIAL_KEY, true) }
        vm.onToggleRecordAudio(false)
        when (microphoneDeclineOutcome(action)) {
            MicrophoneDeclineOutcome.AUDIO_OFF_AND_RECORD -> {
                vm.onAppStatus("Microphone denied — recording without audio")
                vm.onToggleRecording()
            }
            MicrophoneDeclineOutcome.AUDIO_OFF ->
                  // Mirrors its two siblings exactly — "Microphone <verdict> — <consequence>". It
                  // used to report the verdict alone, and inserted "permission" where they say
                  // nothing, so three lines carried two nouns for one fact. The consequence is the
                  // part the operator acts on: this branch fires when they explicitly asked to turn
                  // audio ON, and onToggleRecordAudio(false) has just run, so the next take is
                  // silent. Withholding that is the confusion AUDIO_OFF_BY_DENIAL exists to remove.
                  vm.onAppStatus("Microphone denied — audio off")
        }
    }

    private fun refreshPermissionState() {
        hasCameraPermission = hasPermission(Manifest.permission.CAMERA)
        hasMicrophonePermission = hasPermission(Manifest.permission.RECORD_AUDIO)
        val requestedBefore = permissionPreferences.getBoolean(CAMERA_REQUESTED_BEFORE_KEY, false)
        if (hasCameraPermission && requestedBefore) {
            // A Settings grant (or a later runtime grant) starts a fresh denial history. This also
            // prevents a future Android auto-reset from inheriting an obsolete pre-grant denial.
            permissionPreferences.edit(commit = true) { putBoolean(CAMERA_REQUESTED_BEFORE_KEY, false) }
        }
        if (audioRestoredByMicrophoneGrant(
                audioDisabledByDenial = permissionPreferences.getBoolean(AUDIO_OFF_BY_DENIAL_KEY, false),
                recordAudio = vm.state.value.recordAudio,
                hasMicrophonePermission = hasMicrophonePermission,
            )
        ) {
            permissionPreferences.edit(commit = true) { putBoolean(AUDIO_OFF_BY_DENIAL_KEY, false) }
            vm.onToggleRecordAudio(true)
            vm.onAppStatus("Microphone allowed — audio on")
        }
        cameraPermanentlyDenied = classifyCameraPermission(
            granted = hasCameraPermission,
            requestedBefore = requestedBefore && !hasCameraPermission,
            shouldShowRationale = !hasCameraPermission &&
                shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
        ) == CameraPermissionDisposition.SETTINGS_REQUIRED
    }

    private fun recordCameraPermissionResult(result: Boolean?) {
        val requestedBefore = permissionPreferences.getBoolean(CAMERA_REQUESTED_BEFORE_KEY, false)
        val updated = updatedCameraPermissionRequestHistory(requestedBefore, result)
        if (updated != requestedBefore) {
            permissionPreferences.edit(commit = true) { putBoolean(CAMERA_REQUESTED_BEFORE_KEY, updated) }
        }
    }

    /** Live visual-media access truth, partial ("Select photos") grants included. */
    private fun hasVisualMediaPermission(): Boolean = hasVisualMediaAccess(
        imagesGranted = hasPermission(Manifest.permission.READ_MEDIA_IMAGES),
        videoGranted = hasPermission(Manifest.permission.READ_MEDIA_VIDEO),
        userSelectedGranted = hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
    )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun openPrivacyPolicy() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())) }
    }

    private companion object {
        const val PRIVACY_POLICY_URL = "https://hletrd.github.io/telecam-pro/privacy-policy/"
        const val PERMISSION_PREFS_NAME = "permission_state"
        const val CAMERA_REQUESTED_BEFORE_KEY = "camera_requested_before"

        /** Whether audio is off BECAUSE a microphone request was refused, not by operator choice. */
        const val AUDIO_OFF_BY_DENIAL_KEY = "audio_off_by_denial"

        // Non-standard OPPO keycodes the Find X9 Ultra camera-control button delivers to the focused app
        // (device-captured via a dispatchKeyEvent log). Two are slide notches, one is the light-press.
        // Directions are a calibrated guess — swap SLIDE_IN/OUT if the on-device zoom goes the wrong way.
        // 767 is the HALF-PRESS, not a slide (device-measured 2026-07-31: a light press on the
        // camera-control button delivered exactly one 767 DOWN/UP pair, 3 ms apart, to the focused
        // app — no ~20 Hz repeat, which is the slide signature; slides are live-verified to arrive
        // as standard KEYCODE_ZOOM_IN/OUT). The old guess routed 767 to a zoom step, which is why
        // the half-press read as dead: every light press nudged zoom instead of firing the
        // half-press action. The stock camera's own interceptor registers
        // {765,766,768,770,771,772,781,782} and NOT 767 — 767 is the code the system re-emits to
        // the focused third-party app.
        const val KEY_CAM_HALF_PRESS_ALT = 767

        // The OPPO quick/action button: the PHYSICAL press is KEYCODE_ACTION_BUTTON_CLICK
        // (scan 735), which the system's StrategyActionButtonKeyLaunchApp intercepts and re-emits
        // to the focused app as an INJECTED 781 DOWN/UP pair (device-measured 2026-07-31;
        // isInjected=true in the system keylog). 781 is also in the stock camera's interceptor
        // list, which is how ITS quick-button capture works.
        const val KEY_CAM_QUICK = 781
        const val KEY_CAM_SLIDE_OUT = 769
        const val KEY_CAM_HALF_PRESS = 782
        // Per-EVENT zoom multiplier: the slide repeats ~20 Hz, so ~1.04/event = a controlled
        // ~2.2x per second of continuous slide (1.15 raced 1x-10x in under two seconds).
        const val ZOOM_STEP = 1.04f

        // Unattended-timelapse dim (perf review #10): 10 s idle grace before dimming, and a 5%
        // brightness floor rather than BRIGHTNESS_OVERRIDE_OFF — the operator can still see at a
        // glance that the run is alive, while the OLED panel drops from ~1 W-class to near-idle.
        const val TIMELAPSE_DIM_GRACE_MS = 10_000L
        const val TIMELAPSE_DIM_BRIGHTNESS = 0.05f
    }

    private val permissionPreferences by lazy {
        getSharedPreferences(PERMISSION_PREFS_NAME, MODE_PRIVATE)
    }

}

@Composable
private fun MicrophonePermissionRationale(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.microphone_permission_title)) },
        text = { Text(stringResource(R.string.microphone_permission_rationale)) },
        confirmButton = {
            MinTouchTarget48 {
                TextButton(onClick = onContinue) {
                    Text(stringResource(R.string.microphone_permission_continue))
                }
            }
        },
        dismissButton = {
            MinTouchTarget48 {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.microphone_permission_not_now))
                }
            }
        },
    )
}

@Composable
private fun PermissionGate(
    permanentlyDenied: Boolean,
    /**
     * The permission is GRANTED but the platform still refuses the camera for this app. Different
     * words from a denial because it is a different fact — and deliberately does NOT name a cause:
     * a work profile, kiosk provisioning, and an OEM privacy manager all produce this, so naming
     * one would be wrong on the others. The action is the same either way: the app's settings page.
     */
    policyBlocked: Boolean = false,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    // Styled with the app's own black-chrome palette instead of stock Material accents — this is
    // the first screen a new user sees, and a default-blue filled button is exactly what "would
    // look odd on a Sony camera screen" (docs/UX_POLICY.md). The primary CTA carries clearly more
    // visual weight than the secondary Privacy link.
    Surface(modifier = Modifier.fillMaxSize(), color = CameraColors.Background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = when {
                    policyBlocked -> "Camera blocked for this app on this device."
                    permanentlyDenied -> "Enable camera access in Settings."
                    else -> "Camera access required."
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = CameraColors.TextPrimary,
            )
            Spacer(Modifier.height(16.dp))
            // A CONTAINER fill, not foreground ink — hence a bare `Color.White` rather than
            // CameraColors.TextPrimary, which is the rule stated on CameraColors. The pairing proves
            // it: the content on top of this is BLACK, so the white here is the surface the CTA is
            // made of. (This is the one white outside ui/; the permission screen predates the token
            // set and draws no camera chrome.)
            val primaryColors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            )
            // 48 dp outer targets (DES4-2): these CTAs gate ALL further use of the app and are the
            // first thing a new user must hit one-handed; material3's bare Button/TextButton stop
            // at a 40 dp container.
            if (permanentlyDenied) {
                MinTouchTarget48 {
                    Button(onClick = onOpenSettings, colors = primaryColors) { Text("Settings") }
                }
            } else {
                MinTouchTarget48 {
                    Button(onClick = onRequest, colors = primaryColors) { Text("Allow camera access") }
                }
            }
            Spacer(Modifier.height(8.dp))
            MinTouchTarget48 {
                TextButton(onClick = onOpenPrivacy) {
                    Text("Privacy Policy", color = CameraColors.TextSecondary)
                }
            }
        }
    }
}
