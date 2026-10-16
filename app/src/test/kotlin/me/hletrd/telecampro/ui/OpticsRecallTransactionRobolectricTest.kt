package me.hletrd.telecampro.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import me.hletrd.telecampro.AudioDenialReasonStore
import me.hletrd.telecampro.camera.CameraController
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraRouteInventory
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.HardwareKeyAction
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.PhoneModel
import me.hletrd.telecampro.camera.TeleconverterDeclaration
import me.hletrd.telecampro.camera.TeleconverterProfile
import me.hletrd.telecampro.camera.effectiveFocalMm
import me.hletrd.telecampro.camera.status
import me.hletrd.telecampro.storage.ExtraSettings
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.camera.VideoStabMode
import me.hletrd.telecampro.storage.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
class OpticsRecallTransactionRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var viewModel: CameraViewModel? = null

    private fun createViewModel(): Pair<CameraViewModel, CameraEngine> {
        RobolectricEglSentinels.ensure()
        val engine = CameraEngine(app)
        val vm = CameraViewModel(app, engine)
        viewModel = vm
        val routes = CameraRouteInventory(back = true, front = false, external = false)
        CameraEngine::class.java.getDeclaredField("cameraRouteInventory")
            .apply { isAccessible = true }
            .set(engine, routes)
        engine.onCameraRouteInventory?.invoke(routes, CameraRoute.BACK)
        return vm to engine
    }

    private fun saveTelePreset(
        slot: MemorySlot,
        phone: PhoneModel,
        profile: TeleconverterProfile,
    ) {
        SettingsStore(app).savePreset(
            slot,
            ManualControls(zoomRatio = 1f),
            ExtraSettings(
                mode = CaptureMode.PHOTO,
                lens = LensChoice.TELE3X,
                teleconverter = true,
                phoneModel = phone,
                teleconverterProfile = profile,
            ),
            "",
            "",
        )
    }

    private fun currentDeclaration(engine: CameraEngine): TeleconverterDeclaration =
        CameraEngine::class.java.getDeclaredField("teleconverterDeclaration")
            .apply { isAccessible = true }
            .get(engine) as TeleconverterDeclaration

    private fun currentGeneration(engine: CameraEngine): Long =
        (CameraEngine::class.java.getDeclaredField("opticsIntentGeneration")
            .apply { isAccessible = true }
            .get(engine) as AtomicLong).get()

    private data class RollbackAttempt(val generation: Long, val transaction: Any)

    /** Captures the real generation-owned transaction shape consumed by CameraEngine.rollbackOptics. */
    private fun currentRollbackAttempt(engine: CameraEngine): RollbackAttempt {
        val generation = currentGeneration(engine)
        val baseline = checkNotNull(
            CameraEngine::class.java.getDeclaredField("opticsRollbackBaseline")
                .apply { isAccessible = true }
                .get(engine),
        )
        val transactionType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "OpticsTransaction" }
        val constructor = transactionType.declaredConstructors.single()
            .apply { isAccessible = true }
        return RollbackAttempt(generation, constructor.newInstance(generation, baseline, true))
    }

    /** Invokes the production rollback body; no Engine field or UI callback is pre-restored here. */
    private fun invokeRollback(engine: CameraEngine, attempt: RollbackAttempt) {
        CameraEngine::class.java.declaredMethods
            .single { it.name == "rollbackOptics" }
            .apply { isAccessible = true }
            .invoke(
                engine,
                attempt.transaction,
                CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED.status(),
            )
    }

    private fun installController(
        engine: CameraEngine,
        declaration: TeleconverterDeclaration,
    ): CameraController = CameraController(app).also { controller ->
        controller.setTeleconverterMagnification(declaration.magnification)
        CameraEngine::class.java.getDeclaredField("controller")
            .apply { isAccessible = true }
            .set(engine, controller)
    }

    private fun currentControllerMagnification(controller: CameraController): Float =
        CameraController::class.java.getDeclaredField("teleconverterMagnification")
            .apply { isAccessible = true }
            .getFloat(controller)

    private fun setRecordingState(vm: CameraViewModel, recording: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val state = CameraViewModel::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<CameraUiState>
        state.value = state.value.copy(isRecording = recording)
    }

    /** Installs a no-device accepted TELE baseline without dispatching Camera2 setup work. */
    private fun setAcceptedTeleBaseline(vm: CameraViewModel, engine: CameraEngine) {
        @Suppress("UNCHECKED_CAST")
        val state = CameraViewModel::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<CameraUiState>
        val controls = state.value.controls.copy(zoomRatio = 1f)
        state.value = state.value.copy(
            cameraReady = true,
            lens = LensChoice.TELE3X,
            teleconverterMode = true,
            controls = controls,
        )
        CameraEngine::class.java.getDeclaredField("lensChoice")
            .apply { isAccessible = true }
            .set(engine, LensChoice.TELE3X)
        CameraEngine::class.java.getDeclaredField("teleconverterMode")
            .apply { isAccessible = true }
            .setBoolean(engine, true)
        CameraEngine::class.java.getDeclaredField("controls")
            .apply { isAccessible = true }
            .set(engine, controls)
        CameraEngine::class.java.getDeclaredField("cameraReady")
            .apply { isAccessible = true }
            .setBoolean(engine, true)
        CameraEngine::class.java.getDeclaredField("opticsRollbackBaseline")
            .apply { isAccessible = true }
            .set(engine, null)
    }

    private fun clear(vm: CameraViewModel) {
        CameraViewModel::class.java.getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(vm)
    }

    @After
    fun tearDown() {
        viewModel?.let(::clear)
    }

    @Test
    fun `same-route cross-phone recall keeps UI memory row Engine and shot focal on one packet`() {
        saveTelePreset(MemorySlot.MR1, PhoneModel.FIND_X9_ULTRA, TeleconverterProfile.EXPLORER_300)
        val (vm, engine) = createViewModel()
        vm.onPhoneModel(PhoneModel.VIVO_X300_ULTRA)
        vm.onTeleconverterProfile(TeleconverterProfile.ZEISS_200_X300)
        setAcceptedTeleBaseline(vm, engine)

        vm.onRecallMemorySlot(MemorySlot.MR1)

        val ui = vm.state.value
        val declaration = currentDeclaration(engine)
        assertEquals(PhoneModel.FIND_X9_ULTRA, ui.phoneModel)
        assertEquals(TeleconverterProfile.EXPLORER_300, ui.teleconverterProfile)
        assertEquals(70f, ui.teleconverterHostEquivMm, 0f)
        assertEquals(300f, ui.teleconverterFocalMm, 0.001f)
        assertEquals(ui.phoneModel, declaration.phone)
        assertEquals(ui.teleconverterProfile, declaration.profile)
        assertEquals(ui.teleconverterHostEquivMm, declaration.hostTeleEquivMm, 0f)
        // ShotOptics snapshots these exact two Engine values; pin their EXIF input explicitly.
        assertEquals(
            ui.teleconverterFocalMm,
            effectiveFocalMm(declaration.magnification, declaration.hostTeleEquivMm),
            0.001f,
        )
        assertEquals(300f, vm.state.value.memorySlotPresentations[MemorySlot.MR1]?.focalMm ?: 0f, 0.001f)
    }

    // AGG2-26: the Activity keys the audio-denial reason on the recall's own applied answer; the
    // post-hoc `activeMemorySlot == slot` check was true for a refused re-recall of the same slot.
    @Test
    fun `recallMemorySlot answers an applied recall only for a recall that applied`() {
        saveTelePreset(MemorySlot.MR1, PhoneModel.FIND_X9_ULTRA, TeleconverterProfile.EXPLORER_300)
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)

        assertNotNull(vm.recallMemorySlot(MemorySlot.MR1))
        assertEquals(MemorySlot.MR1, vm.state.value.activeMemorySlot)

        setRecordingState(vm, true)
        assertNull(vm.recallMemorySlot(MemorySlot.MR1))
        assertEquals(MemorySlot.MR1, vm.state.value.activeMemorySlot)

        setRecordingState(vm, false)
        assertNull(vm.recallMemorySlot(MemorySlot.MR2))

        // AGG3-8: the bank's audio-off provenance rides the store and comes back with the recall.
        // AGG4-49: the ViewModel door itself reads the shared denial reason, so a bank stored
        // through it is never "unknown" (null) — it used to be, unless an Activity wrapper ran.
        val reason = AudioDenialReasonStore(app)
        reason.write(true)
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(recordAudio = false)
        vm.onStoreMemorySlot(MemorySlot.MR2)
        assertEquals(true, vm.recallMemorySlot(MemorySlot.MR2)?.recordAudioOffByDenial)
        reason.write(false)
        vm.onStoreMemorySlot(MemorySlot.MR2)
        assertEquals(false, requireNotNull(vm.recallMemorySlot(MemorySlot.MR2)).recordAudioOffByDenial)
    }

    // AGG5-21 / AGG5-22: the recall leg of the audio provenance runs in the VM door itself, and its
    // silent restore keeps the slot the recall just lit (it used to go through the operator's audio
    // toggle, which cleared `activeMemorySlot`; and only the Activity wrapper ran it at all).
    @Test
    fun `recalling a denial-silent bank with the mic granted restores audio and keeps the slot lit`() {
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        val reason = AudioDenialReasonStore(app)
        reason.write(true)
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(recordAudio = false)
        vm.onStoreMemorySlot(MemorySlot.MR3) // denial-silent provenance
        var granted = false
        vm.microphonePermissionCheck = { granted }

        // Not granted: the bank's silence and its reason come back as stored.
        vm.onRecallMemorySlot(MemorySlot.MR3)
        assertEquals(MemorySlot.MR3, vm.state.value.activeMemorySlot)
        assertFalse(vm.state.value.recordAudio)
        assertTrue(reason.read())

        // Granted: the plain CameraActions door (no Activity wrapper) reconciles at once.
        granted = true
        vm.onRecallMemorySlot(MemorySlot.MR3)
        assertTrue("the grant hands the denial-disabled track back", vm.state.value.recordAudio)
        assertEquals("the recalled slot stays lit", MemorySlot.MR3, vm.state.value.activeMemorySlot)
        assertFalse(reason.read())
        assertEquals(CameraStatusMessage.MEMORY_SLOT_LOADED, vm.state.value.status?.message)

        // An operator-silent bank is NOT overridden by the grant.
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(recordAudio = false)
        vm.onStoreMemorySlot(MemorySlot.MR1) // reason is false now: operator-chosen silence
        vm.onRecallMemorySlot(MemorySlot.MR1)
        assertFalse(vm.state.value.recordAudio)
        assertEquals(MemorySlot.MR1, vm.state.value.activeMemorySlot)
    }

    @Test
    fun `the resume grant reconciliation restores denial-disabled audio and announces it`() {
        val (vm, _) = createViewModel()
        val reason = AudioDenialReasonStore(app)
        reason.write(true)
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(recordAudio = false)
        vm.microphonePermissionCheck = { false }
        assertFalse(vm.reconcileMicrophoneGrant())
        vm.microphonePermissionCheck = { true }
        assertTrue(vm.reconcileMicrophoneGrant())
        assertTrue(vm.state.value.recordAudio)
        assertFalse(reason.read())
        assertEquals(CameraStatusMessage.MICROPHONE_ALLOWED_AUDIO_ON, vm.state.value.status?.message)
        assertFalse("consumed: a second resume does nothing", vm.reconcileMicrophoneGrant())
    }

    // AGG6-15: a grant observed mid-take must not flip audio on over a clip whose recorder fixed
    // `doAudio = false` at start; the reason survives and the reconciliation runs when REC ends.
    @Test
    fun `a grant observed mid-take is deferred until the take ends`() {
        val (vm, engine) = createViewModel()
        val reason = AudioDenialReasonStore(app)
        reason.write(true)
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(recordAudio = false, isRecording = true)
        vm.microphonePermissionCheck = { true }

        assertFalse(vm.reconcileMicrophoneGrant())
        assertFalse("the silent take stays silent on screen", vm.state.value.recordAudio)
        assertTrue("the denial reason is not consumed mid-take", reason.read())

        engine.onRecordingTerminated!!.invoke(IllegalStateException("take ended"))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(vm.state.value.isRecording)
        assertTrue("the deferred grant restores audio for the next take", vm.state.value.recordAudio)
        assertFalse(reason.read())
        assertEquals(CameraStatusMessage.MICROPHONE_ALLOWED_AUDIO_ON, vm.state.value.status?.message)
    }

    // AGG4-10: a failed recall lit its slot on the optimistic apply; the rollback restores a
    // baseline that is not the bank, so the indicator must go out with it.
    @Test
    fun `owned recall rollback clears the active memory slot`() {
        saveTelePreset(MemorySlot.MR1, PhoneModel.FIND_X9_ULTRA, TeleconverterProfile.EXPLORER_300)
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        installController(engine, currentDeclaration(engine))

        vm.onRecallMemorySlot(MemorySlot.MR1)
        assertEquals(MemorySlot.MR1, vm.state.value.activeMemorySlot)
        invokeRollback(engine, currentRollbackAttempt(engine))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(vm.state.value.activeMemorySlot)
    }

    // AGG4-11: a REFUSED recall must not arm the pre-inventory request mirrors — when the encoder
    // inventory later landed, applyEncoderInventory replayed the refused bank's codec/transfer/DNG.
    @Test
    fun `refused recall before the encoder inventory arms no pending request`() {
        SettingsStore(app).savePreset(
            MemorySlot.MR1,
            ManualControls(zoomRatio = 1f),
            ExtraSettings(
                mode = CaptureMode.VIDEO,
                videoCodec = VideoCodec.AVC,
                transfer = ColorTransfer.SLOG3_CINE,
                dngRaw = true,
            ),
            "",
            "",
        )
        val (vm, engine) = createViewModel()
        // A route inventory with no camera at all is recall's first refusal exit.
        val none = CameraRouteInventory(back = false, front = false, external = false)
        engine.onCameraRouteInventory?.invoke(none, CameraRoute.BACK)
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(
            encoderInventoryLoaded = false,
            cameraRoutes = none,
        )
        listOf(
            "pendingCodecUntilInventory",
            "pendingTransferUntilInventory",
            "pendingPhotoFormatsUntilInventory",
        ).forEach { ViewModelTestAccess.setField(vm, it, null) }

        assertNull(vm.recallMemorySlot(MemorySlot.MR1))

        assertNull(ViewModelTestAccess.field(vm, "pendingCodecUntilInventory"))
        assertNull(ViewModelTestAccess.field(vm, "pendingTransferUntilInventory"))
        assertNull(ViewModelTestAccess.field(vm, "pendingPhotoFormatsUntilInventory"))

        // TE5-16: the SECOND refusal exit — the Engine's synchronous REC refusal of the optics
        // transaction (`!opticsAccepted`) — must not arm them either.
        val back = CameraRouteInventory(back = true, front = false, external = false)
        engine.onCameraRouteInventory?.invoke(back, CameraRoute.BACK)
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(cameraRoutes = back)
        // The Engine refuses while it owns a recorder; an unconstructed placeholder is enough for
        // that identity check and is removed again before teardown can touch it.
        val recorderField = CameraEngine::class.java.getDeclaredField("recorder").apply { isAccessible = true }
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val placeholder = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, me.hletrd.telecampro.video.VideoRecorder::class.java)
        recorderField.set(engine, placeholder)
        try {
            assertNull(vm.recallMemorySlot(MemorySlot.MR1))
        } finally {
            recorderField.set(engine, null)
        }
        assertNull(ViewModelTestAccess.field(vm, "pendingCodecUntilInventory"))
        assertNull(ViewModelTestAccess.field(vm, "pendingTransferUntilInventory"))
        assertNull(ViewModelTestAccess.field(vm, "pendingPhotoFormatsUntilInventory"))
    }

    // AGG5-23: an ACCEPTED pre-inventory recall that the Engine later rolls back puts the
    // pre-inventory request back as it was; the rolled-back bank's codec/curve/formats must not be
    // persisted by the rollback's save or replayed when the inventory lands.
    @Test
    fun `owned rollback of a pre-inventory recall restores the pending request`() {
        SettingsStore(app).savePreset(
            MemorySlot.MR2,
            ManualControls(zoomRatio = 1f),
            ExtraSettings(
                mode = CaptureMode.VIDEO,
                videoCodec = VideoCodec.AVC,
                transfer = ColorTransfer.SLOG3_CINE,
                heif = false,
                jpeg = true,
            ),
            "",
            "",
        )
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        installController(engine, currentDeclaration(engine))
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(encoderInventoryLoaded = false)
        val launchFormats = me.hletrd.telecampro.camera.PhotoFormats(heif = true, jpeg = false, dngRaw = false)
        ViewModelTestAccess.setField(vm, "pendingCodecUntilInventory", VideoCodec.HEVC)
        ViewModelTestAccess.setField(vm, "pendingTransferUntilInventory", ColorTransfer.HLG)
        ViewModelTestAccess.setField(vm, "pendingPhotoFormatsUntilInventory", launchFormats)

        assertNotNull(vm.recallMemorySlot(MemorySlot.MR2))
        assertEquals(VideoCodec.AVC, ViewModelTestAccess.field(vm, "pendingCodecUntilInventory"))
        invokeRollback(engine, currentRollbackAttempt(engine))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VideoCodec.HEVC, ViewModelTestAccess.field(vm, "pendingCodecUntilInventory"))
        assertEquals(ColorTransfer.HLG, ViewModelTestAccess.field(vm, "pendingTransferUntilInventory"))
        assertEquals(launchFormats, ViewModelTestAccess.field(vm, "pendingPhotoFormatsUntilInventory"))
        val extras = ViewModelTestAccess.invoke(vm, "currentExtras") as ExtraSettings
        assertEquals(VideoCodec.HEVC, extras.videoCodec)
        assertEquals(ColorTransfer.HLG, extras.transfer)
        assertTrue(extras.heif)
        assertFalse(extras.jpeg)
    }

    // AGG5-24: a recall cancels a held AEL; its rollback restores the HELD lock, which no release
    // will ever undo. The rollback puts the operator's value back and persists THAT.
    @Test
    fun `rollback of a hold-cancelling recall restores the operator's AE lock`() {
        saveTelePreset(MemorySlot.MR1, PhoneModel.FIND_X9_ULTRA, TeleconverterProfile.EXPLORER_300)
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        installController(engine, currentDeclaration(engine))
        vm.onVolumeKeyAction(HardwareKeyAction.AEL)
        vm.onHardwareFullKey(true) // momentary lock; the operator's value is unlocked
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        assertTrue(vm.state.value.controls.aeLock)

        vm.onRecallMemorySlot(MemorySlot.MR1) // cancels the hold
        invokeRollback(engine, currentRollbackAttempt(engine))
        shadowOf(Looper.getMainLooper()).idle()
        vm.onHardwareFullKey(false) // the cancelled hold restores nothing

        assertFalse("the held lock must not survive the rollback", vm.state.value.controls.aeLock)
        ViewModelTestAccess.invoke(vm, "saveSettingsIfEnabled")
        assertEquals(false, SettingsStore(app).load()?.controls?.aeLock)
    }

    // AGG6-11: a newer door that supersedes an un-Ready recall inherits the PRE-recall baseline, so
    // its rollback undoes the recall too. Keyed by equality on the recall's generation, the recall's
    // record was dropped there: the held AE lock, the pre-inventory bank and its stab request stayed.
    @Test
    fun `a superseding failed door still restores what the superseded recall changed`() {
        saveTelePreset(MemorySlot.MR1, PhoneModel.FIND_X9_ULTRA, TeleconverterProfile.EXPLORER_300)
        SettingsStore(app).savePreset(
            MemorySlot.MR2,
            ManualControls(zoomRatio = 1f),
            ExtraSettings(
                mode = CaptureMode.PHOTO,
                lens = LensChoice.TELE3X,
                teleconverter = true,
                videoCodec = VideoCodec.AVC,
                transfer = ColorTransfer.SLOG3_CINE,
                videoStabMode = VideoStabMode.OFF,
            ),
            "",
            "",
        )
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        installController(engine, currentDeclaration(engine))
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(encoderInventoryLoaded = false)
        ViewModelTestAccess.setField(vm, "pendingCodecUntilInventory", VideoCodec.HEVC)
        ViewModelTestAccess.setField(vm, "pendingTransferUntilInventory", ColorTransfer.HLG)
        ViewModelTestAccess.setField(vm, "requestedVideoStabMode", VideoStabMode.ENHANCED)
        vm.onVolumeKeyAction(HardwareKeyAction.AEL)
        vm.onHardwareFullKey(true) // momentary lock; the operator's value is unlocked
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        assertTrue(vm.state.value.controls.aeLock)

        assertNotNull(vm.recallMemorySlot(MemorySlot.MR2)) // cancels the hold; arms AVC / S-Log3 / OFF
        val recallGeneration = currentGeneration(engine)
        assertNotNull(vm.recallMemorySlot(MemorySlot.MR1)) // the newer door, un-Ready recall superseded
        assertTrue(currentGeneration(engine) > recallGeneration)
        invokeRollback(engine, currentRollbackAttempt(engine))
        shadowOf(Looper.getMainLooper()).idle()
        vm.onHardwareFullKey(false) // the cancelled hold restores nothing

        assertFalse("the held lock must not survive", vm.state.value.controls.aeLock)
        assertEquals(VideoCodec.HEVC, ViewModelTestAccess.field(vm, "pendingCodecUntilInventory"))
        assertEquals(ColorTransfer.HLG, ViewModelTestAccess.field(vm, "pendingTransferUntilInventory"))
        assertEquals(VideoStabMode.ENHANCED, ViewModelTestAccess.field(vm, "requestedVideoStabMode"))
        ViewModelTestAccess.invoke(vm, "saveSettingsIfEnabled")
        val saved = SettingsStore(app).load()
        assertEquals(false, saved?.controls?.aeLock)
        assertEquals(VideoCodec.HEVC, saved?.extras?.videoCodec)
        assertEquals(ColorTransfer.HLG, saved?.extras?.transfer)
    }

    // AGG6-16: the encoder inventory landing BETWEEN a pre-inventory recall and its rollback consumed
    // the armed bank, so neither the pending restore nor the pipeline packet put the request back.
    @Test
    fun `rollback after the inventory landed restores the request the recall replaced`() {
        SettingsStore(app).savePreset(
            MemorySlot.MR2,
            ManualControls(zoomRatio = 1f),
            ExtraSettings(
                mode = CaptureMode.VIDEO,
                videoCodec = VideoCodec.AVC,
                transfer = ColorTransfer.SDR,
                heif = false,
                jpeg = true,
            ),
            "",
            "",
        )
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        installController(engine, currentDeclaration(engine))
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(encoderInventoryLoaded = false)
        val launchFormats = me.hletrd.telecampro.camera.PhotoFormats(heif = true, jpeg = false, dngRaw = false)
        ViewModelTestAccess.setField(vm, "pendingCodecUntilInventory", VideoCodec.HEVC)
        ViewModelTestAccess.setField(vm, "pendingTransferUntilInventory", ColorTransfer.HLG)
        ViewModelTestAccess.setField(vm, "pendingPhotoFormatsUntilInventory", launchFormats)

        assertNotNull(vm.recallMemorySlot(MemorySlot.MR2))
        val attempt = currentRollbackAttempt(engine)
        val inventory = me.hletrd.telecampro.video.buildCodecInventory(
            listOf(
                me.hletrd.telecampro.video.CodecComponent(
                    name = "vendor.hevc",
                    encoder = true,
                    supportedTypes = setOf(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC),
                    hardwareAccelerated = true,
                    hevcProfiles = setOf(android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
                ),
                me.hletrd.telecampro.video.CodecComponent(
                    name = "vendor.avc",
                    encoder = true,
                    supportedTypes = setOf(android.media.MediaFormat.MIMETYPE_VIDEO_AVC),
                    hardwareAccelerated = true,
                    hevcProfiles = emptySet(),
                ),
            ),
        )
        ViewModelTestAccess.invoke(vm, "applyEncoderInventory", inventory)
        assertEquals("the inventory applied the recalled bank", VideoCodec.AVC, vm.state.value.videoCodec)
        invokeRollback(engine, attempt)
        shadowOf(Looper.getMainLooper()).idle()

        val ui = vm.state.value
        assertEquals(VideoCodec.HEVC, ui.videoCodec)
        assertEquals(ColorTransfer.HLG, ui.transfer)
        assertEquals(launchFormats, ui.photoFormats)
        val extras = ViewModelTestAccess.invoke(vm, "currentExtras") as ExtraSettings
        assertEquals(VideoCodec.HEVC, extras.videoCodec)
        assertEquals(ColorTransfer.HLG, extras.transfer)
        assertTrue(extras.heif)
        assertFalse(extras.jpeg)
    }

    // MRG6-3: two stacked pre-inventory recalls, then the inventory, then a rollback reaching both.
    // Only the newer recall's armed bank was consumed, so the older record had nothing to undo and
    // the walk stopped on the OLDER bank's codec/curve/formats instead of the pre-recall request.
    @Test
    fun `stacked pre-inventory recalls roll back to the request before the first`() {
        fun saveVideoBank(slot: MemorySlot, codec: VideoCodec, transfer: ColorTransfer, heif: Boolean, jpeg: Boolean) =
            SettingsStore(app).savePreset(
                slot,
                ManualControls(zoomRatio = 1f),
                ExtraSettings(mode = CaptureMode.VIDEO, videoCodec = codec, transfer = transfer, heif = heif, jpeg = jpeg),
                "",
                "",
            )
        saveVideoBank(MemorySlot.MR1, VideoCodec.AVC, ColorTransfer.SLOG3_CINE, heif = false, jpeg = true)
        saveVideoBank(MemorySlot.MR2, VideoCodec.HEVC, ColorTransfer.SDR, heif = true, jpeg = true)
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        installController(engine, currentDeclaration(engine))
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(encoderInventoryLoaded = false)
        val launchFormats = me.hletrd.telecampro.camera.PhotoFormats(heif = true, jpeg = false, dngRaw = false)
        ViewModelTestAccess.setField(vm, "pendingCodecUntilInventory", VideoCodec.HEVC)
        ViewModelTestAccess.setField(vm, "pendingTransferUntilInventory", ColorTransfer.HLG)
        ViewModelTestAccess.setField(vm, "pendingPhotoFormatsUntilInventory", launchFormats)

        assertNotNull(vm.recallMemorySlot(MemorySlot.MR1))
        assertNotNull(vm.recallMemorySlot(MemorySlot.MR2))
        val attempt = currentRollbackAttempt(engine)
        val inventory = me.hletrd.telecampro.video.buildCodecInventory(
            listOf(
                me.hletrd.telecampro.video.CodecComponent(
                    name = "vendor.hevc",
                    encoder = true,
                    supportedTypes = setOf(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC),
                    hardwareAccelerated = true,
                    hevcProfiles = setOf(android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
                ),
                me.hletrd.telecampro.video.CodecComponent(
                    name = "vendor.avc",
                    encoder = true,
                    supportedTypes = setOf(android.media.MediaFormat.MIMETYPE_VIDEO_AVC),
                    hardwareAccelerated = true,
                    hevcProfiles = emptySet(),
                ),
            ),
        )
        ViewModelTestAccess.invoke(vm, "applyEncoderInventory", inventory)
        assertEquals("the inventory applied the newer bank", ColorTransfer.SDR, vm.state.value.transfer)
        assertEquals("no reopen superseded the recall", attempt.generation, currentGeneration(engine))
        invokeRollback(engine, attempt)
        shadowOf(Looper.getMainLooper()).idle()

        val ui = vm.state.value
        assertEquals(VideoCodec.HEVC, ui.videoCodec)
        assertEquals(ColorTransfer.HLG, ui.transfer)
        assertEquals(launchFormats, ui.photoFormats)
        val extras = ViewModelTestAccess.invoke(vm, "currentExtras") as ExtraSettings
        assertEquals(VideoCodec.HEVC, extras.videoCodec)
        assertEquals(ColorTransfer.HLG, extras.transfer)
    }

    // MRG5-8: a rolled-back recall's stabilization / frame-rate REQUESTS go back to the operator's,
    // so the rollback's own save does not persist the bank it was told did not load. A newer pick
    // made after the recall (here: the frame rate) is the operator's and survives.
    @Test
    fun `owned rollback restores the stabilization and frame-rate requests`() {
        SettingsStore(app).savePreset(
            MemorySlot.MR2,
            ManualControls(zoomRatio = 1f),
            ExtraSettings(
                mode = CaptureMode.VIDEO,
                videoStabMode = VideoStabMode.OFF,
                videoFrameRate = VideoFrameRate.FPS_25,
            ),
            "",
            "",
        )
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        installController(engine, currentDeclaration(engine))
        ViewModelTestAccess.setField(vm, "requestedVideoStabMode", VideoStabMode.STANDARD)
        ViewModelTestAccess.setField(vm, "requestedVideoFrameRate", VideoFrameRate.FPS_30)

        assertNotNull(vm.recallMemorySlot(MemorySlot.MR2))
        assertEquals(VideoStabMode.OFF, ViewModelTestAccess.field(vm, "requestedVideoStabMode"))
        val attempt = currentRollbackAttempt(engine)
        ViewModelTestAccess.setField(vm, "requestedVideoFrameRate", VideoFrameRate.FPS_24)
        invokeRollback(engine, attempt)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VideoStabMode.STANDARD, ViewModelTestAccess.field(vm, "requestedVideoStabMode"))
        assertEquals(
            "a newer pick is the operator's and survives",
            VideoFrameRate.FPS_24,
            ViewModelTestAccess.field(vm, "requestedVideoFrameRate"),
        )
        val extras = ViewModelTestAccess.invoke(vm, "currentExtras") as ExtraSettings
        assertEquals(VideoStabMode.STANDARD, extras.videoStabMode)
        assertEquals(VideoFrameRate.FPS_24, extras.videoFrameRate)
    }

    // AGG6-2: the restored requests must also reach the Engine (the recall pushed the bank's stab
    // and rate outside the optics transaction, so the Engine rollback cannot restore them) and the
    // screen. Before, the Engine kept recording the bank's OFF / 25 fps under a restored request.
    @Test
    fun `owned rollback puts the restored stabilization and rate on the Engine and the screen`() {
        SettingsStore(app).savePreset(
            MemorySlot.MR2,
            ManualControls(zoomRatio = 1f),
            ExtraSettings(
                mode = CaptureMode.VIDEO,
                videoStabMode = VideoStabMode.OFF,
                videoFrameRate = VideoFrameRate.FPS_25,
            ),
            "",
            "",
        )
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        installController(engine, currentDeclaration(engine))
        fun engineField(name: String): Any? = CameraEngine::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(engine)
        fun setEngineField(name: String, value: Any) = CameraEngine::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(engine, value)
        // The operator's ENHANCED / 30 fps, consistent on request, Engine and screen.
        ViewModelTestAccess.setField(vm, "requestedVideoStabMode", VideoStabMode.ENHANCED)
        ViewModelTestAccess.setField(vm, "requestedVideoFrameRate", VideoFrameRate.FPS_30)
        setEngineField("videoStabMode", VideoStabMode.ENHANCED)
        setEngineField("videoFrameRate", VideoFrameRate.FPS_30)
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(
            videoStabMode = VideoStabMode.ENHANCED,
            videoFrameRate = VideoFrameRate.FPS_30,
        )

        assertNotNull(vm.recallMemorySlot(MemorySlot.MR2))
        assertEquals(VideoStabMode.OFF, engineField("videoStabMode"))
        assertEquals(VideoFrameRate.FPS_25, engineField("videoFrameRate"))
        invokeRollback(engine, currentRollbackAttempt(engine))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(VideoStabMode.ENHANCED, engineField("videoStabMode"))
        assertEquals(VideoFrameRate.FPS_30, engineField("videoFrameRate"))
        val ui = vm.state.value
        assertEquals(VideoStabMode.ENHANCED, ui.videoStabMode)
        assertEquals(VideoFrameRate.FPS_30, ui.videoFrameRate)
        assertEquals(30, ui.controls.fps)
    }

    @Test
    fun `synchronous recording refusal leaves declaration and Engine packet unchanged`() {
        saveTelePreset(MemorySlot.MR1, PhoneModel.FIND_X9_ULTRA, TeleconverterProfile.EXPLORER_300)
        val (vm, engine) = createViewModel()
        vm.onPhoneModel(PhoneModel.VIVO_X300_ULTRA)
        vm.onTeleconverterProfile(TeleconverterProfile.ZEISS_200_X300)
        val beforeUi = vm.state.value
        val beforeEngine = currentDeclaration(engine)
        val beforeGeneration = currentGeneration(engine)
        setRecordingState(vm, true)

        vm.onRecallMemorySlot(MemorySlot.MR1)

        assertEquals(beforeUi.phoneModel, vm.state.value.phoneModel)
        assertEquals(beforeUi.teleconverterProfile, vm.state.value.teleconverterProfile)
        assertEquals(beforeEngine, currentDeclaration(engine))
        assertEquals(beforeGeneration, currentGeneration(engine))
    }

    @Test
    fun `owned asynchronous failure restores complete declaration in Engine and UI`() {
        saveTelePreset(MemorySlot.MR1, PhoneModel.FIND_X9_ULTRA, TeleconverterProfile.EXPLORER_300)
        val (vm, engine) = createViewModel()
        vm.onPhoneModel(PhoneModel.VIVO_X300_ULTRA)
        vm.onTeleconverterProfile(TeleconverterProfile.ZEISS_200_X300)
        setAcceptedTeleBaseline(vm, engine)
        val before = vm.state.value
        val beforeDeclaration = currentDeclaration(engine)
        val controller = installController(engine, beforeDeclaration)

        vm.onRecallMemorySlot(MemorySlot.MR1)
        val attempt = currentRollbackAttempt(engine)
        assertEquals(PhoneModel.FIND_X9_ULTRA, currentDeclaration(engine).phone)
        assertEquals(
            currentDeclaration(engine).magnification,
            currentControllerMagnification(controller),
            0f,
        )

        invokeRollback(engine, attempt)
        shadowOf(Looper.getMainLooper()).idle()

        val restored = vm.state.value
        assertEquals(PhoneModel.VIVO_X300_ULTRA, restored.phoneModel)
        assertEquals(TeleconverterProfile.ZEISS_200_X300, restored.teleconverterProfile)
        assertEquals(85f, restored.teleconverterHostEquivMm, 0f)
        assertEquals(200f, restored.teleconverterFocalMm, 0.001f)
        assertEquals(beforeDeclaration, currentDeclaration(engine))
        assertEquals(
            beforeDeclaration.magnification,
            currentControllerMagnification(controller),
            0f,
        )
        assertEquals(before.mode, restored.mode)
        assertEquals(before.controls, restored.controls)
    }

    @Test
    fun `rollback mirror keeps a video size picked after the rollback committed`() {
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        installController(engine, currentDeclaration(engine))
        engine.setCameraOverride("2")
        val attempt = currentRollbackAttempt(engine)
        // The rollback commits and queues its UI mirror; the operator's pick lands on the main
        // queue before that mirror runs (AGG2-7).
        invokeRollback(engine, attempt)
        val picked = android.util.Size(1920, 1080)
        vm.onVideoResolution(picked)
        shadowOf(Looper.getMainLooper()).idle()

        val mirrored = CameraViewModel::class.java.getDeclaredField("requestedVideoResolution")
            .apply { isAccessible = true }
            .get(vm)
        assertEquals(picked, mirrored)
        assertEquals(picked, engine.currentRequestedVideoSize())
    }

    @Test
    fun `rollback mirror keeps a DNG toggle made after the rollback committed`() {
        val (vm, engine) = createViewModel()
        setAcceptedTeleBaseline(vm, engine)
        installController(engine, currentDeclaration(engine))
        assertFalse(vm.state.value.photoFormats.dngRaw)
        engine.setCameraOverride("2")
        val attempt = currentRollbackAttempt(engine)
        // TELE pins the standalone 3x, so the DNG tap is a direct write the engine keeps; it lands
        // on the main queue before the rollback's mirror runs (AGG3-10).
        invokeRollback(engine, attempt)
        vm.onSetPhotoFormats(vm.state.value.photoFormats.copy(dngRaw = true))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(engine.currentRawWanted())
        assertTrue("chip follows the engine's live intent", vm.state.value.photoFormats.dngRaw)
    }

    @Test
    fun `superseded rollback cannot replace a newer recalled declaration`() {
        saveTelePreset(MemorySlot.MR2, PhoneModel.VIVO_X300_ULTRA, TeleconverterProfile.ZEISS_200_X300)
        saveTelePreset(MemorySlot.MR3, PhoneModel.FIND_X9_ULTRA, TeleconverterProfile.EXPLORER_300)
        val (vm, engine) = createViewModel()
        vm.onPhoneModel(PhoneModel.OTHER)
        vm.onTeleconverterProfile(TeleconverterProfile.GENERIC_2)
        setAcceptedTeleBaseline(vm, engine)
        val oldDeclaration = currentDeclaration(engine)
        val controller = installController(engine, oldDeclaration)

        vm.onRecallMemorySlot(MemorySlot.MR2)
        val superseded = currentRollbackAttempt(engine)
        vm.onRecallMemorySlot(MemorySlot.MR3)
        val newestDeclaration = currentDeclaration(engine)
        invokeRollback(engine, superseded)
        shadowOf(Looper.getMainLooper()).idle()

        val newest = vm.state.value
        assertFalse(engine.isOpticsGenerationCurrent(superseded.generation))
        assertEquals(PhoneModel.FIND_X9_ULTRA, newest.phoneModel)
        assertEquals(TeleconverterProfile.EXPLORER_300, newest.teleconverterProfile)
        assertEquals(300f, newest.teleconverterFocalMm, 0.001f)
        assertEquals(newestDeclaration, currentDeclaration(engine))
        assertEquals(
            newestDeclaration.magnification,
            currentControllerMagnification(controller),
            0f,
        )
    }
}
