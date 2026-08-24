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
import me.hletrd.telecampro.camera.recordingEncoderAdmission
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.video.EncoderSelection
import me.hletrd.telecampro.video.CodecInventory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
class ModeRollbackOwnershipRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var vm: CameraViewModel? = null

    @Test
    fun `video pipeline mutation serializes its decision with optics rollback`() {
        val method = CameraEngine::class.java.getDeclaredMethod(
            "setVideoPipeline",
            List::class.java,
            ColorTransfer::class.java,
            VideoCodec::class.java,
        )

        assertTrue(
            "setVideoPipeline must hold the Engine monitor across mode-transfer derivation and commit",
            Modifier.isSynchronized(method.modifiers),
        )
    }

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
        // Keep a late real-platform inventory callback from overwriting the deterministic fixture.
        setBoolean(viewModel, "cleared", true)
        val main10 = EncoderSelection(
            VideoCodec.HEVC,
            "test-main10",
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            hardwareAccelerated = true,
            main10 = true,
        )
        engine.setVideoEncoders(listOf(main10))
        setField(viewModel, "encoderInventory", CodecInventory(mapOf(
            VideoCodec.HEVC to listOf(main10),
        )))
        val controller = CameraController(app)
        setField(engine, "controller", controller)
        setField(engine, "readyController", controller)
        setBoolean(engine, "cameraReady", true)
        setBoolean(engine, "previewReady", true)
        setBoolean(engine, "videoMode", mode == CaptureMode.VIDEO)
        setField(engine, "transfer", acceptedTransfer)
        setField(engine, "requestedVideoTransfer", requestedTransfer)
        setBoolean(viewModel, "lifecycleStarted", true)
        state(viewModel).value = state(viewModel).value.copy(
            cameraReady = true,
            mode = mode,
            transfer = requestedTransfer,
            videoCodec = VideoCodec.HEVC,
            tenBitEncodeAvailable = true,
            encoderInventoryLoaded = true,
            availableVideoCodecs = listOf(VideoCodec.HEVC),
            recordAudio = true,
        )
        viewModel.onStandbyAudioMeterVisibilityChanged(true)
        return viewModel to engine
    }

    private fun forceOwnedRollback(
        engine: CameraEngine,
        acceptedTransfer: ColorTransfer,
        requestedTransfer: ColorTransfer = acceptedTransfer,
    ) {
        val generation = (field(engine, "opticsIntentGeneration") as AtomicLong).get()
        val baseline = checkNotNull(field(engine, "opticsRollbackBaseline"))
        assertEquals(acceptedTransfer, field(baseline, "transfer"))
        assertEquals(
            requestedTransfer,
            field(checkNotNull(field(baseline, "videoPipeline")), "requestedTransfer"),
        )
        val transactionType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "OpticsTransaction" }
        val transaction = transactionType.declaredConstructors.single()
            .apply { isAccessible = true }
            .newInstance(generation, baseline)
        CameraEngine::class.java.declaredMethods.single { it.name == "rollbackOptics" }
            .apply { isAccessible = true }
            .invoke(engine, transaction, CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED.status())
        assertEquals(acceptedTransfer, engineTransfer(engine))
        assertEquals(requestedTransfer, field(engine, "requestedVideoTransfer"))
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

        forceOwnedRollback(engine, ColorTransfer.SDR, ColorTransfer.HLG)

        assertEquals(CaptureMode.PHOTO, viewModel.state.value.mode)
        assertEquals(ColorTransfer.SDR, engineTransfer(engine))
        assertFalse(standbyWanted(engine))
        assertTrue(viewModel.state.value.cameraReady)
    }

    @Test
    fun `failed direct transfer reopen restores the accepted pre-mutation transfer`() {
        val (viewModel, engine) = createAccepted(
            CaptureMode.VIDEO,
            acceptedTransfer = ColorTransfer.SDR,
            requestedTransfer = ColorTransfer.SDR,
        )
        val generationBefore = (field(engine, "opticsIntentGeneration") as AtomicLong).get()

        viewModel.onTransfer(ColorTransfer.HLG)

        assertEquals(ColorTransfer.HLG, engineTransfer(engine))
        assertEquals(
            generationBefore + 1,
            (field(engine, "opticsIntentGeneration") as AtomicLong).get(),
        )
        forceOwnedRollback(engine, ColorTransfer.SDR)
        assertEquals(ColorTransfer.SDR, engineTransfer(engine))
        assertEquals(ColorTransfer.SDR, viewModel.state.value.transfer)
        assertEquals(VideoCodec.HEVC, viewModel.state.value.videoCodec)
        assertTrue(engineReady(engine))
    }

    @Test
    fun `failed HLG to SDR reopen restores visible and active HLG`() {
        val (viewModel, engine) = createAccepted(CaptureMode.VIDEO)

        viewModel.onTransfer(ColorTransfer.SDR)

        assertEquals(ColorTransfer.SDR, engineTransfer(engine))
        forceOwnedRollback(engine, ColorTransfer.HLG)
        assertEquals(ColorTransfer.HLG, engineTransfer(engine))
        assertEquals(ColorTransfer.HLG, viewModel.state.value.transfer)
        assertTrue(engineReady(engine))
    }

    @Test
    fun `failed HEVC HLG to AVC SDR restores complete encoder tuple`() {
        val (viewModel, engine) = createAccepted(CaptureMode.VIDEO)
        val hevc = EncoderSelection(
            VideoCodec.HEVC,
            "test-main10",
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            hardwareAccelerated = true,
            main10 = true,
        )
        val avc = EncoderSelection(
            VideoCodec.AVC,
            "test-avc",
            MediaFormat.MIMETYPE_VIDEO_AVC,
            hardwareAccelerated = true,
            main10 = false,
        )
        setField(viewModel, "encoderInventory", CodecInventory(mapOf(
            VideoCodec.HEVC to listOf(hevc),
            VideoCodec.AVC to listOf(avc),
        )))
        state(viewModel).value = state(viewModel).value.copy(
            encoderInventoryLoaded = true,
            availableVideoCodecs = listOf(VideoCodec.HEVC, VideoCodec.AVC),
        )

        viewModel.onVideoCodec(VideoCodec.AVC)

        assertEquals(VideoCodec.AVC, field(engine, "videoCodec"))
        assertEquals(ColorTransfer.SDR, engineTransfer(engine))
        forceOwnedRollback(engine, ColorTransfer.HLG)
        assertEquals(VideoCodec.HEVC, field(engine, "videoCodec"))
        assertEquals(listOf(hevc), field(engine, "videoEncoderCandidates"))
        val recAdmission = recordingEncoderAdmission(
            frameRateAvailable = true,
            codec = field(engine, "videoCodec") as VideoCodec,
            transfer = engineTransfer(engine),
            candidates = (field(engine, "videoEncoderCandidates") as List<*>)
                .filterIsInstance<EncoderSelection>(),
        )
        assertEquals(null, recAdmission.failure)
        assertEquals(
            listOf(hevc),
            recAdmission.candidates,
        )
        assertEquals(VideoCodec.HEVC, viewModel.state.value.videoCodec)
        assertEquals(ColorTransfer.HLG, viewModel.state.value.transfer)
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
