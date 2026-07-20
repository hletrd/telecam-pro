package com.hletrd.findx9tele.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.ui.theme.FindX9TeleTheme

/**
 * Deterministic debug-only host for screenshot review. It renders the production camera composable
 * with no-op actions, so visual QA never opens Camera2 or configures a physical-camera output.
 */
class UiSnapshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val orientation = intent.getIntExtra(EXTRA_DEVICE_ORIENTATION, 0)
        setContent {
            FindX9TeleTheme {
                var snapshotState by remember {
                    mutableStateOf(
                        CameraUiState(
                            mode = CaptureMode.VIDEO,
                            cameraReady = true,
                            deviceOrientation = orientation,
                        ),
                    )
                }
                // The Gamma tile is a safe, deterministic interaction probe: it updates the real
                // composable in place while this HAL-free fixture keeps every camera action a no-op.
                val snapshotActions = remember {
                    object : CameraActions by PreviewCameraActions {
                        override fun onTransfer(transfer: ColorTransfer) {
                            snapshotState = snapshotState.copy(transfer = transfer)
                        }
                    }
                }
                CameraScreen(
                    state = snapshotState,
                    actions = snapshotActions,
                )
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE_ORIENTATION = "device_orientation"
    }
}
