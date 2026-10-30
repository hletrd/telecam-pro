package me.hletrd.telecampro.ui

import android.app.Application
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.os.Looper
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import me.hletrd.telecampro.camera.CameraController
import me.hletrd.telecampro.camera.CameraCaps
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.CameraStatus
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.CameraUiState
import me.hletrd.telecampro.camera.CaptureMode
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import me.hletrd.telecampro.camera.TeleSelection
import me.hletrd.telecampro.camera.VideoFrameRate
import me.hletrd.telecampro.camera.VideoSessionIntent
import me.hletrd.telecampro.camera.status
import me.hletrd.telecampro.camera.recordingEncoderAdmission
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.camera.VideoPipelineEngineOverrides
import me.hletrd.telecampro.video.EncoderSelection
import me.hletrd.telecampro.video.EncoderFormatSupport
import me.hletrd.telecampro.video.CodecInventory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Test
    fun `pipeline command queued behind rollback cannot publish Photo with active HLG`() {
        val (viewModel, engine) = createAccepted(CaptureMode.PHOTO)
        viewModel.onModeChange(CaptureMode.VIDEO)
        assertEquals(ColorTransfer.HLG, engineTransfer(engine))
        val candidates = (field(engine, "videoEncoderCandidates") as List<*>)
            .filterIsInstance<EncoderSelection>()
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val pipelineThread: Thread

        synchronized(engine) {
            pipelineThread = Thread {
                started.countDown()
                try {
                    engine.setVideoPipeline(candidates, ColorTransfer.HLG)
                } finally {
                    completed.countDown()
                }
            }.apply { start() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (pipelineThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, pipelineThread.state)
            forceOwnedRollback(engine, ColorTransfer.SDR, ColorTransfer.HLG)
            assertFalse(field(engine, "videoMode") as Boolean)
            assertEquals(ColorTransfer.SDR, engineTransfer(engine))
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        pipelineThread.join()
        assertFalse(field(engine, "videoMode") as Boolean)
        assertEquals(ColorTransfer.SDR, engineTransfer(engine))
        assertEquals(ColorTransfer.HLG, field(engine, "requestedVideoTransfer"))
    }

    @Test
    fun `newer disjoint pipeline command survives the queued rollback publication`() {
        val (viewModel, engine) = createAccepted(CaptureMode.PHOTO)
        viewModel.onModeChange(CaptureMode.VIDEO)
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val pipelineThread: Thread

        synchronized(engine) {
            pipelineThread = Thread {
                started.countDown()
                try {
                    viewModel.onVideoCodec(VideoCodec.AVC)
                } finally {
                    completed.countDown()
                }
            }.apply { start() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (pipelineThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, pipelineThread.state)
            forceOwnedRollback(
                engine,
                acceptedTransfer = ColorTransfer.SDR,
                requestedTransfer = ColorTransfer.HLG,
                drainMain = false,
            )
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        pipelineThread.join()
        assertEquals(VideoCodec.AVC, viewModel.state.value.videoCodec)
        assertEquals(ColorTransfer.SDR, viewModel.state.value.transfer)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CaptureMode.PHOTO, viewModel.state.value.mode)
        assertEquals(VideoCodec.AVC, viewModel.state.value.videoCodec)
        assertEquals(ColorTransfer.SDR, viewModel.state.value.transfer)
        assertEquals(VideoCodec.AVC, field(engine, "videoCodec"))
        assertEquals(ColorTransfer.SDR, field(engine, "requestedVideoTransfer"))
        val admitted = recordingEncoderAdmission(
            frameRateAvailable = true,
            codec = field(engine, "videoCodec") as VideoCodec,
            transfer = field(engine, "requestedVideoTransfer") as ColorTransfer,
            candidates = (field(engine, "videoEncoderCandidates") as List<*>)
                .filterIsInstance<EncoderSelection>(),
        )
        assertEquals(null, admitted.failure)
        assertEquals(listOf(VideoCodec.AVC), admitted.candidates.map { it.codec })
    }

    @Test
    fun `surviving newer codec reprojects restored route caps and delivered size`() {
        val (viewModel, engine) = createAccepted(
            CaptureMode.VIDEO,
            acceptedTransfer = ColorTransfer.SDR,
            requestedTransfer = ColorTransfer.SDR,
        )
        val eightK = Size(7680, 4320)
        val uhd = Size(3840, 2160)
        val raw = rollbackCaps().copy(availableVideoSizes = listOf(eightK, uhd))
        val hevc8k = EncoderSelection(
            VideoCodec.HEVC,
            "test-hevc-8k",
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            hardwareAccelerated = true,
            main10 = true,
            formatSupport = EncoderFormatSupport { format ->
                format.getFloat(MediaFormat.KEY_FRAME_RATE) <= 30f
            },
        )
        val avcUhd = EncoderSelection(
            VideoCodec.AVC,
            "test-avc-uhd",
            MediaFormat.MIMETYPE_VIDEO_AVC,
            hardwareAccelerated = true,
            main10 = false,
            formatSupport = EncoderFormatSupport { format ->
                format.getInteger(MediaFormat.KEY_WIDTH) <= 3840 &&
                    format.getFloat(MediaFormat.KEY_FRAME_RATE) <= 30f
            },
        )
        setField(viewModel, "encoderInventory", CodecInventory(mapOf(
            VideoCodec.HEVC to listOf(hevc8k),
            VideoCodec.AVC to listOf(avcUhd),
        )))
        setField(engine, "selection", TeleSelection("test", null, 23f))
        setField(engine, "caps", raw)
        @Suppress("UNCHECKED_CAST")
        val rawCache = field(engine, "capsCache")
            as java.util.concurrent.ConcurrentHashMap<String, CameraCaps>
        rawCache["test:null"] = raw
        engine.setVideoPipeline(listOf(hevc8k), ColorTransfer.SDR)
        viewModel.onVideoResolution(eightK)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("precondition: HEVC delivers the explicit 8K baseline", eightK, field(engine, "videoSize"))

        viewModel.onModeChange(CaptureMode.PHOTO)
        viewModel.onVideoCodec(VideoCodec.AVC)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the newer Photo-mode command reconciles to UHD", uhd, field(engine, "videoSize"))

        forceOwnedRollback(
            engine,
            acceptedTransfer = ColorTransfer.SDR,
            requestedTransfer = ColorTransfer.SDR,
        )

        assertEquals(CaptureMode.VIDEO, viewModel.state.value.mode)
        assertEquals("the newer codec survives the older rollback", VideoCodec.AVC, field(engine, "videoCodec"))
        assertEquals("rollback caps are projected for that codec", listOf(uhd), (field(engine, "caps") as CameraCaps).availableVideoSizes)
        assertEquals("rollback delivered size uses the same projection", uhd, field(engine, "videoSize"))
        assertEquals("the explicit request still survives", eightK, field(engine, "requestedVideoSize"))
        assertEquals("the actual accepted stream is retained until its reopen", eightK, field(engine, "previewStreamSize"))
        assertFalse("the restored VIDEO session cannot be Ready at a different raster", engineReady(engine))
        assertNull("REC admission is refused until the reprojected stream is accepted", currentAcceptedRecordingSession(engine))
        assertEquals(listOf(uhd), viewModel.state.value.caps?.availableVideoSizes)
        assertEquals(uhd, viewModel.state.value.videoResolution)
        assertEquals("raw camera truth is never narrowed", listOf(eightK, uhd), rawCache["test:null"]!!.availableVideoSizes)
    }

    @Test
    fun `surviving newer pipeline at one raster cannot restore a stale REC intent`() {
        val (viewModel, engine) = createAccepted(
            CaptureMode.VIDEO,
            acceptedTransfer = ColorTransfer.SDR,
            requestedTransfer = ColorTransfer.SDR,
        )
        val eightK = Size(7680, 4320)
        val route = TeleSelection("test", null, 23f)
        val raw = rollbackCaps().copy(
            availableFpsRanges = arrayOf(Range(30, 30)),
            availableVideoSizes = listOf(eightK),
            extendedVideoSizeRates = mapOf(eightK to setOf(23.976, 24.0, 29.97, 30.0)),
        )
        val hevc = EncoderSelection(
            VideoCodec.HEVC,
            "test-hevc-29-97",
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            hardwareAccelerated = true,
            main10 = true,
            formatSupport = EncoderFormatSupport { format ->
                format.getFloat(MediaFormat.KEY_FRAME_RATE) <= 29.97f
            },
        )
        val avc30 = EncoderSelection(
            VideoCodec.AVC,
            "test-avc-30",
            MediaFormat.MIMETYPE_VIDEO_AVC,
            hardwareAccelerated = true,
            main10 = false,
            formatSupport = EncoderFormatSupport { format ->
                format.getFloat(MediaFormat.KEY_FRAME_RATE) == 30f
            },
        )
        setField(viewModel, "encoderInventory", CodecInventory(mapOf(
            VideoCodec.HEVC to listOf(hevc),
            VideoCodec.AVC to listOf(avc30),
        )))
        setField(engine, "selection", route)
        setField(engine, "caps", raw)
        setField(engine, "requestedVideoSize", eightK)
        setField(engine, "videoSize", eightK)
        setField(engine, "previewStreamSize", eightK)
        @Suppress("UNCHECKED_CAST")
        val rawCache = field(engine, "capsCache")
            as java.util.concurrent.ConcurrentHashMap<String, CameraCaps>
        rawCache["test:null"] = raw
        engine.setVideoPipeline(listOf(hevc), ColorTransfer.SDR)
        val controller = field(engine, "controller") as CameraController
        controller.stageVideoSessionIntentForOpen(
            VideoSessionIntent(
                route = route,
                codec = VideoCodec.HEVC,
                transfer = ColorTransfer.SDR,
                rawLog = false,
                openGate = false,
                streamSize = eightK,
                encoderSize = Size(eightK.height, eightK.width),
                frameRate = VideoFrameRate.FPS_29_97,
            ),
        )
        acceptCurrentSession(engine, controller)
        val acceptedRec = checkNotNull(currentRecordingAdmissionSnapshot(engine))
        assertEquals(VideoCodec.HEVC, field(checkNotNull(field(acceptedRec, "inputs")), "codec"))

        viewModel.onModeChange(CaptureMode.PHOTO)
        viewModel.onVideoCodec(VideoCodec.AVC)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VideoCodec.AVC, field(engine, "videoCodec"))
        assertEquals("the new packet normalizes cadence without changing raster", VideoFrameRate.FPS_30, field(engine, "videoFrameRate"))
        assertEquals("the retained controller still describes the old accepted graph", VideoCodec.HEVC, controller.videoSessionIntent?.codec)

        forceOwnedRollback(
            engine,
            acceptedTransfer = ColorTransfer.SDR,
            requestedTransfer = ColorTransfer.SDR,
        )

        assertEquals(CaptureMode.VIDEO, viewModel.state.value.mode)
        assertFalse("a complete intent mismatch must reopen before Ready", engineReady(engine))
        assertNull("REC cannot freeze the retained HEVC/29.97 intent for AVC/30", currentRecordingAdmissionSnapshot(engine))
    }

    @Test
    fun `post-mutation recovery with no resolvable video intent enters bounded retry`() {
        val (_, engine) = createAccepted(CaptureMode.VIDEO)
        val eightK = Size(7680, 4320)
        val raw = rollbackCaps().copy(availableVideoSizes = listOf(eightK))
        setField(engine, "selection", TeleSelection("test", null, 23f))
        setField(engine, "caps", raw)
        setField(engine, "requestedVideoSize", null)
        setField(engine, "started", true)
        @Suppress("UNCHECKED_CAST")
        val rawCache = field(engine, "capsCache")
            as java.util.concurrent.ConcurrentHashMap<String, CameraCaps>
        rawCache["test:null"] = raw
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        val owners = checkNotNull(field(engine, "glOwners"))
        val gl = checkNotNull(owners.javaClass.getDeclaredMethod("current").invoke(owners))
        setField(gl, "inputSurface", surface)
        val retrying = CountDownLatch(1)
        engine.onStatus = {
            if (it?.message == CameraStatusMessage.CAMERA_UNAVAILABLE_RETRYING) retrying.countDown()
        }
        val desired = CameraEngine::class.java.getDeclaredMethod("currentOpticsReconfiguration")
            .apply { isAccessible = true }.invoke(engine)
        val transaction = checkNotNull(field(checkNotNull(desired), "transaction"))

        CameraEngine::class.java.getDeclaredMethod(
            "reconfigureCamera",
            String::class.java,
            transaction.javaClass,
            Boolean::class.javaPrimitiveType,
            Class.forName("me.hletrd.telecampro.camera.StartupTrace\$Owner"),
        ).apply { isAccessible = true }.invoke(engine, "test", transaction, false, null)

        assertTrue("the unresolved intent must reach BARE_RETRY", retrying.await(2, TimeUnit.SECONDS))
        setField(engine, "started", false)
        surface.release()
        texture.release()
    }

    @Test
    fun `rollback reopen effects cannot recursively retain the rejected transaction`() {
        val effectsType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "OpticsRollbackEffects" }
        val transactionType = CameraEngine::class.java.declaredClasses
            .single { it.simpleName == "OpticsTransaction" }

        assertFalse(
            "a mismatch reopen must be requested as a Boolean and rebuilt post-mutation",
            effectsType.declaredFields.any { transactionType.isAssignableFrom(it.type) },
        )
    }

    @Test
    fun `newer same precision packet is the observable GL winner after rollback`() {
        val glTransfers = mutableListOf<ColorTransfer?>()
        val (viewModel, engine) = createAccepted(
            mode = CaptureMode.VIDEO,
            acceptedTransfer = ColorTransfer.HLG,
            requestedTransfer = ColorTransfer.HLG,
            videoPipelineOverrides = VideoPipelineEngineOverrides(glTransfers::add),
        )
        glTransfers.clear()
        val transaction = beginUnchangedOpticsTransaction(engine)

        viewModel.onTransfer(ColorTransfer.SLOG3)
        assertEquals(ColorTransfer.SLOG3, glTransfers.last())
        forceOwnedRollback(engine, transaction, drainMain = false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(ColorTransfer.SLOG3, glTransfers.last())
        assertEquals(ColorTransfer.SLOG3, engineTransfer(engine))
        assertEquals(ColorTransfer.SLOG3, field(engine, "requestedVideoTransfer"))
        assertEquals(ColorTransfer.SLOG3, viewModel.state.value.transfer)
        val admitted = recordingEncoderAdmission(
            frameRateAvailable = true,
            codec = field(engine, "videoCodec") as VideoCodec,
            transfer = engineTransfer(engine),
            candidates = (field(engine, "videoEncoderCandidates") as List<*>)
                .filterIsInstance<EncoderSelection>(),
        )
        assertEquals(null, admitted.failure)
    }

    @Test
    fun `rollback commits state then invokes every callback after releasing Engine monitor`() {
        val (_, engine) = createAccepted(CaptureMode.PHOTO)
        val caps = rollbackCaps()
        setField(engine, "caps", caps)
        val transaction = beginUnchangedOpticsTransaction(engine)
        val lockStates = linkedMapOf<String, Boolean>()
        var rollbackControls: Any? = null
        var publishedCaps: CameraCaps? = null
        var publishedSize: Size? = null
        var publishedAspect: Float? = null
        var ready = false
        var status: CameraStatus? = null
        engine.onOpticsRollback = {
            lockStates["optics"] = Thread.holdsLock(engine)
            rollbackControls = it.controls
        }
        engine.onCapsReady = { value, _ ->
            lockStates["caps"] = Thread.holdsLock(engine)
            publishedCaps = value
        }
        engine.onVideoSizeChosen = { value, _ ->
            lockStates["size"] = Thread.holdsLock(engine)
            publishedSize = value
        }
        engine.onPreviewAspect = { value, _ ->
            lockStates["aspect"] = Thread.holdsLock(engine)
            publishedAspect = value
        }
        engine.onCameraReadyChange = {
            lockStates["ready"] = Thread.holdsLock(engine)
            ready = it.ready
        }
        engine.onStatus = {
            lockStates["status"] = Thread.holdsLock(engine)
            status = it
        }

        forceOwnedRollback(engine, transaction, drainMain = false)

        assertEquals(
            setOf("optics", "caps", "size", "aspect", "ready", "status"),
            lockStates.keys,
        )
        assertTrue(lockStates.values.none { it })
        assertEquals(field(engine, "controls"), rollbackControls)
        assertSame(caps, publishedCaps)
        assertEquals(Size(1920, 1080), publishedSize)
        assertEquals(1080f / 1920f, publishedAspect)
        assertTrue(ready)
        assertEquals(CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED, status?.message)
        val method = CameraEngine::class.java.declaredMethods.single { it.name == "rollbackOptics" }
        assertFalse("rollback wrapper must not own the Engine monitor", Modifier.isSynchronized(method.modifiers))
    }

    @Test
    fun `maker gamma round trip keeps UI and engine framing and clears the cached standalone id`() {
        val (viewModel, engine) = createAccepted(
            CaptureMode.VIDEO, ColorTransfer.SLOG3, ColorTransfer.SLOG3,
        )
        setField(engine, "deviceProfile", me.hletrd.telecampro.camera.DeviceProfile.resolve("PMA110"))
        setField(engine, "lensChoice", me.hletrd.telecampro.camera.LensChoice.TELE3X)
        val controls = me.hletrd.telecampro.camera.ManualControls(zoomRatio = 1.25f)
        setField(engine, "controls", controls)
        setField(viewModel, "pendingControls", controls)
        setField(engine, "overrideId", "4")
        state(viewModel).value = state(viewModel).value.copy(
            makerLogSupported = true,
            lens = me.hletrd.telecampro.camera.LensChoice.TELE3X,
            controls = controls,
        )
        viewModel.onTransfer(ColorTransfer.MAKER_LOG)
        assertEquals(3.75f, viewModel.state.value.controls.zoomRatio, 1e-6f)
        assertEquals(3.75f, (field(engine, "controls") as me.hletrd.telecampro.camera.ManualControls).zoomRatio, 1e-6f)
        assertEquals(null, field(engine, "overrideId"))
        viewModel.onVideoFrameRate(me.hletrd.telecampro.camera.VideoFrameRate.FPS_60)
        assertEquals(1.25f, viewModel.state.value.controls.zoomRatio, 1e-6f)
        assertEquals(1.25f, (field(engine, "controls") as me.hletrd.telecampro.camera.ManualControls).zoomRatio, 1e-6f)
        viewModel.onVideoFrameRate(me.hletrd.telecampro.camera.VideoFrameRate.FPS_29_97)
        assertEquals(3.75f, viewModel.state.value.controls.zoomRatio, 1e-6f)
        assertEquals(3.75f, (field(engine, "controls") as me.hletrd.telecampro.camera.ManualControls).zoomRatio, 1e-6f)
        viewModel.onCameraOverride("4")
        assertEquals(1.25f, viewModel.state.value.controls.zoomRatio, 1e-6f)
        assertEquals(1.25f, (field(engine, "controls") as me.hletrd.telecampro.camera.ManualControls).zoomRatio, 1e-6f)
        viewModel.onCameraOverride(null)
        assertEquals(3.75f, viewModel.state.value.controls.zoomRatio, 1e-6f)
        assertEquals(3.75f, (field(engine, "controls") as me.hletrd.telecampro.camera.ManualControls).zoomRatio, 1e-6f)
        viewModel.onTransfer(ColorTransfer.SLOG3)
        assertEquals(1.25f, viewModel.state.value.controls.zoomRatio, 1e-6f)
        assertEquals(1.25f, (field(engine, "controls") as me.hletrd.telecampro.camera.ManualControls).zoomRatio, 1e-6f)
    }

    @Test
    fun `maker gamma under TC keeps the TC-local zoom and re-resolves the TELE camera`() {
        val (viewModel, engine) = createAccepted(
            CaptureMode.VIDEO, ColorTransfer.SLOG3, ColorTransfer.SLOG3,
        )
        setField(engine, "deviceProfile", me.hletrd.telecampro.camera.DeviceProfile.resolve("PMA110"))
        setField(engine, "lensChoice", me.hletrd.telecampro.camera.LensChoice.TELE3X)
        setBoolean(engine, "teleconverterMode", true)
        val controls = me.hletrd.telecampro.camera.ManualControls(zoomRatio = 2f)
        setField(engine, "controls", controls)
        setField(viewModel, "pendingControls", controls)
        // The ordinary TC route's accepted camera: the standalone host tele.
        setField(engine, "overrideId", "4")
        state(viewModel).value = state(viewModel).value.copy(
            makerLogSupported = true,
            makerLogTeleSupported = true,
            teleconverterMode = true,
            lens = me.hletrd.telecampro.camera.LensChoice.TELE3X,
            controls = controls,
        )
        val teleRouteWanted = CameraEngine::class.java.getDeclaredMethod("makerLogTeleRouteWanted")
            .apply { isAccessible = true }
        assertFalse(teleRouteWanted.invoke(engine) as Boolean)

        viewModel.onTransfer(ColorTransfer.MAKER_LOG)
        // TC-local on both sides: no unified remap (that would read 6.0 here)...
        assertEquals(2f, viewModel.state.value.controls.zoomRatio, 0f)
        assertEquals(2f, (field(engine, "controls") as me.hletrd.telecampro.camera.ManualControls).zoomRatio, 0f)
        // ...but the cached standalone id is dropped so the reopen resolves the logical maker camera.
        assertEquals(null, field(engine, "overrideId"))
        assertTrue(teleRouteWanted.invoke(engine) as Boolean)
        assertTrue(viewModel.state.value.makerLogTeleRoute)
        assertFalse(viewModel.state.value.makerLogRoute)

        // A maker refusal latched on the logical camera hands TELE back to the ordinary route.
        setField(engine, "overrideId", "0")
        setBoolean(engine, "makerLogRefused", true)
        assertFalse(teleRouteWanted.invoke(engine) as Boolean)
        // Selecting Log (maker) again is the explicit retry: it clears the latch and the cached id.
        viewModel.onTransfer(ColorTransfer.MAKER_LOG)
        assertFalse(field(engine, "makerLogRefused") as Boolean)
        assertTrue(teleRouteWanted.invoke(engine) as Boolean)

        // Above 30 fps the TC route is the ordinary standalone one again, still at TC-local 2.0.
        setField(engine, "overrideId", "0")
        viewModel.onVideoFrameRate(me.hletrd.telecampro.camera.VideoFrameRate.FPS_60)
        assertEquals(2f, (field(engine, "controls") as me.hletrd.telecampro.camera.ManualControls).zoomRatio, 0f)
        assertEquals(null, field(engine, "overrideId"))
        assertFalse(teleRouteWanted.invoke(engine) as Boolean)

        viewModel.onVideoFrameRate(me.hletrd.telecampro.camera.VideoFrameRate.FPS_29_97)
        viewModel.onTransfer(ColorTransfer.SLOG3)
        assertEquals(2f, viewModel.state.value.controls.zoomRatio, 0f)
        assertEquals(2f, (field(engine, "controls") as me.hletrd.telecampro.camera.ManualControls).zoomRatio, 0f)
        assertFalse(teleRouteWanted.invoke(engine) as Boolean)
    }

    private fun createAccepted(
        mode: CaptureMode,
        acceptedTransfer: ColorTransfer = transferFor(mode),
        requestedTransfer: ColorTransfer = ColorTransfer.HLG,
        videoPipelineOverrides: VideoPipelineEngineOverrides? = null,
    ): Pair<CameraViewModel, CameraEngine> {
        RobolectricEglSentinels.ensure()
        val engine = CameraEngine(app, videoPipelineOverrides = videoPipelineOverrides)
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
        val avc = EncoderSelection(
            VideoCodec.AVC,
            "test-avc-main",
            MediaFormat.MIMETYPE_VIDEO_AVC,
            hardwareAccelerated = true,
            main10 = false,
        )
        engine.setVideoEncoders(listOf(main10))
        setField(viewModel, "encoderInventory", CodecInventory(mapOf(
            VideoCodec.HEVC to listOf(main10),
            VideoCodec.AVC to listOf(avc),
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
            availableVideoCodecs = listOf(VideoCodec.HEVC, VideoCodec.AVC),
            recordAudio = true,
        )
        viewModel.onStandbyAudioMeterVisibilityChanged(true)
        return viewModel to engine
    }

    private fun forceOwnedRollback(
        engine: CameraEngine,
        acceptedTransfer: ColorTransfer,
        requestedTransfer: ColorTransfer = acceptedTransfer,
        drainMain: Boolean = true,
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
            .newInstance(generation, baseline, true)
        CameraEngine::class.java.declaredMethods.single { it.name == "rollbackOptics" }
            .apply { isAccessible = true }
            .invoke(engine, transaction, CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED.status())
        assertEquals(acceptedTransfer, engineTransfer(engine))
        assertEquals(requestedTransfer, field(engine, "requestedVideoTransfer"))
        if (drainMain) shadowOf(Looper.getMainLooper()).idle()
    }

    private fun beginUnchangedOpticsTransaction(engine: CameraEngine): Any {
        val result = CameraEngine::class.java.declaredMethods.single {
            it.name == "beginOpticsTransaction"
        }.apply { isAccessible = true }.invoke(engine, { Unit }) as Pair<*, *>
        return checkNotNull(result.first)
    }

    private fun rollbackCaps() = CameraCaps(
        logicalId = "test",
        physicalId = null,
        sensorOrientation = 90,
        minFocusDistanceDiopters = 0f,
        hyperfocalDiopters = 0f,
        isoRange = Range(100, 100),
        exposureTimeRange = Range(1_000L, 1_000L),
        maxFrameDurationNs = 1_000L,
        evRange = Range(0, 0),
        evStep = Rational(1, 3),
        focalLengthsMm = floatArrayOf(4f),
        equivalentFocalMm = 23f,
        lensFocalLengthMm = 4f,
        lensApertureF = 2f,
        nativeFocalInImageWidths = 1f,
        supportsManualSensor = false,
        supportsManualPostProcessing = false,
        supportsRaw = false,
        lensFacingFront = false,
        rawSize = null,
        supportedDynamicRangeProfiles = emptySet(),
        largestJpegSize = Size(4000, 3000),
        hiResJpegSize = null,
        hiResUsesMaxResolutionMode = false,
        largestYuvSize = Size(4000, 3000),
        timestampSource = 0,
        isLogicalMultiCamera = false,
        oisAvailable = false,
        flashAvailable = false,
        zoomRatioRange = Range(1f, 10f),
        videoStabModes = intArrayOf(0),
        afModes = intArrayOf(0),
        awbModes = intArrayOf(1),
        aeModes = intArrayOf(1),
        maxAeRegions = 0,
        maxAfRegions = 0,
        antibandingModes = intArrayOf(0),
        effectModes = intArrayOf(0),
        edgeModes = intArrayOf(0),
        noiseReductionModes = intArrayOf(0),
        availableFpsRanges = arrayOf(Range(30, 30)),
        availableVideoSizes = listOf(Size(1920, 1080)),
        openGateVideoSizes = listOf(Size(1440, 1080)),
        highSpeedConfigs = emptyMap(),
    )

    private fun forceOwnedRollback(
        engine: CameraEngine,
        transaction: Any,
        drainMain: Boolean,
    ) {
        CameraEngine::class.java.declaredMethods.single { it.name == "rollbackOptics" }
            .apply { isAccessible = true }
            .invoke(engine, transaction, CameraStatusMessage.CAMERA_UNAVAILABLE_RECALL_UNCHANGED.status())
        if (drainMain) shadowOf(Looper.getMainLooper()).idle()
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

        viewModel.onCameraInputBlockOwnerChange(CameraInputBlockOwner.CAMERA_PERMISSION, true)
        assertTrue(viewModel.state.value.cameraInputBlocked)
        assertFalse(standbyWanted(engine))

        viewModel.onCameraInputBlockOwnerChange(CameraInputBlockOwner.CAMERA_PERMISSION, false)
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

    // AGG2-9: a DNG door that rolls back inside the encoder-inventory window must also roll back
    // the pending REQUEST, or the inventory replays DNG with no remap packet and saves persist it.
    @Test
    fun `rollback mirrors restored DNG into the pre-inventory pending formats`() {
        val (viewModel, engine) = createAccepted(CaptureMode.PHOTO)
        state(viewModel).value = state(viewModel).value.copy(encoderInventoryLoaded = false)
        val transaction = beginUnchangedOpticsTransaction(engine)
        setField(
            viewModel,
            "pendingPhotoFormatsUntilInventory",
            me.hletrd.telecampro.camera.PhotoFormats(heif = true, jpeg = false, dngRaw = true),
        )

        forceOwnedRollback(engine, transaction, drainMain = true)

        assertEquals(
            me.hletrd.telecampro.camera.PhotoFormats(heif = true, jpeg = false, dngRaw = false),
            field(viewModel, "pendingPhotoFormatsUntilInventory"),
        )
        assertFalse(viewModel.state.value.photoFormats.dngRaw)
    }

    private fun transferFor(mode: CaptureMode) =
        if (mode == CaptureMode.VIDEO) ColorTransfer.HLG else ColorTransfer.SDR

    @Suppress("UNCHECKED_CAST")
    private fun state(viewModel: CameraViewModel) =
        field(viewModel, "_state") as MutableStateFlow<CameraUiState>

    private fun engineTransfer(engine: CameraEngine) = field(engine, "transfer") as ColorTransfer
    private fun standbyWanted(engine: CameraEngine) = field(engine, "standbyAudioMonitorWanted") as Boolean
    private fun engineReady(engine: CameraEngine) = field(engine, "cameraReady") as Boolean

    private fun currentAcceptedRecordingSession(engine: CameraEngine): Any? =
        CameraEngine::class.java.getDeclaredMethod("currentAcceptedRecordingSession")
            .apply { isAccessible = true }
            .invoke(engine)

    private fun currentRecordingAdmissionSnapshot(engine: CameraEngine): Any? =
        CameraEngine::class.java.getDeclaredMethod("currentRecordingAdmissionSnapshot")
            .apply { isAccessible = true }
            .invoke(engine)

    private fun acceptCurrentSession(engine: CameraEngine, controller: CameraController) {
        val opticsGeneration = (field(engine, "opticsIntentGeneration") as AtomicLong).get()
        val sessionGeneration = (field(engine, "cameraSessionGeneration") as AtomicLong).get()
        CameraEngine::class.java.declaredMethods.single {
            it.name == "commitOpticsReady" && it.parameterTypes.size == 6
        }.apply { isAccessible = true }.invoke(
            engine,
            opticsGeneration,
            controller,
            PhotoSessionOutputs(),
            sessionGeneration,
            {},
            null,
        )
    }

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
