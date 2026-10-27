package me.hletrd.telecampro.camera

import android.util.Size

/** The exact extended-raster graph a HAL refused. Gamma is part of the identity, never a fallback axis. */
internal data class ExtendedVideoRasterRefusal(
    val routeKey: String,
    val size: Size,
    val transfer: ColorTransfer,
) {
    fun matches(routeKey: String, size: Size, transfer: ColorTransfer): Boolean =
        this.routeKey == routeKey && this.size == size && this.transfer == transfer
}

/**
 * Process-lifetime memory of a refused extended graph. An explicit retry clears only the identical
 * route/size/transfer tuple; a different route or Gamma neither clears nor inherits the refusal.
 */
internal class ExtendedVideoRasterRefusalLatch {
    @Volatile private var current: ExtendedVideoRasterRefusal? = null

    @Synchronized
    fun refuse(refusal: ExtendedVideoRasterRefusal) {
        current = refusal
    }

    @Synchronized
    fun clearForExplicitRetry(routeKey: String, size: Size, transfer: ColorTransfer) {
        if (current?.matches(routeKey, size, transfer) == true) current = null
    }

    fun blocks(routeKey: String, size: Size, transfer: ColorTransfer): Boolean =
        !shouldRetryRefusedRaster(current, routeKey, size, transfer)
}

/** Automatic choice may not reopen the refused graph; only the operator's identical retry may. */
internal fun shouldRetryRefusedRaster(
    refusal: ExtendedVideoRasterRefusal?,
    routeKey: String,
    size: Size,
    transfer: ColorTransfer,
    explicitRetry: Boolean = false,
): Boolean = refusal?.matches(routeKey, size, transfer) != true || explicitRetry

/** Largest offered raster strictly below the refused one, by area, never an upscale or a Gamma change. */
internal fun fallbackSize(refused: Size, offered: List<Size>): Size? {
    val refusedArea = refused.width.toLong() * refused.height.toLong()
    return offered.asSequence()
        .filter {
            val area = it.width.toLong() * it.height.toLong()
            it.width <= refused.width && it.height <= refused.height && area < refusedArea
        }
        .maxByOrNull { it.width.toLong() * it.height.toLong() }
}

/** The device-fatal counterpart of [ExtendedVideoRasterRefusal], reported before bounded recovery. */
internal class ExtendedVideoRasterConfigureFatalException(
    val routeKey: String,
    val size: Size,
    cause: Throwable,
) : IllegalStateException(
    "Extended video raster ${size.width}x${size.height} failed the camera device",
    cause,
)

/** One controller open's ownership facts; an older callback cannot mutate a newer request or Gamma. */
internal data class ExtendedVideoRasterOpenOwnership(
    val opticsGeneration: Long,
    val pipelineGeneration: Long,
    val requestedSize: Size?,
    val requestedSizeDirectWrites: Long,
    val openGate: Boolean,
    val deliveredSize: Size,
)

internal fun extendedVideoRasterCallbackOwnsCurrent(
    attempted: ExtendedVideoRasterOpenOwnership,
    controllerMatches: Boolean,
    currentOpticsGeneration: Long,
    currentPipelineGeneration: Long,
    currentRequestedSize: Size?,
    currentRequestedSizeDirectWrites: Long,
    currentOpenGate: Boolean,
    currentDeliveredSize: Size,
): Boolean = controllerMatches &&
    attempted.opticsGeneration == currentOpticsGeneration &&
    attempted.pipelineGeneration == currentPipelineGeneration &&
    attempted.requestedSize == currentRequestedSize &&
    attempted.requestedSizeDirectWrites == currentRequestedSizeDirectWrites &&
    attempted.openGate == currentOpenGate &&
    attempted.deliveredSize == currentDeliveredSize

/** Classified only after [ConfigureAttemptGate] has made this attempt's callback/throw exactly once. */
internal sealed interface ExtendedVideoRasterConfigureRejection {
    data class Soft(val routeKey: String, val size: Size, val opticsGeneration: Long) :
        ExtendedVideoRasterConfigureRejection

    data class Fatal(val failure: ExtendedVideoRasterConfigureFatalException) :
        ExtendedVideoRasterConfigureRejection
}

/**
 * Extended rasters have their own refusal door ahead of maker-log, RAW-log, P010 and the legacy
 * ladder. [deviceFatal] is supplied by [configureFailureIsDeviceFatal] so host tests can use a real
 * throwable without constructing Android's stub [android.hardware.camera2.CameraAccessException].
 */
internal fun classifyExtendedVideoRasterConfigureRejection(
    routeKey: String,
    extendedSize: Size?,
    opticsGeneration: Long,
    failure: Throwable?,
    deviceFatal: Boolean = false,
): ExtendedVideoRasterConfigureRejection? {
    if (extendedSize == null) return null
    if (deviceFatal && failure != null) {
        return ExtendedVideoRasterConfigureRejection.Fatal(
            ExtendedVideoRasterConfigureFatalException(routeKey, extendedSize, failure),
        )
    }
    return ExtendedVideoRasterConfigureRejection.Soft(routeKey, extendedSize, opticsGeneration)
}
