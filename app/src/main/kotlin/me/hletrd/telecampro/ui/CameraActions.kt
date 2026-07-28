package me.hletrd.telecampro.ui

import android.util.Size
import android.view.Surface
import me.hletrd.telecampro.camera.Antibanding
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.BitrateLevel
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorEffect
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.ExposureStep
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.HardwareKeyAction
import me.hletrd.telecampro.camera.AfSpotSize
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.PeakingColor
import me.hletrd.telecampro.camera.PeakingLevel
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.ProcessingLevel
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.camera.WbMode

/**
 * Everything the stateless Compose UI can ask the engine to do. Implemented by CameraViewModel.
 * The UI renders [me.hletrd.telecampro.camera.CameraUiState] and calls these — it never touches
 * Camera2/GL directly.
 */
interface CameraActions {
    // Preview surface lifecycle
    fun onPreviewSurfaceAvailable(surface: Surface, width: Int, height: Int)
    fun onPreviewSurfaceChanged(width: Int, height: Int)
    fun onPreviewSurfaceDestroyed()

    // Focus
    fun onFocusMode(mode: FocusMode)
    fun onFocusSlider(slider: Float)
    fun onAfLock(locked: Boolean)
    fun onTapFocus(nx: Float, ny: Float)
    fun onResetFocusPoint()

    // Exposure
    fun onIso(iso: Int)
    fun onShutterNs(ns: Long)
    fun onExposureCompensation(ev: Int)
    fun onExposureMode(mode: ExposureMode)
    fun onToggleAeLock(locked: Boolean)
    fun onAntibanding(mode: Antibanding)
    fun onShutterMode(mode: ShutterMode)
    fun onShutterAngle(angle: Float)
    fun onExposureStep(step: ExposureStep)

    // White balance
    fun onWbMode(mode: WbMode)
    fun onWbKelvin(kelvin: Int)
    fun onWbTint(tint: Int)
    fun onToggleAwbLock(locked: Boolean)
    fun onMeteringMode(mode: MeteringMode)
    fun onAfSpotSize(size: AfSpotSize)
    fun onCaptureCustomWb()

    // Processing
    fun onEdge(level: ProcessingLevel)
    fun onNoiseReduction(level: ProcessingLevel)
    fun onColorEffect(effect: ColorEffect)

    // Optics / output
    fun onFlash(mode: FlashMode)
    fun onToggleOis(enabled: Boolean)
    fun onZoomRatio(ratio: Float)
    // Pinch-to-zoom on the viewfinder: [factor] is the incremental pinch scale (1.0 = no change).
    fun onPinchZoom(factor: Float)
    /**
     * The last finger of a pinch that actually zoomed has lifted. The gesture loop is the only place
     * that knows this boundary exactly; the ViewModel otherwise has to infer it from a timer, and
     * inferring it wrongly is what left a re-pinch inside the previous gesture's 700 ms tail without
     * its zoom-OUT leading edge (AGG4-14). Advisory only: the timer-based re-arm remains the
     * fallback for the input paths that never report a finger-up (hardware slide-zoom, ease glide).
     */
    fun onPinchEnd()
    /**
     * A TELE focal-rail mark: [totalMagnification] is the converter-equivalent TOTAL the user tapped
     * (13×/30×/60×-class), not a lens-local ratio. The converter's host lens stays put — this is a
     * discrete digital-zoom pick, never a lens change.
     */
    fun onTeleZoomMark(totalMagnification: Float)
    fun onJpegQuality(quality: Int)

    // Modes
    fun onModeChange(mode: CaptureMode)
    fun onTransfer(transfer: ColorTransfer)
    fun onSetPhotoFormats(formats: PhotoFormats)
    /** Hi-res (full-sensor) still INTENT; accepted truth is photoSessionOutputs.hiRes. */
    fun onToggleHiResStill(enabled: Boolean)
    fun onAspectRatio(ratio: AspectRatio)
    fun onToggleRecordAudio(enabled: Boolean)
    fun onAudioGain(gain: Float)
    fun onAudioScene(scene: me.hletrd.telecampro.camera.AudioScene)
    fun onAudioInputPreference(preference: me.hletrd.telecampro.camera.AudioInputPreference)
    fun onToggleTeleconverter(enabled: Boolean)
    /**
     * Declares which PHONE the converter clamps onto. Seeded from `Build.MODEL` at first launch and
     * overridable; it narrows the converter list and can therefore change the magnification.
     */
    fun onPhoneModel(model: me.hletrd.telecampro.camera.PhoneModel)
    /** Declares WHICH converter is clamped on — a manual choice, never a detection. */
    fun onTeleconverterProfile(profile: me.hletrd.telecampro.camera.TeleconverterProfile)
    /** Magnification for [me.hletrd.telecampro.camera.TeleconverterProfile.CUSTOM] only. */
    fun onTeleconverterCustomMagnification(value: Float)
    fun onVideoCodec(codec: VideoCodec)
    fun onBitrateLevel(level: BitrateLevel)
    fun onVideoResolution(size: Size)
    fun onVideoFrameRate(rate: VideoFrameRate)
    fun onToggleOpenGate(enabled: Boolean)

    // Stabilization
    fun onVideoStabMode(mode: me.hletrd.telecampro.camera.VideoStabMode)

    // Viewfinder assists
    fun onTogglePeaking(enabled: Boolean)
    fun onPeakingLevel(level: PeakingLevel)
    fun onPeakingColor(color: PeakingColor)
    fun onToggleZebra(enabled: Boolean)
    fun onZebraLevel(level: me.hletrd.telecampro.camera.ZebraLevel)
    fun onToggleFalseColor(enabled: Boolean)
    fun onToggleHistogram(enabled: Boolean)
    fun onToggleWaveform(enabled: Boolean)
    fun onToggleGammaAssist(enabled: Boolean)
    fun onFrameLines(type: FrameLineType)
    fun onGridType(type: GridType)
    fun onToggleLevel(enabled: Boolean)
    fun onTogglePunchIn(enabled: Boolean)
    /** Same-stream Loupe Overview toggle (default OFF); resolved against TELE + Photo + 4:3 + loupe. */
    fun onToggleTeleFinder(enabled: Boolean)

    // Drive
    fun onTimer(timer: ShutterTimer)
    fun onDriveMode(mode: DriveMode)
    fun onIntervalSec(sec: Int)

    // Shutter
    fun onCapturePhoto()
    fun onToggleRecording()
    fun onHardwareHalfPress(active: Boolean)

    // Lens picks are ZOOM PRESETS — they do NOT bundle the teleconverter. TELE stays on only when
    // it already is AND the pick is its 3× host lens; onToggleTeleconverter owns converter state.
    fun onLens(choice: me.hletrd.telecampro.camera.LensChoice)

    /** Flip between the rear route and the BASIC front (selfie) camera; never persisted. */
    fun onToggleFrontCamera()

    // Settings
    fun onCameraOverride(id: String?)
    fun onToggleRememberSettings(enabled: Boolean)
    fun onTogglePreserveLensSelection(enabled: Boolean)
    fun onTogglePreserveTeleconverter(enabled: Boolean)
    fun onSetPhotoFnSlots(slots: List<FnSlot>)
    fun onSetVideoFnSlots(slots: List<FnSlot>)
    fun onSetMyMenuSlots(slots: List<FnSlot>)
    fun onStoreMemorySlot(slot: MemorySlot)
    fun onRecallMemorySlot(slot: MemorySlot)
    fun onVolumeKeyAction(action: HardwareKeyAction)
    fun onHalfPressAction(action: HardwareKeyAction)
    fun onDeleteLastMedia(uri: android.net.Uri)

    /**
     * The full-screen media-review overlay opened/closed for its frozen [uri]. Returns true only
     * when opening pinned that exact capture family; the UI must otherwise use file-only copy.
     * [onCameraInputBlockedChange] owns the broader gate shared with settings and Fn modals.
     */
    fun onReviewOpenChange(open: Boolean, uri: android.net.Uri): Boolean
    fun onCameraInputBlockedChange(blocked: Boolean)
    /** True only while the standby Video level meter is actually visible and unobscured. */
    fun onStandbyAudioMeterVisibilityChanged(visible: Boolean)
}
