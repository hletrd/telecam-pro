package me.hletrd.telecampro

import android.content.pm.ActivityInfo
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import me.hletrd.telecampro.ui.controls.MinTouchTarget48
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.ui.CameraActions
import me.hletrd.telecampro.ui.CameraScreen
import me.hletrd.telecampro.ui.CameraViewModel
import me.hletrd.telecampro.ui.ExternalNavigationFailure
import me.hletrd.telecampro.ui.ExternalNavigationRecovery
import me.hletrd.telecampro.ui.ExternalNavigationTarget
import me.hletrd.telecampro.ui.OwnerlessMediaDeleteConsentResult
import me.hletrd.telecampro.ui.OwnerlessMediaDeleteLaunch
import me.hletrd.telecampro.ui.OwnerlessMediaDeletePreparation
import me.hletrd.telecampro.ui.OwnerlessMediaDeleteRequest
import me.hletrd.telecampro.ui.PrivacyPolicyFallbackDialog
import me.hletrd.telecampro.ui.externalNavigationFailure
import me.hletrd.telecampro.ui.launchExternal
import me.hletrd.telecampro.ui.windowFollowsDevice
import me.hletrd.telecampro.ui.theme.CameraColors
import me.hletrd.telecampro.ui.theme.TeleCamProTheme

private const val READ_MEDIA_VISUAL_USER_SELECTED_PERMISSION =
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

private const val OBSCURED_TOUCH_FLAGS =
    MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED

/** One side-effect-free decision seam for the Activity's full- and partial-overlay boundary. */
internal fun touchEventIsUnobscured(flags: Int): Boolean = flags and OBSCURED_TOUCH_FLAGS == 0

/** AndroidX turns a synchronous SendIntentException into a canceled result carrying this marker. */
internal fun convertedSendIntentExceptionMarker(
    action: String?,
    hasExceptionExtra: Boolean,
): Boolean = action ==
    ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST &&
    hasExceptionExtra

/** Pure classification keeps a framework launch failure distinct from an operator cancellation. */
internal fun classifyOwnerlessMediaDeleteActivityResult(
    resultCode: Int,
    dataAction: String?,
    hasConvertedExceptionExtra: Boolean,
): OwnerlessMediaDeleteConsentResult = when {
    resultCode == android.app.Activity.RESULT_OK -> OwnerlessMediaDeleteConsentResult.APPROVED
    convertedSendIntentExceptionMarker(dataAction, hasConvertedExceptionExtra) ->
        OwnerlessMediaDeleteConsentResult.LAUNCH_FAILED
    else -> OwnerlessMediaDeleteConsentResult.CANCELED
}

/** Builds and launches only the exact request already claimed from ViewModel ownership. */
internal fun launchOwnerlessMediaDeleteRequest(
    ready: OwnerlessMediaDeleteLaunch,
    launch: (IntentSenderRequest) -> Unit,
    onFailure: (OwnerlessMediaDeleteRequest) -> Unit,
): Boolean = runCatching {
    launch(IntentSenderRequest.Builder(ready.pendingIntent.intentSender).build())
}.fold(
    onSuccess = { true },
    onFailure = {
        onFailure(ready.request)
        false
    },
)

/**
 * Pins light system-bar icons over TeleCamPro's unconditionally dark surface.
 *
 * [SystemBarStyle.dark] describes the bar background, so it selects light icons. The bare
 * [enableEdgeToEdge] overload follows the system night setting instead, which can put black icons
 * over the dark viewfinder and makes debug screenshot evidence depend on device theme state.
 */
internal fun ComponentActivity.enableTeleCamEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
}

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

    /**
     * Rejects overlay-marked pointer input before it can enter the Compose tree.
     *
     * Android's view-level [android.view.View.filterTouchesWhenObscured] covers full obscuration,
     * but does not reject [MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED]. Keeping the policy here
     * makes both flags apply uniformly to Shutter, REC, settings/permission, and both delete taps.
     * Every unflagged event is delegated unchanged, including multi-touch/pinch, stylus/mouse,
     * accessibility-generated input, and system-gesture negotiation.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!touchEventIsUnobscured(event.flags)) return false
        return super.dispatchTouchEvent(event)
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

    /** Consumes only commands admitted by the debug manifest's DUMP-protected component. */
    private fun consumeProtectedDebugCameraCommand() {
        if (!BuildConfig.DEBUG) return
        val command = DebugCameraControlMailbox.consume() ?: return
        command.zslSpike?.let(vm::debugSetZslSpike)
        command.zoomRatio?.let(vm::debugApplyZoom)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Intent extras on this ordinary exported launcher are deliberately inert. The protected
        // debug component publishes through the process-local mailbox before delivering this edge.
        consumeProtectedDebugCameraCommand()
    }

    /**
     * Portrait-locks HANDSETS at runtime, and leaves anything sw600dp+ free to rotate.
     *
     * This used to be `android:screenOrientation="portrait"` in the manifest. The behaviour is the
     * same — Android 16 already ignores that attribute at sw600dp+ and API 37 removes the opt-out
     * entirely, so the lock was only ever reaching handsets. What changed is that Play's
     * large-screen check reads the manifest STATICALLY: it cannot see that the lock is conditional,
     * so the attribute earned a permanent advisory claiming the app is orientation-locked on
     * tablets when it is not. Expressing the same rule in code says the true thing to both.
     *
     * Briefly lifted on 2026-08-05 so a sideways phone would get a re-laid-out landscape window, and
     * restored the same day: the owner's rule is that orientation moves NO control ("placing shutter
     * button regardless of screen orientation, and also gallery button and functional buttons"). A
     * turned handset window is ~420 dp tall, which cannot hold the one arrangement, so the only way
     * to keep the shutter where the thumb expects it is to not turn the window. Everything the
     * operator has to READ still follows the device, via the glyph residual.
     *
     * `smallestScreenWidthDp` is the right axis: it is the width of the SHORTER side, so it does
     * not change when the device turns, and 600 is the platform's own handset/tablet boundary.
     */
    private fun lockPortraitOnHandsets() {
        requestedOrientation = if (windowFollowsDevice(resources.configuration.smallestScreenWidthDp)) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockPortraitOnHandsets()
        // Both bars are pinned DARK — meaning "this bar sits on a dark background", which is how
        // the system decides to draw LIGHT (white) icons: SystemBarStyle.dark sets
        // detectDarkMode = { true }, and setUp() applies isAppearanceLightStatusBars = !isDark.
        // The bare enableEdgeToEdge() default is SystemBarStyle.auto(), which resolves that from the
        // SYSTEM night setting — but TeleCamProTheme is unconditionally dark (TeleDarkColorScheme,
        // no isSystemInDarkTheme, no values-night), so a phone in LIGHT mode drew BLACK status and
        // navigation icons over the viewfinder. A viewfinder is dark at every hour and in every
        // scene, so the app's own appearance is the only correct input here, never the system's.
        // The theme's android:windowLightStatusBar=false was aimed at exactly this and cannot reach
        // it: it seeds the starting window, and then this call overwrites the appearance flags.
        enableTeleCamEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        consumeProtectedDebugCameraCommand()
        // Defense in depth for child Views: dispatchTouchEvent above is the authoritative full +
        // partial overlay boundary; this platform flag independently retains the full-obscuration
        // filter if a child is ever dispatched through another framework path.
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
                var externalFailure by remember { mutableStateOf<ExternalNavigationFailure?>(null) }
                var showPrivacyFallback by remember { mutableStateOf(false) }
                val openExternal: (ExternalNavigationTarget) -> Unit = { target ->
                    externalFailure = externalNavigationFailure(target, launchExternal(this, target))
                }

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
                    // The visual-media picker/dialog has released its full-screen input ownership.
                    vm.onCameraInputBlockedChange(false)
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
                    // The system permission dialog has released its full-screen input ownership.
                    vm.onCameraInputBlockedChange(false)
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
                val ownerlessDeleteLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult(),
                ) { result ->
                    vm.onOwnerlessMediaDeleteConsentResult(
                        classifyOwnerlessMediaDeleteActivityResult(
                            resultCode = result.resultCode,
                            dataAction = result.data?.action,
                            hasConvertedExceptionExtra = result.data?.hasExtra(
                                ActivityResultContracts.StartIntentSenderForResult
                                    .EXTRA_SEND_INTENT_EXCEPTION,
                            ) == true,
                        ),
                    )
                }

                // StateFlow replays a request created before Activity recreation. Claiming the
                // exact generation immediately before launch prevents an old provider completion
                // from opening a system surface for a replacement review.
                LaunchedEffect(ownerlessDeleteLauncher) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        vm.ownerlessMediaDeleteLaunch.collect { launch ->
                            if (launch == null || !vm.claimOwnerlessMediaDeleteLaunch(launch)) {
                                return@collect
                            }
                            launchOwnerlessMediaDeleteRequest(
                                ready = launch,
                                launch = ownerlessDeleteLauncher::launch,
                                onFailure = { request ->
                                    vm.onOwnerlessMediaDeleteConsentResult(
                                        request,
                                        OwnerlessMediaDeleteConsentResult.LAUNCH_FAILED,
                                    )
                                },
                            )
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (!hasCameraPermission && !cameraPermanentlyDenied) {
                        cameraLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    }
                }

                // This gate replaces CameraScreen rather than opening through its modal state.
                // Acquire the same input owner so a pending one-shot timer cannot survive a policy
                // transition and fire behind the permission surface.
                LaunchedEffect(state.cameraPolicyBlocked) {
                    if (state.cameraPolicyBlocked) vm.onCameraInputBlockedChange(true)
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

                            override fun onDeleteLastMedia(
                                uri: android.net.Uri,
                                provenance: me.hletrd.telecampro.storage.MediaProvenance,
                            ) {
                                when (val preparation = vm.prepareOwnerlessMediaDelete(uri, provenance)) {
                                    OwnerlessMediaDeletePreparation.DirectAppOwned ->
                                        vm.onDeleteLastMedia(uri, provenance)
                                    OwnerlessMediaDeletePreparation.Rejected -> Unit
                                    is OwnerlessMediaDeletePreparation.ConsentRequired ->
                                        vm.beginOwnerlessMediaDeleteRequestCreation(preparation.request)
                                }
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
                                // tap IS the context, so the system dialog needs no rationale sheet.
                                //
                                // Branches on the access LEVEL, not on "is there any access"
                                // (2026-08-06): a partial "Select photos" grant answers yes to the
                                // latter, so this used to fall straight to the re-restore — which is
                                // the very query that just came back empty. That left a partial-grant
                                // user who had not hand-picked their own captures with no way to
                                // widen the selection, ever. FULL still falls through to the
                                // re-restore (it covers a grant made in Settings mid-session, and
                                // there is nothing further to ask for).
                                if (shouldRequestVisualMediaAccess(visualMediaAccess())) {
                                    vm.onCameraInputBlockedChange(true)
                                    val permissions = visualMediaPermissionsToRequest(
                                        imagesGranted = hasPermission(Manifest.permission.READ_MEDIA_IMAGES),
                                        videoGranted = hasPermission(Manifest.permission.READ_MEDIA_VIDEO),
                                        userSelectedGranted = hasPermission(
                                            READ_MEDIA_VISUAL_USER_SELECTED_PERMISSION,
                                        ),
                                        userSelectedPermissionAvailable = android.os.Build.VERSION.SDK_INT >= 34,
                                    ).map { permission ->
                                        when (permission) {
                                            VisualMediaPermission.IMAGES -> Manifest.permission.READ_MEDIA_IMAGES
                                            VisualMediaPermission.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
                                            VisualMediaPermission.USER_SELECTED ->
                                                READ_MEDIA_VISUAL_USER_SELECTED_PERMISSION
                                        }
                                    }.toTypedArray()
                                    mediaAccessLauncher.launch(permissions)
                                } else {
                                    vm.onGalleryAccessRequested()
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
                            onOpenSettings = { openExternal(ExternalNavigationTarget.APP_SETTINGS) },
                            onOpenPrivacy = { openExternal(ExternalNavigationTarget.PRIVACY_POLICY) },
                            externalFailure = externalFailure,
                            onOpenPrivacyInApp = { showPrivacyFallback = true },
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
                                vm.onCameraInputBlockedChange(false)
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
                        onOpenSettings = { openExternal(ExternalNavigationTarget.APP_SETTINGS) },
                        onOpenPrivacy = { openExternal(ExternalNavigationTarget.PRIVACY_POLICY) },
                        externalFailure = externalFailure,
                        onOpenPrivacyInApp = { showPrivacyFallback = true },
                    )
                }
                if (showPrivacyFallback) {
                    PrivacyPolicyFallbackDialog(onDismiss = { showPrivacyFallback = false })
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
                        vm.onAppStatus(CameraStatusMessage.RECORDING_WITHOUT_AUDIO)
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

    // The Find X9 Ultra camera-control button's capacitive gestures ride the `cs_press` sensor.
    // Slides reach a focused third-party app as standard KEYCODE_ZOOM_IN/OUT (168/169, repeating
    // ~20 Hz; live-verified 2026-07-09). A light press is device-measured as one non-repeating 767
    // DOWN/UP pair and routes to the half-press action; KEYCODE_FOCUS/782 remain half-press-family
    // siblings. The unmeasured 769 is only a speculative slide-out alias, while 781 is the separate
    // measured quick button. Slides → eased stepped zoom; press/half-press/quick → their configurable
    // HardwareKeyActions. The authoritative 767 measurement is documented beside its constant.
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
                            vm.onAppStatus(CameraStatusMessage.RECORDING_WITHOUT_AUDIO)
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
                            vm.onAppStatus(CameraStatusMessage.RECORDING_WITHOUT_AUDIO)
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
        // Acquire before Compose can draw the rationale. Besides blocking physical keys, this
        // synchronously cancels any pending one-shot timer through the ViewModel ownership seam.
        vm.onCameraInputBlockedChange(true)
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
                vm.onAppStatus(CameraStatusMessage.MICROPHONE_DENIED_RECORDING_WITHOUT_AUDIO)
                vm.onToggleRecording()
            }
            MicrophoneDeclineOutcome.AUDIO_OFF ->
                  // Mirrors its two siblings exactly — "Microphone <verdict> — <consequence>". It
                  // used to report the verdict alone, and inserted "permission" where they say
                  // nothing, so three lines carried two nouns for one fact. The consequence is the
                  // part the operator acts on: this branch fires when they explicitly asked to turn
                  // audio ON, and onToggleRecordAudio(false) has just run, so the next take is
                  // silent. Withholding that is the confusion AUDIO_OFF_BY_DENIAL exists to remove.
                  vm.onAppStatus(CameraStatusMessage.MICROPHONE_DENIED_AUDIO_OFF)
        }
    }

    private fun refreshPermissionState() {
        hasCameraPermission = hasPermission(Manifest.permission.CAMERA)
        if (!hasCameraPermission) vm.onCameraInputBlockedChange(true)
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
            vm.onAppStatus(CameraStatusMessage.MICROPHONE_ALLOWED_AUDIO_ON)
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
        userSelectedGranted = hasPermission(READ_MEDIA_VISUAL_USER_SELECTED_PERMISSION),
    )

    /** Live access LEVEL — the finer answer, so a partial grant is distinguishable from a full one. */
    private fun visualMediaAccess(): VisualMediaAccess = visualMediaAccessLevel(
        imagesGranted = hasPermission(Manifest.permission.READ_MEDIA_IMAGES),
        videoGranted = hasPermission(Manifest.permission.READ_MEDIA_VIDEO),
        userSelectedGranted = hasPermission(READ_MEDIA_VISUAL_USER_SELECTED_PERMISSION),
    )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
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
internal fun MicrophonePermissionRationale(
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
internal fun PermissionGate(
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
    externalFailure: ExternalNavigationFailure? = null,
    onOpenPrivacyInApp: () -> Unit = {},
) {
    // Styled with the app's own black-chrome palette instead of stock Material accents — this is
    // the first screen a new user sees, and a default-blue filled button is exactly what "would
    // look odd on a Sony camera screen" (docs/UX_POLICY.md). The primary CTA carries clearly more
    // visual weight than the secondary Privacy link.
    Surface(modifier = Modifier.fillMaxSize(), color = CameraColors.Background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val availableHeight = maxHeight
            val compact = availableHeight < 480.dp || LocalDensity.current.fontScale >= 1.5f
            Column(
                // Keep the centered first-run composition on ordinary windows, but give compact
                // windows a bounded scroll owner. heightIn preserves a viewport-height content box
                // when the copy is short and expands naturally when EN/KO large-font copy or an
                // external-navigation recovery exceeds it.
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = availableHeight)
                    .padding(horizontal = 24.dp, vertical = if (compact) 12.dp else 24.dp),
                verticalArrangement = if (compact) Arrangement.Top else Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when {
                        policyBlocked -> stringResource(R.string.camera_permission_policy_blocked)
                        permanentlyDenied -> stringResource(R.string.camera_permission_permanently_denied)
                        else -> stringResource(R.string.camera_permission_required)
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
                        Button(onClick = onOpenSettings, colors = primaryColors) { Text(stringResource(R.string.action_settings)) }
                    }
                } else {
                    MinTouchTarget48 {
                        Button(onClick = onRequest, colors = primaryColors) { Text(stringResource(R.string.action_allow_camera_access)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                MinTouchTarget48 {
                    TextButton(onClick = onOpenPrivacy) {
                        Text(stringResource(R.string.action_privacy_policy), color = CameraColors.TextSecondary)
                    }
                }
                ExternalNavigationRecovery(
                    failure = externalFailure,
                    onOpenPrivacyInApp = onOpenPrivacyInApp,
                )
            }
        }
    }
}
