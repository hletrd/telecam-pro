package me.hletrd.telecampro.ui

import android.app.Application
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import me.hletrd.telecampro.camera.CameraEngine
import me.hletrd.telecampro.camera.ColorTransfer
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.VideoCodec
import me.hletrd.telecampro.storage.ExtraSettings
import me.hletrd.telecampro.video.CodecComponent
import me.hletrd.telecampro.video.CodecInventory
import me.hletrd.telecampro.video.buildCodecInventory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * AGG6-1: a codec walk that FAILED (mediaserver restarting during the cold-start scan) is not the
 * device's answer. It must leave the operator's pending HEIF/HEVC/HLG request armed and unpersisted,
 * and the next start must walk again and apply the real inventory to that request.
 */
@RunWith(RobolectricTestRunner::class)
class EncoderInventoryRetryRobolectricTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var viewModel: CameraViewModel? = null

    @After fun tearDown() {
        viewModel?.let(ViewModelTestAccess::clear)
    }

    /** Runs the VM's io lane dry, then delivers what it posted to main. */
    private fun drain(vm: CameraViewModel) {
        val io = ViewModelTestAccess.field(vm, "ioExecutor") as ExecutorService
        io.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val main10Inventory: CodecInventory = buildCodecInventory(
        listOf(
            CodecComponent(
                name = "vendor.hevc",
                encoder = true,
                supportedTypes = setOf(MediaFormat.MIMETYPE_VIDEO_HEVC),
                hardwareAccelerated = true,
                hevcProfiles = setOf(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10),
            ),
        ),
    )

    @Test
    fun `a failed codec walk keeps the pending request and the next start applies the real one`() {
        RobolectricEglSentinels.ensure()
        val vm = CameraViewModel(app, CameraEngine(app)).also { viewModel = it }
        // Let the init-time load settle before staging the pre-inventory placeholder state.
        drain(vm)
        val heifOnly = PhotoFormats(heif = true, jpeg = false, dngRaw = false)
        ViewModelTestAccess.state(vm).value = vm.state.value.copy(
            encoderInventoryLoaded = false,
            photoFormats = PhotoFormats(heif = false, jpeg = true, dngRaw = false),
            transfer = ColorTransfer.SDR,
        )
        ViewModelTestAccess.setField(vm, "pendingCodecUntilInventory", VideoCodec.HEVC)
        ViewModelTestAccess.setField(vm, "pendingTransferUntilInventory", ColorTransfer.HLG)
        ViewModelTestAccess.setField(vm, "pendingPhotoFormatsUntilInventory", heifOnly)
        var walks = 0
        var walkAnswer: CodecInventory? = null
        val cached: () -> CodecInventory? = { null }
        val walk: () -> CodecInventory? = { walks++; walkAnswer }
        ViewModelTestAccess.setField(vm, "cachedEncoderInventory", cached)
        ViewModelTestAccess.setField(vm, "walkEncoderInventory", walk)

        vm.onStart()
        drain(vm)

        assertEquals(1, walks)
        assertFalse("a failed walk is not device truth", vm.state.value.encoderInventoryLoaded)
        assertEquals(VideoCodec.HEVC, ViewModelTestAccess.field(vm, "pendingCodecUntilInventory"))
        assertEquals(ColorTransfer.HLG, ViewModelTestAccess.field(vm, "pendingTransferUntilInventory"))
        assertEquals(heifOnly, ViewModelTestAccess.field(vm, "pendingPhotoFormatsUntilInventory"))
        // What a background save would persist is still the operator's request, not JPEG/SDR.
        val extras = ViewModelTestAccess.invoke(vm, "currentExtras") as ExtraSettings
        assertEquals(ColorTransfer.HLG, extras.transfer)
        assertTrue(extras.heif)
        assertFalse(extras.jpeg)
        vm.onStop()

        // The provider recovered: the next start walks again and applies the request in full.
        walkAnswer = main10Inventory
        vm.onStart()
        drain(vm)

        assertEquals(2, walks)
        val s = vm.state.value
        assertTrue(s.encoderInventoryLoaded)
        assertEquals(VideoCodec.HEVC, s.videoCodec)
        assertEquals(ColorTransfer.HLG, s.transfer)
        assertEquals(heifOnly, s.photoFormats)

        // Loaded: a later start never walks again.
        vm.onStop()
        vm.onStart()
        drain(vm)
        assertEquals(2, walks)
        vm.onStop()
    }
}
