package me.hletrd.telecampro.ui

import android.content.Intent
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.util.Range
import android.util.Rational
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.CameraCaps
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.FocusConfidenceSource
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.MediaDeleteScope
import me.hletrd.telecampro.MicrophonePermissionRationale
import me.hletrd.telecampro.PermissionGate
import me.hletrd.telecampro.R
import me.hletrd.telecampro.ui.controls.ProSheet
import me.hletrd.telecampro.ui.controls.ProSheetTab
import me.hletrd.telecampro.ui.review.ReviewCriticalStatus
import me.hletrd.telecampro.ui.review.ReviewCriticalUiState
import me.hletrd.telecampro.ui.review.ReviewDeleteConfirmationDialog
import me.hletrd.telecampro.ui.theme.TeleCamProTheme
import androidx.compose.ui.unit.Density

/**
 * Deterministic debug-only host for screenshot review. It renders the production camera composable
 * with no-op actions, so visual QA never opens Camera2 or configures a physical-camera output.
 */
class UiSnapshotActivity : ComponentActivity() {
    private var snapshotRequest by mutableStateOf(SnapshotRequest())
    private var snapshotGeneration by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptSnapshotIntent(intent)
        setContent {
            TeleCamProTheme {
                val request = snapshotRequest
                val generation = snapshotGeneration
                val layoutDirection = if (request.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalLayoutDirection provides layoutDirection,
                    LocalDensity provides Density(density.density, request.fontScale),
                ) {
                    // A new intent is a new evidence scene even when its extras equal the preceding
                    // request. Key the whole stateful production subtree, not only the fixture data.
                    key(generation) {
                        var snapshotState by remember {
                            mutableStateOf(snapshotState(request.scenario, request.orientation))
                        }
                        // The Gamma tile is a safe, deterministic interaction probe: it updates the
                        // real composable while every physical-camera action remains a no-op.
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
                        Box(modifier = Modifier.fillMaxSize()) {
                            SnapshotSurface(
                                request = request,
                                state = snapshotState,
                                actions = snapshotActions,
                            )
                            SnapshotStateProbe(
                                scenario = request.scenario ?: UiSnapshotActivity.SCENARIO_DEFAULT,
                                transfer = snapshotState.transfer,
                                layoutDirection = layoutDirection,
                                modifier = Modifier.align(AbsoluteAlignment.TopLeft),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSnapshotIntent(intent)
    }

    private fun acceptSnapshotIntent(intent: Intent) {
        snapshotRequest = intent.snapshotRequest()
        snapshotGeneration++
    }

    companion object {
        const val EXTRA_DEVICE_ORIENTATION = "device_orientation"
        const val EXTRA_LAYOUT_DIRECTION_RTL = "snapshot_rtl"
        const val EXTRA_SCENARIO = "snapshot_scenario"
        const val EXTRA_FONT_SCALE = "snapshot_font_scale"

        const val SCENARIO_DEFAULT = "default"
        const val SCENARIO_MEMORY = "memory"
        const val SCENARIO_ADJUSTMENT = "adjustment"
        const val SCENARIO_LOUPE = "loupe"
        const val SCENARIO_MAXIMAL_OSD = "maximal_osd"
        const val SCENARIO_FN = "fn"
        const val SCENARIO_SETTINGS = "settings"
        const val SCENARIO_SETTINGS_FN = "settings_fn"
        const val SCENARIO_REVIEW_RAW = "review_raw"
        const val SCENARIO_REVIEW_LOADING = "review_loading"
        const val SCENARIO_REVIEW_ERROR = "review_error"
        const val SCENARIO_REVIEW_DELETE = "review_delete"
        const val SCENARIO_PERMISSION = "permission"
        const val SCENARIO_PERMISSION_DENIED = "permission_denied"
        const val SCENARIO_MIC_RATIONALE = "microphone_rationale"
    }
}

private data class SnapshotRequest(
    val scenario: String? = null,
    val orientation: Int = 0,
    val rtl: Boolean = false,
    val fontScale: Float = 1f,
)

private fun Intent.snapshotRequest(): SnapshotRequest = SnapshotRequest(
    scenario = getStringExtra(UiSnapshotActivity.EXTRA_SCENARIO),
    orientation = getIntExtra(UiSnapshotActivity.EXTRA_DEVICE_ORIENTATION, 0),
    rtl = getBooleanExtra(UiSnapshotActivity.EXTRA_LAYOUT_DIRECTION_RTL, false),
    fontScale = getFloatExtra(UiSnapshotActivity.EXTRA_FONT_SCALE, 1f).coerceIn(0.85f, 2f),
)

@Composable
private fun SnapshotSurface(
    request: SnapshotRequest,
    state: CameraUiState,
    actions: CameraActions,
) {
    val settingsTab = snapshotSettingsTab(request.scenario)
    if (settingsTab != null) {
        ProSheet(state = state, actions = actions, onDismiss = {}, initialTab = settingsTab)
        return
    }
    when (request.scenario) {
        UiSnapshotActivity.SCENARIO_FN -> FnOverlay(
            state = state,
            actions = actions,
            onSelectManualDial = {},
            onDismiss = {},
            glyphRotation = request.orientation.toFloat(),
        )
        UiSnapshotActivity.SCENARIO_REVIEW_RAW -> ReviewCriticalStatus(
            ReviewCriticalUiState.Raw,
            request.orientation.toFloat(),
            onRetry = {},
            modifier = Modifier.fillMaxSize(),
        )
        UiSnapshotActivity.SCENARIO_REVIEW_LOADING -> ReviewCriticalStatus(
            ReviewCriticalUiState.Loading,
            request.orientation.toFloat(),
            onRetry = {},
            modifier = Modifier.fillMaxSize(),
        )
        UiSnapshotActivity.SCENARIO_REVIEW_ERROR -> ReviewCriticalStatus(
            ReviewCriticalUiState.Error(R.string.review_error_open_image),
            request.orientation.toFloat(),
            onRetry = {},
            modifier = Modifier.fillMaxSize(),
        )
        UiSnapshotActivity.SCENARIO_REVIEW_DELETE -> ReviewDeleteConfirmationDialog(
            scope = MediaDeleteScope.CAPTURE_FAMILY,
            raw = true,
            onConfirm = {},
            onDismiss = {},
        )
        UiSnapshotActivity.SCENARIO_PERMISSION -> PermissionGate(false, onRequest = {}, onOpenSettings = {}, onOpenPrivacy = {})
        UiSnapshotActivity.SCENARIO_PERMISSION_DENIED -> PermissionGate(true, onRequest = {}, onOpenSettings = {}, onOpenPrivacy = {})
        UiSnapshotActivity.SCENARIO_MIC_RATIONALE -> MicrophonePermissionRationale(onContinue = {}, onDismiss = {})
        else -> CameraScreen(state = state, actions = actions)
    }
}

private fun snapshotSettingsTab(scenario: String?): ProSheetTab? = when (scenario) {
    UiSnapshotActivity.SCENARIO_SETTINGS, "settings_shooting" -> ProSheetTab.SHOOTING
    "settings_my" -> ProSheetTab.MY_MENU
    "settings_exposure" -> ProSheetTab.EXPOSURE
    "settings_focus" -> ProSheetTab.FOCUS
    "settings_lens" -> ProSheetTab.LENS
    "settings_video" -> ProSheetTab.VIDEO
    "settings_image" -> ProSheetTab.PROCESSING
    "settings_assist" -> ProSheetTab.ASSISTS
    UiSnapshotActivity.SCENARIO_SETTINGS_FN, "settings_setup" -> ProSheetTab.ADVANCED
    else -> null
}

@Composable
private fun SnapshotStateProbe(
    scenario: String,
    transfer: ColorTransfer,
    layoutDirection: LayoutDirection,
    modifier: Modifier = Modifier,
) {
    // UIAutomator omits AccessibilityNodeInfo.stateDescription. These nonvisual, non-actionable,
    // debug-only nodes let device acceptance prove exact state without weakening production tile
    // semantics or using OCR/pixel-change guesses.
    // Keep the tiny probes in a raw LTR coordinate space so neither node is laid out beyond the
    // three-dp host when the production subtree itself is intentionally rendered RTL. The ready
    // marker is scenario-specific because review/permission/dialog fixtures deliberately expose no
    // camera chrome for the device harness to wait on.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier = modifier.size(3.dp)) {
            Spacer(
                modifier = Modifier
                    .size(1.dp)
                    .semantics {
                        contentDescription = "Snapshot Gamma ${snapshotTransferLabel(transfer)}"
                    },
            )
            Spacer(
                modifier = Modifier
                    .absoluteOffset(x = 1.dp)
                    .size(1.dp)
                    .semantics { contentDescription = "Snapshot layout $layoutDirection" },
            )
            Spacer(
                modifier = Modifier
                    .absoluteOffset(x = 2.dp)
                    .size(1.dp)
                    .semantics { contentDescription = "Snapshot ready $scenario" },
            )
        }
    }
}

private fun snapshotTransferLabel(transfer: ColorTransfer): String = when (transfer) {
    ColorTransfer.HLG -> "HLG"
    ColorTransfer.SLOG3 -> "S-Log3"
    ColorTransfer.SLOG3_CINE -> "S-Log3.Cine"
    ColorTransfer.LOGC3 -> "LogC3"
    ColorTransfer.SDR -> "SDR"
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
        UiSnapshotActivity.SCENARIO_MAXIMAL_OSD -> common.copy(
            caps = snapshotCameraCaps(),
            facing = me.hletrd.telecampro.camera.CameraFacing.FRONT,
            activeMemorySlot = MemorySlot.MR3,
            transfer = ColorTransfer.SLOG3_CINE,
            gammaAssist = true,
            openGate = true,
            recordAudio = false,
            videoStabMode = VideoStabMode.OFF,
            controls = ManualControls(
                aeLock = true,
                awbLock = true,
                afLock = true,
                meteringMode = MeteringMode.SPOT,
            ),
            focusConfidence = FocusConfidenceSource.FRAME_DETAIL,
            punchIn = true,
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
    hiResJpegSize = null,
    hiResUsesMaxResolutionMode = false,
    largestYuvSize = Size(4080, 3064),
    timestampSource = CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
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
    lensFacingFront = false,
)
