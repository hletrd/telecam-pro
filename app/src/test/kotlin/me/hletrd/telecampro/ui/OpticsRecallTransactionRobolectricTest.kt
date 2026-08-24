package me.hletrd.telecampro.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import me.hletrd.telecampro.camera.CameraController
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraRouteInventory
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.LensChoice
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MemorySlot
import me.hletrd.telecampro.camera.PhoneModel
import me.hletrd.telecampro.camera.TeleconverterDeclaration
import me.hletrd.telecampro.camera.TeleconverterProfile
import me.hletrd.telecampro.camera.effectiveFocalMm
import me.hletrd.telecampro.camera.status
import me.hletrd.telecampro.storage.ExtraSettings
import me.hletrd.telecampro.storage.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        return RollbackAttempt(generation, constructor.newInstance(generation, baseline))
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
