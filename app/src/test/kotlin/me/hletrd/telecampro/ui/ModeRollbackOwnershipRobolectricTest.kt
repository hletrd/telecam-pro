package me.hletrd.telecampro.ui

import android.app.Application
import android.media.MediaFormat
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import me.hletrd.telecampro.camera.CameraController
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.status
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.video.EncoderSelection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
class ModeRollbackOwnershipRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var vm: CameraViewModel? = null

    private fun createAccepted(
        mode: CaptureMode,
        acceptedTransfer: ColorTransfer = transferFor(mode),
        requestedTransfer: ColorTransfer = ColorTransfer.HLG,
    ): Pair<CameraViewModel, CameraEngine> {
        RobolectricEglSentinels.ensure()
        val engine = CameraEngine(app)
        val viewModel = CameraViewModel(app, engine)
        vm = viewModel
        // Drain constructor-time capability publication before installing the accepted fixture.
        shadowOf(Looper.getMainLooper()).idle()
        engine.setVideoEncoders(
            listOf(
                EncoderSelection(
                    VideoCodec.HEVC,
                    "test-main10",
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    hardwareAccelerated = true,
                    main10 = true,
                ),
            ),
        )
        val controller = CameraController(app)
        setField(engine, "controller", controller)
        setField(engine, "readyController", controller)
        setBoolean(engine, "cameraReady", true)
        setBoolean(engine, "previewReady", true)
        setBoolean(engine, "videoMode", mode == CaptureMode.VIDEO)
        setField(engine, "transfer", acceptedTransfer)
        setBoolean(viewModel, "lifecycleStarted", true)
        state(viewModel).value = state(viewModel).value.copy(
            cameraReady = true,
            mode = mode,
            transfer = requestedTransfer,
            videoCodec = VideoCodec.HEVC,
            tenBitEncodeAvailable = true,
            recordAudio = true,
        )
        viewModel.onStandbyAudioMeterVisibilityChanged(true)
        return viewModel to engine
    }

    private fun forceOwnedRollback(engine: CameraEngine, acceptedTransfer: ColorTransfer) {
        val generation = (field(engine, "opticsIntentGeneration") as AtomicLong).get()
        val baseline = checkNotNull(field(engine, "opticsRollbackBaseline"))
        assertEquals(acceptedTransfer, field(baseline, "transfer"))
        val transactionType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "OpticsTransaction" }
        val transaction = transactionType.declaredConstructors.single()
            .apply { isAccessible = true }
            .newInstance(generation, baseline)
        CameraEngine::class.java.declaredMethods.single { it.name == "rollbackOptics" }
            .apply { isAccessible = true }
            .invoke(engine, transaction, CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED.status())
        assertEquals(acceptedTransfer, engineTransfer(engine))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `failed Video to Photo restores transfer standby ownership and Ready`() {
        val (viewModel, engine) = createAccepted(CaptureMode.VIDEO)
        assertTrue(standbyWanted(engine))

        viewModel.onModeChange(CaptureMode.PHOTO)
        assertEquals(ColorTransfer.SDR, engineTransfer(engine))
        assertFalse(standbyWanted(engine))

        forceOwnedRollback(engine, ColorTransfer.HLG)

        assertEquals(CaptureMode.VIDEO, viewModel.state.value.mode)
        assertEquals(ColorTransfer.HLG, engineTransfer(engine))
        assertTrue(standbyWanted(engine))
        assertTrue(viewModel.state.value.cameraReady)
        assertEquals(
            "the next recording must use the accepted Video transfer",
            ColorTransfer.HLG,
            engineTransfer(engine),
        )
    }

    @Test
    fun `external permission surface releases and restores standby microphone ownership`() {
        val (viewModel, engine) = createAccepted(CaptureMode.VIDEO)
        assertTrue(standbyWanted(engine))

        viewModel.onCameraInputBlockOwnerChange(CameraInputBlockOwner.MEDIA_PERMISSION, true)
        assertTrue(viewModel.state.value.cameraInputBlocked)
        assertFalse(standbyWanted(engine))

        viewModel.onCameraInputBlockOwnerChange(CameraInputBlockOwner.MEDIA_PERMISSION, false)
        assertFalse(viewModel.state.value.cameraInputBlocked)
        assertTrue(standbyWanted(engine))
    }

    @Test
    fun `failed Photo to Video restores SDR drops standby and Ready`() {
        val (viewModel, engine) = createAccepted(CaptureMode.PHOTO)
        assertFalse(standbyWanted(engine))
        val generationBefore = (field(engine, "opticsIntentGeneration") as AtomicLong).get()

        viewModel.onModeChange(CaptureMode.VIDEO)
        assertEquals(ColorTransfer.HLG, engineTransfer(engine))
        assertEquals(
            "mode and transfer must publish through one optics generation",
            generationBefore + 1,
            (field(engine, "opticsIntentGeneration") as AtomicLong).get(),
        )
        assertTrue(standbyWanted(engine))

        forceOwnedRollback(engine, ColorTransfer.SDR)

        assertEquals(CaptureMode.PHOTO, viewModel.state.value.mode)
        assertEquals(ColorTransfer.SDR, engineTransfer(engine))
        assertFalse(standbyWanted(engine))
        assertTrue(viewModel.state.value.cameraReady)
    }

    @Test
    fun `failed direct transfer reopen restores the accepted pre-mutation transfer`() {
        val (_, engine) = createAccepted(
            CaptureMode.VIDEO,
            acceptedTransfer = ColorTransfer.SDR,
            requestedTransfer = ColorTransfer.SDR,
        )
        val generationBefore = (field(engine, "opticsIntentGeneration") as AtomicLong).get()

        engine.setTransfer(ColorTransfer.HLG)

        assertEquals(ColorTransfer.HLG, engineTransfer(engine))
        assertEquals(
            generationBefore + 1,
            (field(engine, "opticsIntentGeneration") as AtomicLong).get(),
        )
        forceOwnedRollback(engine, ColorTransfer.SDR)
        assertEquals(ColorTransfer.SDR, engineTransfer(engine))
        assertTrue(engineReady(engine))
    }

    private fun transferFor(mode: CaptureMode) =
        if (mode == CaptureMode.VIDEO) ColorTransfer.HLG else ColorTransfer.SDR

    @Suppress("UNCHECKED_CAST")
    private fun state(viewModel: CameraViewModel) =
        field(viewModel, "_state") as MutableStateFlow<CameraUiState>

    private fun engineTransfer(engine: CameraEngine) = field(engine, "transfer") as ColorTransfer
    private fun standbyWanted(engine: CameraEngine) = field(engine, "standbyAudioMonitorWanted") as Boolean
    private fun engineReady(engine: CameraEngine) = field(engine, "cameraReady") as Boolean

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(owner)

    private fun setField(owner: Any, name: String, value: Any?) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    private fun setBoolean(owner: Any, name: String, value: Boolean) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.setBoolean(owner, value)
    }

    @After
    fun tearDown() {
        vm?.let {
            CameraViewModel::class.java.getDeclaredMethod("onCleared")
                .apply { isAccessible = true }
                .invoke(it)
        }
    }
}
