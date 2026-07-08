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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hletrd.findx9tele.ui.CameraScreen
import com.hletrd.findx9tele.ui.CameraViewModel
import com.hletrd.findx9tele.ui.theme.FindX9TeleTheme

class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()

    // Compose-observable permission state, held on the Activity so onResume (return from the system
    // Settings screen) can re-check and flip the gate without the user re-launching.
    private var hasRequiredPermissions by mutableStateOf(false)
    // True once the user has denied with "don't ask again": the runtime dialog no longer appears, so
    // the CTA must deep-link into App Settings instead of a dead re-request (designer UX-6 / M8).
    private var permanentlyDenied by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hasRequiredPermissions = hasRequiredPermissions()

        // POC: probe whether OPPO's CameraUnit/OCS service authenticates this app (→ path to the stock
        // 300 mm teleconverter OIS). Query-only, off the main thread; see OcsProbe. TODO: remove after POC.
        Thread { com.hletrd.findx9tele.camera.OcsProbe.run(applicationContext) }.start()

        setContent {
            FindX9TeleTheme {
                val state by vm.state.collectAsState()

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    hasRequiredPermissions = hasRequiredPermissions()
                    if (!hasRequiredPermissions) {
                        permanentlyDenied = REQUIRED_PERMISSIONS.any { permission ->
                            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED &&
                                !shouldShowRequestPermissionRationale(permission)
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (!hasRequiredPermissions) launcher.launch(REQUIRED_PERMISSIONS)
                }

                if (hasRequiredPermissions) {
                    CameraScreen(state = state, actions = vm, modifier = Modifier.fillMaxSize())
                } else {
                    PermissionGate(
                        permanentlyDenied = permanentlyDenied,
                        onRequest = { launcher.launch(REQUIRED_PERMISSIONS) },
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
        if (!hasRequiredPermissions && hasRequiredPermissions()) hasRequiredPermissions = true
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
            if (hasRequiredPermissions && event.repeatCount == 0) vm.onHardwareFullKey(active = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isShutterKey(keyCode)) {
            if (hasRequiredPermissions) vm.onHardwareFullKey(active = false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // The Find X9 Ultra camera-control button's capacitive gestures ride the `cs_press` sensor, and the
    // framework DOES surface them to a focused third-party app as (non-standard OPPO) keycodes — device-
    // verified they reach dispatchKeyEvent: a light press and each slide notch arrive as key 767/769/782.
    // Map them: the two slide keycodes → stepped zoom in/out, the press keycode → a centre AF trigger
    // (half-press). Handled on ACTION_DOWN and consumed. (Directions calibrated on device; swap the two
    // slide constants if reversed.)
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (hasRequiredPermissions) {
            when (event.keyCode) {
                KEY_CAM_SLIDE_IN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) vm.onPinchZoom(ZOOM_STEP)
                    return true
                }
                KEY_CAM_SLIDE_OUT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) vm.onPinchZoom(1f / ZOOM_STEP)
                    return true
                }
                KEY_CAM_HALF_PRESS -> {
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

    private fun hasRequiredPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun openPrivacyPolicy() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())) }
    }

    private companion object {
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        const val PRIVACY_POLICY_URL = "https://hletrd.github.io/telecam-pro/privacy-policy/"

        // Non-standard OPPO keycodes the Find X9 Ultra camera-control button delivers to the focused app
        // (device-captured via a dispatchKeyEvent log). Two are slide notches, one is the light-press.
        // Directions are a calibrated guess — swap SLIDE_IN/OUT if the on-device zoom goes the wrong way.
        const val KEY_CAM_SLIDE_IN = 767
        const val KEY_CAM_SLIDE_OUT = 769
        const val KEY_CAM_HALF_PRESS = 782
        val CAMERA_BUTTON_KEYS = setOf(KEY_CAM_SLIDE_IN, KEY_CAM_SLIDE_OUT, KEY_CAM_HALF_PRESS)
        // Per-notch zoom multiplier for the slide gesture.
        const val ZOOM_STEP = 1.15f
    }
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
                    "Camera or microphone access is off. Enable both permissions in Settings to use the app."
                } else {
                    "TeleCam Pro needs Camera for the viewfinder and capture, and Microphone for video sound."
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            if (permanentlyDenied) {
                Button(onClick = onOpenSettings) { Text("Open Settings") }
            } else {
                Button(onClick = onRequest) { Text("Grant Permissions") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenPrivacy) { Text("Privacy Policy") }
        }
    }
}
