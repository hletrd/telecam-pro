package me.hletrd.telecampro.camera

import android.app.Application
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.ui.RobolectricEglSentinels
import me.hletrd.telecampro.ui.ViewModelTestAccess
import me.hletrd.telecampro.video.EncoderSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    fun `the fallback is the largest offered smaller raster`() {
        assertEquals(
            Size(5120, 2880),
            fallbackSize(eightK, listOf(eightK, Size(5120, 2880), uhd, fullHd)),
        )
        assertEquals(
            Size(2560, 1440),
            fallbackSize(uhd, listOf(eightK, uhd, Size(2560, 1440), fullHd)),
        )
        assertNull(fallbackSize(fullHd, listOf(eightK, uhd, fullHd)))
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
    fun `an 8K maker Log refusal chooses 4K maker Log without overwriting the request`() {
        val fixture = extendedFixture()
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
            sizeRateSupport = null,
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
        val openGateSize = Size(4096, 3072)

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
        val openGateSize = Size(4096, 3072)
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
    fun `fatal recovery cannot choose the refused 8K graph again`() {
        val fixture = extendedFixture()
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

    private fun extendedFixture(
        intermediateSize: Size? = null,
        outgoingSizeAtWire: Size? = null,
    ): Fixture {
        val engine = CameraEngine(app)
        val projected = ViewModelTestAccess.caps().copy(
            availableVideoSizes = listOfNotNull(eightK, intermediateSize, uhd, fullHd),
            openGateVideoSizes = listOf(Size(4096, 3072)),
        )
        setField(engine, "selection", route)
        setField(engine, "caps", projected)
        @Suppress("UNCHECKED_CAST")
        val capsCache = field(engine, "capsCache") as MutableMap<String, CameraCaps>
        capsCache["0:null"] = projected
        setField(engine, "videoMode", true)
        setField(engine, "transfer", ColorTransfer.MAKER_LOG)
        setField(engine, "requestedVideoTransfer", ColorTransfer.MAKER_LOG)
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

    private fun chooseVideoSize(engine: CameraEngine): Size =
        CameraEngine::class.java.declaredMethods.single { it.name == "chooseVideoSize" }
            .apply { isAccessible = true }
            .invoke(engine, route) as Size

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
