package com.hletrd.findx9tele.ui

import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.util.Range
import android.util.Rational
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hletrd.findx9tele.camera.AspectRatio
import com.hletrd.findx9tele.camera.CameraCaps
import com.hletrd.findx9tele.camera.CameraUiState
import com.hletrd.findx9tele.camera.CaptureMode
import com.hletrd.findx9tele.camera.ColorTransfer
import com.hletrd.findx9tele.camera.ExposureMode
import com.hletrd.findx9tele.camera.FnSlot
import com.hletrd.findx9tele.camera.LensChoice
import com.hletrd.findx9tele.camera.MemorySlot
import com.hletrd.findx9tele.camera.PhotoSessionOutputs
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
        val scenario = intent.getStringExtra(EXTRA_SCENARIO)
        setContent {
            FindX9TeleTheme {
                var snapshotState by remember {
                    mutableStateOf(snapshotState(scenario, orientation))
                }
                // The Gamma tile is a safe, deterministic interaction probe: it updates the real
                // composable in place while this HAL-free fixture keeps every camera action a no-op.
                val snapshotActions = remember {
                    object : CameraActions by PreviewCameraActions {
                        override fun onTransfer(transfer: ColorTransfer) {
                            snapshotState = snapshotState.copy(transfer = transfer)
                        }

                        override fun onExposureMode(mode: ExposureMode) {
                            snapshotState = snapshotState.copy(
                                controls = snapshotState.controls.copy(exposureMode = mode),
                            )
                        }

                        override fun onIso(iso: Int) {
                            snapshotState = snapshotState.copy(
                                controls = snapshotState.controls.copy(iso = iso),
                            )
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
        const val EXTRA_SCENARIO = "snapshot_scenario"

        const val SCENARIO_DEFAULT = "default"
        const val SCENARIO_MEMORY = "memory"
        const val SCENARIO_ADJUSTMENT = "adjustment"
        const val SCENARIO_LOUPE = "loupe"
    }
}

/**
 * HAL-free fixtures for screenshot and accessibility evidence. Every scenario feeds the shipping
 * [CameraScreen]; the capability-rich variants only unlock Compose controls and never open Camera2.
 */
private fun snapshotState(scenario: String?, orientation: Int): CameraUiState {
    val common = CameraUiState(
        mode = CaptureMode.VIDEO,
        cameraReady = true,
        deviceOrientation = orientation,
    )
    return when (scenario ?: UiSnapshotActivity.SCENARIO_DEFAULT) {
        UiSnapshotActivity.SCENARIO_MEMORY -> common.copy(
            mode = CaptureMode.PHOTO,
            activeMemorySlot = MemorySlot.MR1,
            savedMemorySlots = setOf(MemorySlot.MR1, MemorySlot.MR2, MemorySlot.MR3),
            photoSessionOutputs = PhotoSessionOutputs(processed = true),
        )
        UiSnapshotActivity.SCENARIO_ADJUSTMENT -> common.copy(
            mode = CaptureMode.PHOTO,
            caps = snapshotCameraCaps(),
            photoFnSlots = listOf(
                FnSlot.ISO,
                FnSlot.SHUTTER,
                FnSlot.FOCUS,
                FnSlot.WB,
                FnSlot.EV,
                FnSlot.ZOOM,
            ),
            photoSessionOutputs = PhotoSessionOutputs(processed = true),
        )
        UiSnapshotActivity.SCENARIO_LOUPE -> common.copy(
            mode = CaptureMode.PHOTO,
            aspectRatio = AspectRatio.W4_3,
            previewAspect = 3f / 4f,
            caps = snapshotCameraCaps(),
            lens = LensChoice.TELE3X,
            teleconverterMode = true,
            punchIn = true,
            teleFinder = true,
            photoSessionOutputs = PhotoSessionOutputs(processed = true),
        )
        else -> common
    }
}

private fun snapshotCameraCaps(): CameraCaps = CameraCaps(
    logicalId = "snapshot",
    physicalId = "snapshot-tele",
    sensorOrientation = 90,
    minFocusDistanceDiopters = 10f,
    hyperfocalDiopters = 0.2f,
    isoRange = Range(50, 12_750),
    exposureTimeRange = Range(125_000L, 4_000_000_000L),
    maxFrameDurationNs = 4_000_000_000L,
    evRange = Range(-9, 9),
    evStep = Rational(1, 3),
    focalLengthsMm = floatArrayOf(20.1f),
    equivalentFocalMm = 70f,
    lensFocalLengthMm = 20.1f,
    lensApertureF = 2.2f,
    nativeFocalInImageWidths = 1f,
    supportsManualSensor = true,
    supportsManualPostProcessing = true,
    supportsRaw = false,
    rawSize = null,
    supportedDynamicRangeProfiles = emptySet(),
    largestJpegSize = Size(4080, 3064),
    largestYuvSize = Size(4080, 3064),
    isLogicalMultiCamera = true,
    oisAvailable = true,
    flashAvailable = false,
    zoomRatioRange = Range(0.6f, 20f),
    videoStabModes = intArrayOf(
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
    ),
    afModes = intArrayOf(
        CameraMetadata.CONTROL_AF_MODE_OFF,
        CameraMetadata.CONTROL_AF_MODE_AUTO,
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
    ),
    awbModes = intArrayOf(
        CameraMetadata.CONTROL_AWB_MODE_OFF,
        CameraMetadata.CONTROL_AWB_MODE_AUTO,
    ),
    aeModes = intArrayOf(
        CameraMetadata.CONTROL_AE_MODE_OFF,
        CameraMetadata.CONTROL_AE_MODE_ON,
    ),
    maxAeRegions = 1,
    maxAfRegions = 1,
    antibandingModes = intArrayOf(
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF,
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO,
    ),
    effectModes = intArrayOf(CameraMetadata.CONTROL_EFFECT_MODE_OFF),
    edgeModes = intArrayOf(CameraMetadata.EDGE_MODE_OFF),
    noiseReductionModes = intArrayOf(CameraMetadata.NOISE_REDUCTION_MODE_OFF),
    availableFpsRanges = arrayOf(Range(30, 30)),
    availableVideoSizes = listOf(Size(3840, 2160)),
    openGateVideoSizes = listOf(Size(4080, 3064)),
    highSpeedConfigs = emptyMap(),
)
