package me.hletrd.telecampro.ui.controls

import me.hletrd.telecampro.camera.AfSpotSize
import me.hletrd.telecampro.camera.Antibanding
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.AudioInputPreference
import me.hletrd.telecampro.camera.AudioScene
import me.hletrd.telecampro.camera.BitrateLevel
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorEffect
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.DriveMode
import me.hletrd.telecampro.camera.ExposureMode
import me.hletrd.telecampro.camera.ExposureStep
import me.hletrd.telecampro.camera.FlashMode
import me.hletrd.telecampro.camera.FnSlot
import me.hletrd.telecampro.camera.FocusMode
import me.hletrd.telecampro.camera.FrameLineType
import me.hletrd.telecampro.camera.GridType
import me.hletrd.telecampro.camera.HardwareKeyAction
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.PeakingColor
import me.hletrd.telecampro.camera.PeakingLevel
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.ProcessingLevel
import me.hletrd.telecampro.camera.ShutterMode
import me.hletrd.telecampro.camera.ShutterTimer
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.camera.WbMode
import me.hletrd.telecampro.camera.ZebraLevel
import me.hletrd.telecampro.ui.CameraActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [performQuickFn]'s dispatch contract with a recording [CameraActions] fake: exactly which
 * action a quick-Fn tap fires per slot, that the [quickFnEnabled] guard refuses BEFORE any action
 * (the My-Menu mid-REC teleconverter/transfer hole this fn's own comment documents), and that the
 * capability-dial-gated slots (Shutter/EV/Zoom) fire nothing while no route capabilities exist.
 */
class PerformQuickFnTest {

    /** Records every invoked action as "name(arg)"; unrelated callbacks fail loudly if reached. */
    private class RecordingActions : CameraActions {
        val calls = mutableListOf<String>()
        private fun hit(call: String) {
            calls += call
        }

        override fun onPreviewSurfaceAvailable(surface: android.view.Surface, width: Int, height: Int) = hit("onPreviewSurfaceAvailable")
        override fun onPreviewSurfaceChanged(width: Int, height: Int) = hit("onPreviewSurfaceChanged")
        override fun onPreviewSurfaceDestroyed() = hit("onPreviewSurfaceDestroyed")
        override fun onFocusMode(mode: FocusMode) = hit("onFocusMode($mode)")
        override fun onFocusSlider(slider: Float) = hit("onFocusSlider")
        override fun onAfLock(locked: Boolean) = hit("onAfLock")
        override fun onTapFocus(nx: Float, ny: Float) = hit("onTapFocus")
        override fun onResetFocusPoint() = hit("onResetFocusPoint")
        override fun onIso(iso: Int) = hit("onIso($iso)")
        override fun onShutterNs(ns: Long) = hit("onShutterNs($ns)")
        override fun onExposureCompensation(ev: Int) = hit("onExposureCompensation($ev)")
        override fun onExposureMode(mode: ExposureMode) = hit("onExposureMode($mode)")
        override fun onToggleAeLock(locked: Boolean) = hit("onToggleAeLock")
        override fun onAntibanding(mode: Antibanding) = hit("onAntibanding")
        override fun onShutterMode(mode: ShutterMode) = hit("onShutterMode($mode)")
        override fun onShutterAngle(angle: Float) = hit("onShutterAngle")
        override fun onExposureStep(step: ExposureStep) = hit("onExposureStep")
        override fun onWbMode(mode: WbMode) = hit("onWbMode($mode)")
        override fun onWbKelvin(kelvin: Int) = hit("onWbKelvin")
        override fun onWbTint(tint: Int) = hit("onWbTint")
        override fun onToggleAwbLock(locked: Boolean) = hit("onToggleAwbLock")
        override fun onMeteringMode(mode: MeteringMode) = hit("onMeteringMode($mode)")
        override fun onAfSpotSize(size: AfSpotSize) = hit("onAfSpotSize")
        override fun onCaptureCustomWb() = hit("onCaptureCustomWb")
        override fun onEdge(level: ProcessingLevel) = hit("onEdge")
        override fun onNoiseReduction(level: ProcessingLevel) = hit("onNoiseReduction")
        override fun onColorEffect(effect: ColorEffect) = hit("onColorEffect")
        override fun onFlash(mode: FlashMode) = hit("onFlash")
        override fun onToggleOis(enabled: Boolean) = hit("onToggleOis")
        override fun onZoomRatio(ratio: Float) = hit("onZoomRatio($ratio)")
        override fun onPinchZoom(factor: Float) = hit("onPinchZoom")
        override fun onPinchEnd() = hit("onPinchEnd")
        override fun onTeleZoomMark(totalMagnification: Float) = hit("onTeleZoomMark($totalMagnification)")
        override fun onJpegQuality(quality: Int) = hit("onJpegQuality")
        override fun onModeChange(mode: CaptureMode) = hit("onModeChange")
        override fun onTransfer(transfer: ColorTransfer) = hit("onTransfer($transfer)")
        override fun onSetPhotoFormats(formats: PhotoFormats) = hit("onSetPhotoFormats")
        override fun onToggleHiResStill(enabled: Boolean) = hit("onToggleHiResStill")
        override fun onAspectRatio(ratio: AspectRatio) = hit("onAspectRatio")
        override fun onToggleRecordAudio(enabled: Boolean) = hit("onToggleRecordAudio")
        override fun onAudioGain(gain: Float) = hit("onAudioGain")
        override fun onAudioScene(scene: AudioScene) = hit("onAudioScene($scene)")
        override fun onAudioInputPreference(preference: AudioInputPreference) = hit("onAudioInputPreference")
        override fun onToggleTeleconverter(enabled: Boolean) = hit("onToggleTeleconverter($enabled)")
        override fun onPhoneModel(model: me.hletrd.telecampro.camera.PhoneModel) = hit("onPhoneModel($model)")
        override fun onTeleconverterProfile(profile: me.hletrd.telecampro.camera.TeleconverterProfile) =
            hit("onTeleconverterProfile($profile)")
        override fun onTeleconverterCustomMagnification(value: Float) =
            hit("onTeleconverterCustomMagnification($value)")
        override fun onVideoCodec(codec: VideoCodec) = hit("onVideoCodec")
        override fun onBitrateLevel(level: BitrateLevel) = hit("onBitrateLevel")
        override fun onVideoResolution(size: android.util.Size) = hit("onVideoResolution")
        override fun onVideoFrameRate(rate: VideoFrameRate) = hit("onVideoFrameRate")
        override fun onToggleOpenGate(enabled: Boolean) = hit("onToggleOpenGate($enabled)")
        override fun onVideoStabMode(mode: VideoStabMode) = hit("onVideoStabMode($mode)")
        override fun onTogglePeaking(enabled: Boolean) = hit("onTogglePeaking($enabled)")
        override fun onPeakingLevel(level: PeakingLevel) = hit("onPeakingLevel")
        override fun onPeakingColor(color: PeakingColor) = hit("onPeakingColor")
        override fun onToggleZebra(enabled: Boolean) = hit("onToggleZebra($enabled)")
        override fun onZebraLevel(level: ZebraLevel) = hit("onZebraLevel")
        override fun onToggleFalseColor(enabled: Boolean) = hit("onToggleFalseColor")
        override fun onToggleHistogram(enabled: Boolean) = hit("onToggleHistogram")
        override fun onToggleWaveform(enabled: Boolean) = hit("onToggleWaveform")
        override fun onToggleGammaAssist(enabled: Boolean) = hit("onToggleGammaAssist")
        override fun onFrameLines(type: FrameLineType) = hit("onFrameLines($type)")
        override fun onGridType(type: GridType) = hit("onGridType($type)")
        override fun onToggleLevel(enabled: Boolean) = hit("onToggleLevel($enabled)")
        override fun onTogglePunchIn(enabled: Boolean) = hit("onTogglePunchIn($enabled)")
        override fun onToggleTeleFinder(enabled: Boolean) = hit("onToggleTeleFinder")
        override fun onTimer(timer: ShutterTimer) = hit("onTimer")
        override fun onDriveMode(mode: DriveMode) = hit("onDriveMode($mode)")
        override fun onIntervalSec(sec: Int) = hit("onIntervalSec")
        override fun onCapturePhoto() = hit("onCapturePhoto")
        override fun onToggleRecording() = hit("onToggleRecording")
        override fun onHardwareHalfPress(active: Boolean) = hit("onHardwareHalfPress")
        override fun onLens(choice: LensChoice) = hit("onLens")
        override fun onToggleFrontCamera() = hit("onToggleFrontCamera")
        override fun onCameraOverride(id: String?) = hit("onCameraOverride")
        override fun onToggleRememberSettings(enabled: Boolean) = hit("onToggleRememberSettings")
        override fun onTogglePreserveLensSelection(enabled: Boolean) = hit("onTogglePreserveLensSelection")
        override fun onTogglePreserveTeleconverter(enabled: Boolean) = hit("onTogglePreserveTeleconverter")
        override fun onSetPhotoFnSlots(slots: List<FnSlot>) = hit("onSetPhotoFnSlots")
        override fun onSetVideoFnSlots(slots: List<FnSlot>) = hit("onSetVideoFnSlots")
        override fun onSetMyMenuSlots(slots: List<FnSlot>) = hit("onSetMyMenuSlots")
        override fun onStoreMemorySlot(slot: MemorySlot) = hit("onStoreMemorySlot")
        override fun onRecallMemorySlot(slot: MemorySlot) = hit("onRecallMemorySlot")
        override fun onVolumeKeyAction(action: HardwareKeyAction) = hit("onVolumeKeyAction")
        override fun onHalfPressAction(action: HardwareKeyAction) = hit("onHalfPressAction")
        override fun onQuickButtonAction(action: HardwareKeyAction) = Unit
        override fun onDeleteLastMedia(uri: android.net.Uri) = hit("onDeleteLastMedia")
        override fun onReviewOpenChange(open: Boolean, uri: android.net.Uri): Boolean {
            hit("onReviewOpenChange")
            return false
        }
        override fun onCameraInputBlockedChange(blocked: Boolean) = hit("onCameraInputBlockedChange")
        override fun onStandbyAudioMeterVisibilityChanged(visible: Boolean) = hit("onStandbyAudioMeterVisibilityChanged")
        override fun onScopesVisibilityChanged(visible: Boolean) = hit("onScopesVisibilityChanged")
        override fun onGalleryAccessRequested() = hit("onGalleryAccessRequested")
    }

    private fun dispatched(slot: FnSlot, state: CameraUiState): List<String> {
        val actions = RecordingActions()
        performQuickFn(slot, state, actions)
        return actions.calls
    }

    @Test
    fun `guarded slots fire nothing while recording`() {
        val recording = CameraUiState(isRecording = true, mode = CaptureMode.VIDEO)
        for (slot in listOf(
            FnSlot.TRANSFER, FnSlot.TELECONVERTER, FnSlot.STABILIZATION,
            FnSlot.AUDIO_SCENE, FnSlot.OPEN_GATE,
        )) {
            assertEquals("$slot must refuse mid-REC", emptyList<String>(), dispatched(slot, recording))
        }
    }

    @Test
    fun `toggle slots invert exactly their own state`() {
        val idle = CameraUiState(mode = CaptureMode.VIDEO)
        assertEquals(listOf("onTogglePeaking(true)"), dispatched(FnSlot.PEAKING, idle))
        assertEquals(listOf("onToggleZebra(true)"), dispatched(FnSlot.ZEBRA, idle))
        assertEquals(listOf("onToggleLevel(true)"), dispatched(FnSlot.LEVEL, idle))
        assertEquals(listOf("onTogglePunchIn(true)"), dispatched(FnSlot.PUNCH_IN, idle))
        assertEquals(listOf("onToggleTeleconverter(true)"), dispatched(FnSlot.TELECONVERTER, idle))
        assertEquals(listOf("onToggleOpenGate(true)"), dispatched(FnSlot.OPEN_GATE, idle))
        assertEquals(
            listOf("onTogglePeaking(false)"),
            dispatched(FnSlot.PEAKING, idle.copy(focusPeaking = true)),
        )
    }

    @Test
    fun `cycle slots advance one step through the shared cycle order`() {
        val idle = CameraUiState()
        // The video-only cycles need a VIDEO state: performQuickFn re-checks quickFnEnabled, which
        // now carries fnSlotAppliesTo — in photo these taps mutated video settings that could not
        // affect the still (and STABILIZATION's chip then contradicted the OSD's OIS reading).
        val idleVideo = idle.copy(mode = CaptureMode.VIDEO)
        assertEquals(
            listOf("onVideoStabMode(${VideoStabMode.OFF})"),
            dispatched(FnSlot.STABILIZATION, idleVideo), // default ENHANCED -> OFF
        )
        assertEquals(listOf("onDriveMode(${DriveMode.BURST})"), dispatched(FnSlot.DRIVE, idle))
        assertEquals(listOf("onGridType(${GridType.GOLDEN})"), dispatched(FnSlot.GRID, idle))
        assertEquals(
            listOf("onFrameLines(${FrameLineType.CINEMA})"),
            dispatched(FnSlot.FRAME_LINES, idle),
        )
        assertEquals(listOf("onTransfer(${ColorTransfer.SLOG3})"), dispatched(FnSlot.TRANSFER, idleVideo))
        assertEquals(
            listOf("onAudioScene(${AudioScene.SOUND_FOCUS})"),
            dispatched(FnSlot.AUDIO_SCENE, idleVideo),
        )
        // And the same taps are inert in photo, where none of them can change the capture.
        assertEquals(emptyList<String>(), dispatched(FnSlot.STABILIZATION, idle))
        assertEquals(emptyList<String>(), dispatched(FnSlot.TRANSFER, idle))
        assertEquals(emptyList<String>(), dispatched(FnSlot.AUDIO_SCENE, idle))
    }

    @Test
    fun `mode slots cycle inside the projected availability only`() {
        // No caps -> the projection is the singleton current value: the tap re-selects it rather
        // than inventing a mode the route never advertised.
        val idle = CameraUiState()
        assertEquals(
            listOf("onExposureMode(${ExposureMode.PROGRAM})"),
            dispatched(FnSlot.EXPOSURE_MODE, idle),
        )
        assertEquals(
            listOf("onFocusMode(${FocusMode.CONTINUOUS})"),
            dispatched(FnSlot.FOCUS, idle),
        )
        assertEquals(listOf("onWbMode(${WbMode.AUTO})"), dispatched(FnSlot.WB, idle))
        assertEquals(
            listOf("onMeteringMode(${MeteringMode.MATRIX})"),
            dispatched(FnSlot.METERING, idle),
        )
    }

    @Test
    fun `iso tap returns to program from iso priority and stays projected otherwise`() {
        val isoPriority = CameraUiState(
            controls = ManualControls(exposureMode = ExposureMode.ISO),
        )
        assertEquals(
            listOf("onExposureMode(${ExposureMode.PROGRAM})"),
            dispatched(FnSlot.ISO, isoPriority),
        )
        // Without advertised ISO-priority the tap cannot leave the projected singleton.
        val shutterPriority = CameraUiState(
            controls = ManualControls(exposureMode = ExposureMode.SHUTTER),
        )
        assertEquals(
            listOf("onExposureMode(${ExposureMode.SHUTTER})"),
            dispatched(FnSlot.ISO, shutterPriority),
        )
    }

    @Test
    fun `dial-gated slots fire nothing while no route capabilities exist`() {
        val idle = CameraUiState()
        for (slot in listOf(FnSlot.SHUTTER, FnSlot.EV, FnSlot.ZOOM)) {
            assertEquals("$slot needs its dial gate", emptyList<String>(), dispatched(slot, idle))
        }
    }

    @Test
    fun `every dispatch fires at most one action`() {
        val idle = CameraUiState(mode = CaptureMode.VIDEO)
        for (slot in FnSlot.entries) {
            assertTrue("$slot fired more than once", dispatched(slot, idle).size <= 1)
        }
    }
}
