package me.hletrd.telecampro.ui

import android.os.Handler
import android.os.Message
import android.os.MessageQueue
import android.util.Range
import android.util.Rational
import android.util.Size
import kotlinx.coroutines.flow.MutableStateFlow
import me.hletrd.telecampro.camera.CameraCaps
import me.hletrd.telecampro.camera.CameraUiState

/**
 * Read/write reflection over [CameraViewModel]'s private state for Robolectric contract tests.
 *
 * The ViewModel exposes only its read-only [CameraViewModel.state]; tests that must stage an
 * accepted-session state a never-resumed final engine can never honestly publish (Ready, caps, a
 * recording flag) write the private `_state` flow directly, exactly as the existing VM suites do.
 */
internal object ViewModelTestAccess {
    @Suppress("UNCHECKED_CAST")
    fun state(vm: CameraViewModel): MutableStateFlow<CameraUiState> =
        CameraViewModel::class.java.getDeclaredField("_state")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<CameraUiState>

    fun field(vm: CameraViewModel, name: String): Any? =
        CameraViewModel::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(vm)

    fun setField(vm: CameraViewModel, name: String, value: Any?) {
        CameraViewModel::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    fun invoke(vm: CameraViewModel, name: String, vararg args: Any?): Any? =
        CameraViewModel::class.java.declaredMethods
            .single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }
            .invoke(vm, *args)

    fun clear(vm: CameraViewModel) {
        runCatching {
            CameraViewModel::class.java.getDeclaredMethod("onCleared")
                .apply { isAccessible = true }
                .invoke(vm)
        }
    }

    /**
     * How many messages queued on [handler]'s looper carry exactly [callback]. Robolectric's paused
     * looper runs the real framework MessageQueue, so this counts the actual pending posts — the
     * observation a "posted twice" defect needs (`Handler.hasCallbacks` answers only yes/no).
     */
    fun queuedCallbackCount(handler: Handler, callback: Runnable): Int {
        val queue: MessageQueue = handler.looper.queue
        val head = MessageQueue::class.java.getDeclaredField("mMessages")
            .apply { isAccessible = true }
        val next = Message::class.java.getDeclaredField("next").apply { isAccessible = true }
        var message = head.get(queue) as Message?
        var count = 0
        synchronized(queue) {
            while (message != null) {
                if (message.callback === callback) count++
                message = next.get(message) as Message?
            }
        }
        return count
    }

    /** A minimal rear photo caps packet; only [zoomRatioRange] and [equivalentFocalMm] vary. */
    fun caps(
        zoomRatioRange: Range<Float> = Range(1f, 10f),
        equivalentFocalMm: Float = 23f,
        isoRange: Range<Int> = Range(100, 100),
    ) = CameraCaps(
        logicalId = "test",
        physicalId = null,
        sensorOrientation = 90,
        minFocusDistanceDiopters = 0f,
        hyperfocalDiopters = 0f,
        isoRange = isoRange,
        exposureTimeRange = Range(1_000L, 1_000L),
        maxFrameDurationNs = 1_000L,
        evRange = Range(0, 0),
        evStep = Rational(1, 3),
        focalLengthsMm = floatArrayOf(4f),
        equivalentFocalMm = equivalentFocalMm,
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
        zoomRatioRange = zoomRatioRange,
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
}
