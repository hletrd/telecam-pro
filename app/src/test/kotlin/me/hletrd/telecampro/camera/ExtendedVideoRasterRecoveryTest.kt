package me.hletrd.telecampro.camera

import android.app.Application
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaFormat
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import me.hletrd.telecampro.ui.ViewModelTestAccess
import me.hletrd.telecampro.video.EncoderFormatSupport
import me.hletrd.telecampro.video.EncoderSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.StreamConfigurationMapBuilder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** 8K refusal recovery changes only the delivered raster, never Gamma or the persisted request. */
@RunWith(RobolectricTestRunner::class)
class ExtendedVideoRasterRecoveryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val eightK = Size(7680, 4320)
    private val uhd = Size(3840, 2160)
    private val fullHd = Size(1920, 1080)
    private val route = TeleSelection("0", null, 23f)
    private val routeKey = "0//bare"

    @Before
    fun installEglSentinels() {
        RobolectricEglSentinels.ensure()
    }

    @Test
    fun `a refused explicit request returns to the capped automatic raster`() {
        val fixture = extendedFixture(intermediateSize = Size(5120, 2880))
        val latch = field(fixture.engine, "extendedVideoRasterRefusals") as ExtendedVideoRasterRefusalLatch
        latch.refuse(ExtendedVideoRasterRefusal(routeKey, eightK, ColorTransfer.MAKER_LOG))

        assertEquals(uhd, chooseVideoSize(fixture.engine))
    }

    @Test
    fun `Ready is delivered once by the first real frame of its own session`() {
        val gate = RealFrameReadyGate<String>()
        var deliveries = 0
        gate.arm("candidate") { deliveries++ }

        assertEquals(0, deliveries)
        gate.onRealFrame("outgoing")
        assertEquals(0, deliveries)
        gate.onRealFrame("candidate")
        gate.onRealFrame("candidate")
        assertEquals(1, deliveries)
    }

    @Test
    fun `REC admission refuses a raster different from the accepted session raster`() {
        assertFalse(acceptedRecordingRasterMatches(true, eightK, uhd))
        assertTrue(acceptedRecordingRasterMatches(true, eightK, eightK))
        assertTrue(acceptedRecordingRasterMatches(false, null, uhd))
    }

    @Test
    fun `an explicit retry clears only its exact route size and transfer refusal`() {
        val refusal = ExtendedVideoRasterRefusal(routeKey, eightK, ColorTransfer.MAKER_LOG)
        val latch = ExtendedVideoRasterRefusalLatch()
        latch.refuse(refusal)

        assertFalse(shouldRetryRefusedRaster(refusal, routeKey, eightK, ColorTransfer.MAKER_LOG))
        assertTrue(
            shouldRetryRefusedRaster(
                refusal,
                routeKey,
                eightK,
                ColorTransfer.MAKER_LOG,
                explicitRetry = true,
            ),
        )
        assertTrue(shouldRetryRefusedRaster(refusal, routeKey, eightK, ColorTransfer.SLOG3))
        assertTrue(shouldRetryRefusedRaster(refusal, "4//tc", eightK, ColorTransfer.MAKER_LOG))
        assertTrue(shouldRetryRefusedRaster(refusal, routeKey, uhd, ColorTransfer.MAKER_LOG))

        latch.clearForExplicitRetry(routeKey, eightK, ColorTransfer.SLOG3)
        assertTrue(latch.blocks(routeKey, eightK, ColorTransfer.MAKER_LOG))
        latch.clearForExplicitRetry("4//tc", eightK, ColorTransfer.MAKER_LOG)
        assertTrue(latch.blocks(routeKey, eightK, ColorTransfer.MAKER_LOG))
        latch.clearForExplicitRetry(routeKey, uhd, ColorTransfer.MAKER_LOG)
        assertTrue(latch.blocks(routeKey, eightK, ColorTransfer.MAKER_LOG))
        latch.clearForExplicitRetry(routeKey, eightK, ColorTransfer.MAKER_LOG)
        assertFalse(latch.blocks(routeKey, eightK, ColorTransfer.MAKER_LOG))
    }

    @Test
    fun `every refused route size and transfer stays latched until its own explicit retry`() {
        val latch = ExtendedVideoRasterRefusalLatch()
        val fiveK = Size(5120, 2880)
        val otherRoute = "4//tc"
        latch.refuse(ExtendedVideoRasterRefusal(routeKey, eightK, ColorTransfer.MAKER_LOG))
        latch.refuse(ExtendedVideoRasterRefusal(routeKey, fiveK, ColorTransfer.MAKER_LOG))
        latch.refuse(ExtendedVideoRasterRefusal(otherRoute, eightK, ColorTransfer.SLOG3))

        assertTrue(latch.blocks(routeKey, eightK, ColorTransfer.MAKER_LOG))
        assertTrue(latch.blocks(routeKey, fiveK, ColorTransfer.MAKER_LOG))
        assertTrue(latch.blocks(otherRoute, eightK, ColorTransfer.SLOG3))

        latch.clearForExplicitRetry(routeKey, fiveK, ColorTransfer.MAKER_LOG)
        assertFalse(latch.blocks(routeKey, fiveK, ColorTransfer.MAKER_LOG))
        assertTrue(latch.blocks(routeKey, eightK, ColorTransfer.MAKER_LOG))
        assertTrue(latch.blocks(otherRoute, eightK, ColorTransfer.SLOG3))
    }

    @Test
    fun `fallback and the next reopen skip every already refused raster`() {
        val fiveK = Size(5120, 2880)
        val fixture = extendedFixture(intermediateSize = fiveK)
        val latch = field(fixture.engine, "extendedVideoRasterRefusals") as ExtendedVideoRasterRefusalLatch
        latch.refuse(ExtendedVideoRasterRefusal(routeKey, fiveK, ColorTransfer.MAKER_LOG))
        var published: Size? = null
        fixture.engine.onVideoSizeChosen = { size, _ -> published = size }

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals(uhd, published)
        assertEquals(uhd, field(fixture.engine, "videoSize"))
        assertEquals(uhd, chooseVideoSize(fixture.engine))
    }

    @Test
    fun `an 8K maker Log refusal makes one capped attempt without overwriting the request`() {
        val fixture = extendedFixture(intermediateSize = Size(5120, 2880))
        var published: Pair<Size, Long?>? = null
        fixture.engine.onVideoSizeChosen = { size, generation -> published = size to generation }

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals(uhd to 7L, published)
        assertEquals(uhd, field(fixture.engine, "videoSize"))
        assertEquals(eightK, fixture.engine.currentRequestedVideoSize())
        assertEquals(ColorTransfer.MAKER_LOG, field(fixture.engine, "transfer"))
        assertEquals(uhd, chooseVideoSize(fixture.engine))
    }

    @Test
    fun `an explicit resolution retry reopens only the cleared extended request`() {
        val fixture = extendedFixture()
        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)
        assertEquals(uhd, field(fixture.engine, "videoSize"))

        assertTrue(fixture.engine.setVideoResolution(eightK))

        assertEquals(eightK, fixture.engine.currentRequestedVideoSize())
        assertEquals(eightK, field(fixture.engine, "videoSize"))
        assertEquals(eightK, chooseVideoSize(fixture.engine))
    }

    @Test
    fun `an older callback cannot replace a newer direct size choice`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "requestedVideoSize", fullHd)
        setField(fixture.engine, "requestedVideoSizeDirectWrites", 2L)
        setField(fixture.engine, "videoSize", fullHd)
        var published = false
        fixture.engine.onVideoSizeChosen = { _, _ -> published = true }

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals(fullHd, field(fixture.engine, "videoSize"))
        assertFalse(published)
    }

    @Test
    fun `an older callback cannot replace a newer transfer`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "transfer", ColorTransfer.SLOG3)
        setField(fixture.engine, "requestedVideoTransfer", ColorTransfer.SLOG3)
        field(fixture.engine, "videoPipelinePublicationGeneration").let {
            (it as AtomicLong).incrementAndGet()
        }

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals(eightK, field(fixture.engine, "videoSize"))
        assertEquals(ColorTransfer.SLOG3, field(fixture.engine, "transfer"))
    }

    @Test
    fun `an older optics generation callback is inert`() {
        val fixture = extendedFixture()
        (field(fixture.engine, "opticsIntentGeneration") as AtomicLong).incrementAndGet()

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals(eightK, field(fixture.engine, "videoSize"))
        assertEquals(ColorTransfer.MAKER_LOG, field(fixture.engine, "transfer"))
    }

    @Test
    fun `fallback effects cannot publish after a newer direct size interleaves`() {
        val fixture = extendedFixture()

        val published = interleaveFallbackEffects(fixture) {
            fixture.engine.setVideoResolution(fullHd)
        }

        assertEquals(fullHd, published.last())
        assertEquals(fullHd, field(fixture.engine, "videoSize"))
        assertEquals(fullHd, fixture.engine.currentRequestedVideoSize())
    }

    @Test
    fun `fallback effects cannot publish after a newer pipeline reprojection interleaves`() {
        val fiveK = Size(5120, 2880)
        val fixture = extendedFixture(intermediateSize = fiveK)
        val legacyOnlyHevc = EncoderSelection(
            codec = VideoCodec.HEVC,
            codecName = "hevc",
            mime = "video/hevc",
            hardwareAccelerated = true,
            main10 = true,
            formatSupport = null,
        )

        val published = interleaveFallbackEffects(fixture) {
            fixture.engine.setVideoPipeline(listOf(legacyOnlyHevc), ColorTransfer.MAKER_LOG)
        }

        assertEquals(uhd, published.last())
        assertEquals(uhd, field(fixture.engine, "videoSize"))
        assertEquals(ColorTransfer.MAKER_LOG, field(fixture.engine, "transfer"))
        assertEquals(eightK, fixture.engine.currentRequestedVideoSize())
    }

    @Test
    fun `fallback effects cannot publish after Open Gate interleaves`() {
        val fixture = extendedFixture()
        val openGateSize = Size(2560, 1920)

        val published = interleaveFallbackEffects(fixture) {
            fixture.engine.setOpenGate(true)
        }

        assertEquals(openGateSize, published.last())
        assertEquals(openGateSize, field(fixture.engine, "videoSize"))
        assertEquals(openGateSize, field(fixture.engine, "previewStreamSize"))
    }

    @Test
    fun `an old 16 by 9 callback is inert after Open Gate has already changed intent`() {
        val fixture = extendedFixture()
        val openGateSize = Size(2560, 1920)
        fixture.engine.setOpenGate(true)
        var published = false
        fixture.engine.onVideoSizeChosen = { _, _ -> published = true }

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertFalse(published)
        assertEquals(openGateSize, field(fixture.engine, "videoSize"))
        assertEquals(openGateSize, field(fixture.engine, "previewStreamSize"))
    }

    @Test
    fun `dual-open ownership uses the accepted size resolved after controller wiring`() {
        val fixture = extendedFixture(outgoingSizeAtWire = fullHd)
        var published: Size? = null
        fixture.engine.onVideoSizeChosen = { size, _ -> published = size }

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals(uhd, published)
        assertEquals(uhd, field(fixture.engine, "videoSize"))
    }

    @Test
    fun `automatic video selection has no answer when only extended sizes are offered`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "requestedVideoSize", null)
        setField(fixture.engine, "caps", ViewModelTestAccess.caps().copy(availableVideoSizes = listOf(eightK)))

        assertNull(chooseVideoSize(fixture.engine))
    }

    @Test
    fun `automatic photo selection has no answer when only an extended 4 by 3 size is offered`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "videoMode", false)
        setField(fixture.engine, "caps", ViewModelTestAccess.caps().copy(openGateVideoSizes = listOf(Size(7680, 5760))))

        val selected = CameraEngine::class.java.declaredMethods.single { it.name == "choosePreviewStreamSize" }
            .apply { isAccessible = true }
            .invoke(fixture.engine, route)
        assertNull(selected)
    }

    @Test
    fun `a refusal with no capped answer enters owned bounded recovery`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "started", true)
        setField(fixture.engine, "caps", ViewModelTestAccess.caps().copy(availableVideoSizes = listOf(eightK)))
        var status: CameraStatus? = null
        fixture.engine.onStatus = { status = it }

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals(CameraStatusMessage.SELECTED_RESOLUTION_UNAVAILABLE, status?.message)
        assertEquals(1, field(fixture.engine, "cameraRecoveryAttempts"))
        assertTrue(field(fixture.engine, "recoveryScheduledFor") === fixture.controller)
        setField(fixture.engine, "controller", CameraController(app))
    }

    @Test
    fun `a fatal refusal with no capped answer still schedules owned recovery`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "started", true)
        setField(fixture.engine, "caps", null)

        handleFailure(
            fixture.engine,
            fixture.controller,
            ExtendedVideoRasterConfigureFatalException(
                routeKey,
                eightK,
                IllegalStateException("device fatal"),
            ),
        )

        assertEquals(1, field(fixture.engine, "cameraRecoveryAttempts"))
        assertTrue(field(fixture.engine, "recoveryScheduledFor") === fixture.controller)
        setField(fixture.engine, "controller", CameraController(app))
    }

    @Test
    fun `an explicit extended pick with no camera-supported rate is refused before storing the request`() {
        val fixture = extendedFixture()
        val rateLessCaps = ViewModelTestAccess.caps().copy(
            availableFpsRanges = arrayOf(android.util.Range(60, 60)),
            availableVideoSizes = listOf(eightK, uhd),
            extendedVideoSizeRates = mapOf(eightK to setOf(29.97, 30.0)),
        )
        setField(fixture.engine, "caps", rateLessCaps)
        setField(fixture.engine, "requestedVideoSize", null)
        setField(fixture.engine, "videoSize", uhd)

        assertFalse(fixture.engine.setVideoResolution(eightK))

        assertNull(fixture.engine.currentRequestedVideoSize())
        assertEquals(uhd, field(fixture.engine, "videoSize"))
    }

    @Test
    fun `photo codec reprojection without a video size keeps the accepted photo session ready`() {
        val fixture = extendedFixture()
        val raw = ViewModelTestAccess.caps().copy(
            availableVideoSizes = listOf(eightK),
            openGateVideoSizes = listOf(Size(2560, 1920)),
            extendedVideoSizeRates = mapOf(eightK to setOf(29.97, 30.0)),
        )
        setField(fixture.engine, "videoMode", false)
        setField(fixture.engine, "transfer", ColorTransfer.SDR)
        setField(fixture.engine, "requestedVideoTransfer", ColorTransfer.SDR)
        setField(fixture.engine, "caps", raw)
        setField(fixture.engine, "previewStreamSize", Size(2560, 1920))
        setField(fixture.engine, "cameraReady", true)
        @Suppress("UNCHECKED_CAST")
        val capsCache = field(fixture.engine, "capsCache") as MutableMap<String, CameraCaps>
        capsCache["0:null"] = raw
        val legacyOnly = EncoderSelection(
            codec = VideoCodec.HEVC,
            codecName = "legacy-hevc",
            mime = "video/hevc",
            hardwareAccelerated = true,
            main10 = true,
            formatSupport = null,
        )

        fixture.engine.setVideoPipeline(listOf(legacyOnly), ColorTransfer.SDR)

        assertEquals(true, field(fixture.engine, "cameraReady"))
        assertEquals(Size(2560, 1920), field(fixture.engine, "previewStreamSize"))
    }

    @Test
    fun `an empty capped 4 by 3 list falls back to direct camera characteristics`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "videoMode", false)
        setField(fixture.engine, "caps", ViewModelTestAccess.caps().copy(openGateVideoSizes = emptyList()))
        val manager = app.getSystemService(CameraManager::class.java)
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val map = StreamConfigurationMapBuilder.newBuilder()
            .addOutputSize(fullHd)
            .build()
        shadowOf(characteristics).set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, map)
        shadowOf(manager).addCamera("0", characteristics)

        val selected = CameraEngine::class.java.declaredMethods.single { it.name == "choosePreviewStreamSize" }
            .apply { isAccessible = true }
            .invoke(fixture.engine, route)

        assertEquals(fullHd, selected)
    }

    @Test
    fun `fatal recovery makes one capped attempt instead of re-entering 8K or choosing 5K`() {
        val fixture = extendedFixture(intermediateSize = Size(5120, 2880))
        val cause = IllegalStateException("device fatal")
        // The controller's terminal wrapper marks a maker attempt failed; an extended-raster fatal
        // must not be reclassified as a maker-pipeline refusal during bounded recovery.
        setField(fixture.controller, "makerLogFailed", true)

        handleFailure(
            fixture.engine,
            fixture.controller,
            ExtendedVideoRasterConfigureFatalException(routeKey, eightK, cause),
        )

        assertEquals(eightK, fixture.engine.currentRequestedVideoSize())
        assertEquals(ColorTransfer.MAKER_LOG, field(fixture.engine, "transfer"))
        assertEquals(false, field(fixture.engine, "makerLogRefused"))
        assertEquals(uhd, chooseVideoSize(fixture.engine))
    }

    @Test
    fun `a same-size tap during an in-flight open preserves refusal ownership`() {
        val fixture = extendedFixture(intermediateSize = Size(5120, 2880))
        val directWrites = field(fixture.engine, "requestedVideoSizeDirectWrites") as Long

        assertTrue(fixture.engine.setVideoResolution(eightK))
        assertEquals(directWrites, field(fixture.engine, "requestedVideoSizeDirectWrites"))

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals("the in-flight attempt still owns its capped recovery", uhd, field(fixture.engine, "videoSize"))
    }

    @Test
    fun `a post-configure streaming fatal latches the exact extended graph`() {
        val fixture = extendedFixture(intermediateSize = Size(5120, 2880))
        val latch = field(fixture.engine, "extendedVideoRasterRefusals") as ExtendedVideoRasterRefusalLatch

        handleFailure(fixture.engine, fixture.controller, IllegalStateException("Camera error 3"))

        assertTrue(latch.blocks(routeKey, eightK, ColorTransfer.MAKER_LOG))
        assertEquals(uhd, chooseVideoSize(fixture.engine))
    }

    @Test
    fun `recovery reopens the previous accepted graph rather than descending`() {
        val fiveK = Size(5120, 2880)
        val fixture = extendedFixture(intermediateSize = fiveK)
        setField(fixture.engine, "requestedVideoSize", uhd)
        setField(fixture.engine, "videoSize", uhd)
        setField(fixture.engine, "previewStreamSize", uhd)
        setField(fixture.engine, "previewReady", true)
        fixture.controller.stageVideoSessionIntentForOpen(videoIntent(uhd))
        commitReady(fixture)

        setField(fixture.engine, "requestedVideoSize", eightK)
        setField(fixture.engine, "requestedVideoSizeDirectWrites", 2L)
        setField(fixture.engine, "videoSize", eightK)
        setField(fixture.engine, "previewStreamSize", eightK)
        setField(fixture.engine, "cameraReady", false)
        registerOwnership(fixture)

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals(uhd, field(fixture.engine, "videoSize"))
        assertEquals(uhd, field(fixture.engine, "previewStreamSize"))
    }

    @Test
    fun `sequential 8K then 5K refusals recover accepted UHD and never auto re-enter 8K`() {
        val fiveK = Size(5120, 2880)
        val fixture = extendedFixture(intermediateSize = fiveK)
        setField(fixture.engine, "requestedVideoSize", uhd)
        setField(fixture.engine, "videoSize", uhd)
        setField(fixture.engine, "previewStreamSize", uhd)
        setField(fixture.engine, "previewReady", true)
        fixture.controller.stageVideoSessionIntentForOpen(videoIntent(uhd))
        commitReady(fixture)

        setField(fixture.engine, "requestedVideoSize", eightK)
        setField(fixture.engine, "requestedVideoSizeDirectWrites", 2L)
        setField(fixture.engine, "videoSize", eightK)
        setField(fixture.engine, "previewStreamSize", eightK)
        setField(fixture.engine, "cameraReady", false)
        registerOwnership(fixture)
        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)
        assertEquals(uhd, field(fixture.engine, "videoSize"))

        fixture.controller.stageVideoSessionIntentForOpen(videoIntent(fiveK))
        setField(fixture.engine, "requestedVideoSize", fiveK)
        setField(fixture.engine, "requestedVideoSizeDirectWrites", 3L)
        setField(fixture.engine, "videoSize", fiveK)
        setField(fixture.engine, "previewStreamSize", fiveK)
        registerOwnership(fixture)
        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, fiveK, 7L)

        assertEquals(uhd, field(fixture.engine, "videoSize"))
        val latch = field(fixture.engine, "extendedVideoRasterRefusals") as ExtendedVideoRasterRefusalLatch
        assertTrue(latch.blocks(routeKey, eightK, ColorTransfer.MAKER_LOG))
        assertTrue(latch.blocks(routeKey, fiveK, ColorTransfer.MAKER_LOG))
        setField(fixture.engine, "requestedVideoSize", eightK)
        assertEquals(uhd, chooseVideoSize(fixture.engine))
    }

    @Test
    fun `accepted codec recovery keeps the same-shape update and the explicit failed request`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "transfer", ColorTransfer.SDR)
        setField(fixture.engine, "requestedVideoTransfer", ColorTransfer.SDR)
        setField(fixture.engine, "requestedVideoSize", uhd)
        setField(fixture.engine, "videoSize", uhd)
        setField(fixture.engine, "previewStreamSize", uhd)
        setField(fixture.engine, "previewReady", true)
        fixture.controller.stageVideoSessionIntentForOpen(videoIntent(uhd, transfer = ColorTransfer.SDR))
        commitReady(fixture)
        val avc = EncoderSelection(
            codec = VideoCodec.AVC,
            codecName = "accepted-avc",
            mime = MediaFormat.MIMETYPE_VIDEO_AVC,
            hardwareAccelerated = true,
            main10 = false,
            formatSupport = EncoderFormatSupport { true },
        )

        fixture.engine.setVideoPipeline(listOf(avc), ColorTransfer.SDR)
        assertEquals("precondition: the accepted controller carries AVC", VideoCodec.AVC, fixture.controller.videoSessionIntent?.codec)
        failExtendedAttempt(fixture)

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals(VideoCodec.AVC, field(fixture.engine, "videoCodec"))
        assertEquals(listOf(avc), field(fixture.engine, "videoEncoderCandidates"))
        assertEquals(uhd, field(fixture.engine, "videoSize"))
        assertEquals("recovery must not relabel the operator's explicit retry", eightK, fixture.engine.currentRequestedVideoSize())
    }

    @Test
    fun `accepted bitrate recovery keeps the same-shape update`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "transfer", ColorTransfer.SDR)
        setField(fixture.engine, "requestedVideoTransfer", ColorTransfer.SDR)
        setField(fixture.engine, "bitrateLevel", BitrateLevel.LOW)
        setField(fixture.engine, "requestedVideoSize", uhd)
        setField(fixture.engine, "videoSize", uhd)
        setField(fixture.engine, "previewStreamSize", uhd)
        setField(fixture.engine, "previewReady", true)
        fixture.controller.stageVideoSessionIntentForOpen(videoIntent(uhd, transfer = ColorTransfer.SDR))
        commitReady(fixture)

        fixture.engine.setBitrateLevel(BitrateLevel.HIGH)
        failExtendedAttempt(fixture)

        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)

        assertEquals(BitrateLevel.HIGH, field(fixture.engine, "bitrateLevel"))
        assertEquals(uhd, field(fixture.engine, "videoSize"))
        assertEquals(eightK, fixture.engine.currentRequestedVideoSize())
    }

    @Test
    fun `identical retry after no capped answer starts one attempt but an in-flight tap stays inert`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "caps", ViewModelTestAccess.caps().copy(availableVideoSizes = listOf(eightK)))
        var publications = 0
        fixture.engine.onVideoSizeChosen = { _, _ -> publications++ }

        assertTrue(fixture.engine.setVideoResolution(eightK))
        assertEquals("a same-size tap during the original open owns nothing new", 0, publications)
        fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)
        assertEquals(0, publications)

        assertTrue(fixture.engine.setVideoResolution(eightK))

        assertEquals("the explicit retry must publish one new resolved attempt", 1, publications)
        assertEquals(eightK, fixture.engine.currentRequestedVideoSize())
        val latch = field(fixture.engine, "extendedVideoRasterRefusals") as ExtendedVideoRasterRefusalLatch
        assertFalse(latch.blocks(routeKey, eightK, ColorTransfer.MAKER_LOG))
    }

    @Test
    fun `identical retry supersedes queued no capped recovery as one extended open`() {
        val fixture = extendedFixture()
        setField(fixture.engine, "started", true)
        setField(fixture.engine, "overrideId", "0")
        setField(fixture.engine, "caps", ViewModelTestAccess.caps().copy(availableVideoSizes = listOf(eightK)))
        val manager = app.getSystemService(CameraManager::class.java)
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(characteristics).set(
            CameraCharacteristics.LENS_FACING,
            CameraCharacteristics.LENS_FACING_BACK,
        )
        shadowOf(manager).addCamera("0", characteristics)
        val texture = android.graphics.SurfaceTexture(0)
        val surface = android.view.Surface(texture)
        val owners = checkNotNull(field(fixture.engine, "glOwners"))
        val gl = checkNotNull(owners.javaClass.getDeclaredMethod("current").invoke(owners))
        setField(gl, "inputSurface", surface)
        val executor = field(fixture.engine, "setupExecutor") as java.util.concurrent.ExecutorService
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))
        val controllerSequence = CameraController::class.java.getDeclaredField("controllerSequence")
            .apply { isAccessible = true }
            .get(null) as AtomicLong
        val controllersBefore = controllerSequence.get()
        try {
            fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)
            val recoveryQueued = CountDownLatch(1)
            val recoveryThread = java.util.concurrent.atomic.AtomicReference<Thread>()
            fixture.engine.onCameraReadyChange = {
                if (!it.ready) {
                    recoveryThread.set(Thread.currentThread())
                    recoveryQueued.countDown()
                }
            }
            assertTrue("the delayed no-capped recovery must be queued behind the setup blocker", recoveryQueued.await(3, TimeUnit.SECONDS))
            val scheduler = recoveryThread.get()
            val recoveryQueueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (scheduler.state == Thread.State.RUNNABLE && System.nanoTime() < recoveryQueueDeadline) Thread.yield()
            assertTrue(
                "the scheduler must finish queueing recovery before the explicit retry",
                scheduler.state == Thread.State.WAITING || scheduler.state == Thread.State.TIMED_WAITING,
            )

            assertTrue(fixture.engine.setVideoResolution(eightK))
            assertTrue("a repeated tap during the retry open is also accepted as inert", fixture.engine.setVideoResolution(eightK))
            releaseBlocker.countDown()
            val drained = CountDownLatch(1)
            executor.execute { drained.countDown() }
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            val reconfiguresDrained = CountDownLatch(1)
            executor.execute { reconfiguresDrained.countDown() }
            assertTrue(reconfiguresDrained.await(5, TimeUnit.SECONDS))

            assertEquals(
                "exactly one retry controller may be constructed",
                1L,
                controllerSequence.get() - controllersBefore,
            )
        } finally {
            releaseBlocker.countDown()
            setField(fixture.engine, "started", false)
            setField(fixture.engine, "controller", CameraController(app))
            surface.release()
            texture.release()
        }
    }

    private fun failExtendedAttempt(fixture: Fixture) {
        setField(fixture.engine, "requestedVideoSize", eightK)
        setField(fixture.engine, "requestedVideoSizeDirectWrites", 2L)
        setField(fixture.engine, "videoSize", eightK)
        setField(fixture.engine, "previewStreamSize", eightK)
        setField(fixture.engine, "cameraReady", false)
        registerOwnership(fixture)
    }

    private fun videoIntent(
        size: Size,
        codec: VideoCodec = VideoCodec.HEVC,
        transfer: ColorTransfer = ColorTransfer.MAKER_LOG,
    ) = VideoSessionIntent(
        route = route,
        codec = codec,
        transfer = transfer,
        rawLog = false,
        openGate = false,
        streamSize = size,
        encoderSize = Size(size.height, size.width),
        frameRate = VideoFrameRate.FPS_29_97,
    )

    private fun commitReady(fixture: Fixture) {
        val sessionGeneration = (field(fixture.engine, "cameraSessionGeneration") as AtomicLong).get()
        CameraEngine::class.java.declaredMethods.single {
            it.name == "commitOpticsReady" && it.parameterTypes.size == 6
        }.apply { isAccessible = true }.invoke(
            fixture.engine,
            7L,
            fixture.controller,
            PhotoSessionOutputs(),
            sessionGeneration,
            {},
            null,
        )
    }

    private fun registerOwnership(fixture: Fixture) {
        CameraEngine::class.java.declaredMethods.single {
            it.name == "registerExtendedVideoRasterOpenOwnership"
        }.apply { isAccessible = true }.invoke(fixture.engine, fixture.controller)
    }

    private fun extendedFixture(
        intermediateSize: Size? = null,
        outgoingSizeAtWire: Size? = null,
    ): Fixture {
        val engine = CameraEngine(app)
        val projected = ViewModelTestAccess.caps().copy(
            availableVideoSizes = listOfNotNull(eightK, intermediateSize, uhd, fullHd),
            openGateVideoSizes = listOf(Size(2560, 1920), Size(4096, 3072)),
            extendedVideoSizeRates = listOfNotNull(eightK, intermediateSize)
                .associateWith { setOf(23.976, 24.0, 29.97, 30.0) },
        )
        setField(engine, "selection", route)
        setField(engine, "caps", projected)
        @Suppress("UNCHECKED_CAST")
        val capsCache = field(engine, "capsCache") as MutableMap<String, CameraCaps>
        capsCache["0:null"] = projected
        setField(engine, "videoMode", true)
        setField(engine, "transfer", ColorTransfer.MAKER_LOG)
        setField(engine, "requestedVideoTransfer", ColorTransfer.MAKER_LOG)
        setField(
            engine,
            "videoEncoderCandidates",
            listOf(
                EncoderSelection(
                    codec = VideoCodec.HEVC,
                    codecName = "extended-fixture",
                    mime = MediaFormat.MIMETYPE_VIDEO_HEVC,
                    hardwareAccelerated = true,
                    main10 = true,
                    formatSupport = EncoderFormatSupport { format ->
                        format.getFloat(MediaFormat.KEY_FRAME_RATE) <= 30f
                    },
                ),
            ),
        )
        setField(engine, "requestedVideoSize", eightK)
        setField(engine, "requestedVideoSizeDirectWrites", 1L)
        setField(engine, "videoSize", outgoingSizeAtWire ?: eightK)
        setField(engine, "previewStreamSize", outgoingSizeAtWire ?: eightK)
        (field(engine, "opticsIntentGeneration") as AtomicLong).set(7L)
        (field(engine, "videoPipelinePublicationGeneration") as AtomicLong).set(11L)
        val controller = wireController(engine)
        setField(engine, "controller", controller)
        if (outgoingSizeAtWire != null) {
            // The dual-open candidate resolves its accepted stream AFTER wireController returns.
            setField(engine, "videoSize", eightK)
            setField(engine, "previewStreamSize", eightK)
        }
        CameraEngine::class.java.declaredMethods.single {
            it.name == "registerExtendedVideoRasterOpenOwnership"
        }.apply { isAccessible = true }.invoke(engine, controller)
        return Fixture(engine, controller)
    }

    /** Pauses the old callback inside its first external effect while a newer intent runs. */
    private fun interleaveFallbackEffects(
        fixture: Fixture,
        newerIntent: () -> Unit,
    ): List<Size> {
        val published = Collections.synchronizedList(mutableListOf<Size>())
        val callbackCount = AtomicInteger(0)
        val firstEffectStarted = CountDownLatch(1)
        val newerEffectPublished = CountDownLatch(1)
        val releaseFirstEffect = CountDownLatch(1)
        fixture.engine.onVideoSizeChosen = { size, _ ->
            if (callbackCount.incrementAndGet() == 1) {
                firstEffectStarted.countDown()
                assertTrue(releaseFirstEffect.await(5, TimeUnit.SECONDS))
            } else {
                newerEffectPublished.countDown()
            }
            published += size
        }
        val oldCallback = thread {
            fixture.controller.onExtendedVideoRasterRefused?.invoke(routeKey, eightK, 7L)
        }
        assertTrue(firstEffectStarted.await(5, TimeUnit.SECONDS))
        val newer = thread { newerIntent() }
        // The racy implementation publishes the newer effect while the old one is parked. The fixed
        // implementation serializes it behind releaseFirstEffect instead; both orders reach the joins.
        newerEffectPublished.await(2, TimeUnit.SECONDS)
        releaseFirstEffect.countDown()
        oldCallback.join(5_000)
        newer.join(5_000)
        assertFalse(oldCallback.isAlive)
        assertFalse(newer.isAlive)
        return published
    }

    private fun chooseVideoSize(engine: CameraEngine): Size? =
        CameraEngine::class.java.declaredMethods.single { it.name == "chooseVideoSize" }
            .apply { isAccessible = true }
            .invoke(engine, route) as Size?

    private fun wireController(engine: CameraEngine): CameraController {
        val owners = checkNotNull(field(engine, "glOwners"))
        val gl = owners.javaClass.getDeclaredMethod("current").invoke(owners)
        return CameraEngine::class.java.declaredMethods.single { it.name == "wireController" }
            .apply { isAccessible = true }
            .invoke(engine, gl) as CameraController
    }

    private fun handleFailure(engine: CameraEngine, failed: CameraController, failure: Throwable) {
        CameraEngine::class.java.declaredMethods.single { it.name == "handleActiveCameraFailure" }
            .apply { isAccessible = true }
            .invoke(engine, failed, failure)
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private data class Fixture(val engine: CameraEngine, val controller: CameraController)
}
