package com.hletrd.findx9tele

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.ui.CameraActions
import com.hletrd.findx9tele.ui.CameraScreen
import com.hletrd.findx9tele.ui.CameraViewModel
import com.hletrd.findx9tele.ui.theme.FindX9TeleTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshPermissionState()

        // Debug-only CameraUnit availability check. Query-only, off the main thread; see OcsProbe.
        // TODO: remove once the 300 mm OIS integration path is settled.
        Thread { com.hletrd.findx9tele.camera.OcsProbe.run(applicationContext) }.start()

        setContent {
            FindX9TeleTheme {
                val state by vm.state.collectAsState()

                val cameraLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { result ->
                    refreshPermissionState()
                    if (!result) updateCameraPermanentDenial()
                }
                val microphoneLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    hasMicrophonePermission = hasPermission(Manifest.permission.RECORD_AUDIO)
                    val action = pendingAudioAction
                    pendingAudioAction = null
                    if (!granted) {
                        vm.onToggleRecordAudio(false)
                        vm.onAppStatus("Microphone permission denied")
                        return@rememberLauncherForActivityResult
                    }
                    when (action) {
                        PendingAudioAction.ENABLE_AUDIO -> vm.onToggleRecordAudio(true)
                        PendingAudioAction.START_RECORDING -> vm.onToggleRecording()
                        null -> Unit
                    }
                }

                LaunchedEffect(Unit) {
                    if (!hasCameraPermission) cameraLauncher.launch(Manifest.permission.CAMERA)
                }

                if (hasCameraPermission) {
                    val permissionAwareActions = remember(state.mode, state.isRecording, state.recordAudio, hasMicrophonePermission) {
                        object : CameraActions by vm {
                            override fun onToggleRecording() {
                                requestMicrophoneThen(
                                    action = PendingAudioAction.START_RECORDING,
                                    launcher = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    block = vm::onToggleRecording,
                                )
                            }

                            override fun onToggleRecordAudio(enabled: Boolean) {
                                if (!enabled) {
                                    vm.onToggleRecordAudio(false)
                                    return
                                }
                                requestMicrophoneThen(
                                    action = PendingAudioAction.ENABLE_AUDIO,
                                    launcher = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                ) { vm.onToggleRecordAudio(true) }
                            }
                        }
                    }
                    CameraScreen(state = state, actions = permissionAwareActions, modifier = Modifier.fillMaxSize())
                } else {
                    PermissionGate(
                        permanentlyDenied = cameraPermanentlyDenied,
                        onRequest = { cameraLauncher.launch(Manifest.permission.CAMERA) },
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
            if (hasCameraPermission && event.repeatCount == 0) {
                val s = vm.state.value
                if (s.mode == CaptureMode.VIDEO && !s.isRecording && s.recordAudio && !hasMicrophonePermission) {
                    vm.onToggleRecordAudio(false)
                    vm.onAppStatus("Recording without audio")
                }
                vm.onHardwareFullKey(active = true)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isShutterKey(keyCode)) {
            if (hasCameraPermission) vm.onHardwareFullKey(active = false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // The Find X9 Ultra camera-control button's capacitive gestures ride the `cs_press` sensor. As
    // live-verified 2026-07-09, the slide reaches a focused third-party app as the STANDARD
    // KEYCODE_ZOOM_IN/OUT (repeating ~20 Hz) — the OPPO codes 767/769/782 seen in one earlier
    // session are config-dependent and kept only as aliases; the light press is currently NOT
    // delivered at all (the KEYCODE_FOCUS/782 handlers stay armed if it ever arrives). Slides →
    // stepped zoom via the eased target; press/half-press → the configurable HardwareKeyActions.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // DEBUG: trace every non-standard key so the camera-button gestures can be re-mapped from a
        // live session — the codes seen once (767/769/782) may not be the full story (user reports
        // slide/half-press dead while full press works).
        if (BuildConfig.DEBUG && !isShutterKey(event.keyCode) && event.keyCode != KeyEvent.KEYCODE_BACK) {
            android.util.Log.i("BtnDbg", "key code=${event.keyCode} action=${event.action} perm=$hasCameraPermission")
        }
        if (hasCameraPermission) {
            when (event.keyCode) {
                // Live-captured 2026-07-09: the camera-control button's slide arrives as the STANDARD
                // KEYCODE_ZOOM_IN/OUT (168/169), repeating ~20 Hz while the finger slides — NOT the
                // OPPO 767/769 codes seen in one earlier session (kept as aliases just in case).
                KeyEvent.KEYCODE_ZOOM_IN, KEY_CAM_SLIDE_IN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) vm.onHardwareZoomStep(ZOOM_STEP)
                    return true
                }
                KeyEvent.KEYCODE_ZOOM_OUT, KEY_CAM_SLIDE_OUT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) vm.onHardwareZoomStep(1f / ZOOM_STEP)
                    return true
                }
                // Half-press = the standard camera-family KEYCODE_FOCUS (DOWN engages, UP releases).
                KeyEvent.KEYCODE_FOCUS, KEY_CAM_HALF_PRESS -> {
                    if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                        vm.onHardwareHalfPress(event.action == KeyEvent.ACTION_DOWN)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isShutterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_CAMERA

    private fun requestMicrophoneThen(action: PendingAudioAction, launcher: () -> Unit, block: () -> Unit) {
        val s = vm.state.value
        val needsMicrophone = when (action) {
            PendingAudioAction.ENABLE_AUDIO -> true
            PendingAudioAction.START_RECORDING -> s.mode == CaptureMode.VIDEO && !s.isRecording && s.recordAudio
        }
        if (!needsMicrophone || hasMicrophonePermission) {
            block()
            return
        }
        pendingAudioAction = action
        launcher()
    }

    private fun refreshPermissionState() {
        hasCameraPermission = hasPermission(Manifest.permission.CAMERA)
        hasMicrophonePermission = hasPermission(Manifest.permission.RECORD_AUDIO)
        if (!hasCameraPermission) updateCameraPermanentDenial()
    }

    private fun updateCameraPermanentDenial() {
        cameraPermanentlyDenied = !hasPermission(Manifest.permission.CAMERA) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }

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

        // Non-standard OPPO keycodes the Find X9 Ultra camera-control button delivers to the focused app
        // (device-captured via a dispatchKeyEvent log). Two are slide notches, one is the light-press.
        // Directions are a calibrated guess — swap SLIDE_IN/OUT if the on-device zoom goes the wrong way.
        const val KEY_CAM_SLIDE_IN = 767
        const val KEY_CAM_SLIDE_OUT = 769
        const val KEY_CAM_HALF_PRESS = 782
        // Per-EVENT zoom multiplier: the slide repeats ~20 Hz, so ~1.04/event = a controlled
        // ~2.2x per second of continuous slide (1.15 raced 1x-10x in under two seconds).
        const val ZOOM_STEP = 1.04f
    }

    private enum class PendingAudioAction { ENABLE_AUDIO, START_RECORDING }
}

@Composable
private fun PermissionGate(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (permanentlyDenied) {
                    "Camera access is off. Enable it in Settings."
                } else {
                    "Camera is required. Mic is used for video only."
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            if (permanentlyDenied) {
                Button(onClick = onOpenSettings) { Text("Settings") }
            } else {
                Button(onClick = onRequest) { Text("Grant Camera") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenPrivacy) { Text("Privacy") }
        }
    }
}
