package me.hletrd.telecampro.camera

import android.hardware.camera2.CameraMetadata
import android.util.Size

/**
 * PMA110's OEM 8K video route (device-measured 2026-10-30/31, `docs/FIELD_CHECKS.md` A32).
 *
 * No camera advertises 7680×4320, so it is never in the camera's own size lists; the HAL's real 8K
 * usecase is the vendor operation mode 0x80A9 (`CameraHWConfiguration.config`: "8K SINGLE 30FPS EIS2
 * VIDEO", main and 3× tele only). Measured from a third-party probe on both lenses:
 *  - Standalone main (2) or 3× tele (4), SessionConfiguration type 0x80A9, and the session parameters
 *    AND every repeating request carrying `com.oplus.is.sdk.camera.package = byteArrayOf(1)` plus
 *    `com.oplus.is.from.extension = 1` select the real 8K sensor mode (main mode 29, 8160×4592; tele
 *    driver resIdx 44, the HAL table's "8k"). Without the pair the same session runs a binned mode
 *    (main 47, tele resIdx 42) and UPSCALES — a fake 8K that configures and records without error.
 *  - The 8K stream must reach a NON-flexible consumer, or cameraserver rounds it to an advertised
 *    size before the HAL sees it: [OemEightKInputBridge] is a PRIVATE reader with
 *    GPU_SAMPLED_IMAGE | VIDEO_ENCODE usage.
 *  - That reader as the session's ONLY stream, bridged into the GL input SurfaceTexture, held
 *    29.85 fps for 150 s with no dropped frame through the GL renderer into an 8-bit HEVC Main
 *    encoder at 4320×7680 (GL thread ~9.5 ms per frame). The tele held 29.89 fps.
 *  - Ten-bit does NOT hold: the same chain into Main10 ran 27.5–28.6 fps (the encoder's RGBA → ten-bit
 *    conversion at 8K), and the P010 YUV EGL target is refused by the encoder driver. So the route is
 *    Rec. 709 / 8-bit only, HEVC only (the measured codec).
 *  - The sensor cadence is fixed (33.50 ms main, 33.44 ms tele) under the [30,30] AE target range, the
 *    only range measured; the constant-rate clock puts it on the 29.97 / 30 grid by repeating one
 *    frame every ~8 s / ~6.7 s.
 * The camera session carries no still reader: the in-REC snapshot and the Photo shutter follow the
 * accepted output mask, like the HLG10 rung.
 */
internal val OEM_8K_VIDEO_SIZE = Size(7680, 4320)

/** The HAL's 8K video operation mode, captured from stock's own 8K session (CamX operation_mode). */
internal const val OEM_8K_SESSION_TYPE = 0x80A9

/** Parent rates measured on the route: the [30,30] range (29.97 and 30 selections). */
internal val OEM_8K_VIDEO_FPS: Set<Int> = setOf(30)

/**
 * Whether an opened route may offer the OEM 8K raster: the measured profile, a BACK standalone camera,
 * and one of the two lenses 0x80A9 lists ([hostLens]: the main or 3× tele, resolved by enumerated focal,
 * never by a camera-id literal).
 */
internal fun oemEightKRouteAdmitted(
    profileAdmits: Boolean,
    backRoute: Boolean,
    standalone: Boolean,
    hostLens: Boolean,
): Boolean = profileAdmits && backRoute && standalone && hostLens

/** The encoder/Gamma packet the route was measured with: Rec. 709 into HEVC. */
internal fun oemEightKPacketAdmitted(transfer: ColorTransfer, codec: VideoCodec): Boolean =
    transfer == ColorTransfer.SDR && codec == VideoCodec.HEVC

/**
 * Adds the unadvertised OEM raster (and its measured parent rates) to a route's RAW caps before the
 * ordinary encoder projection, which still has to prove the exact recorder format at that size. Never
 * written into the camera cache: a later Gamma/codec packet reprojects from camera truth and drops it.
 */
internal fun withOemEightKVideoSize(raw: CameraCaps, admitted: Boolean): CameraCaps {
    if (!admitted || OEM_8K_VIDEO_SIZE in raw.availableVideoSizes) return raw
    return raw.copy(
        availableVideoSizes = listOf(OEM_8K_VIDEO_SIZE) + raw.availableVideoSizes,
        measuredExtendedVideoFps = raw.measuredExtendedVideoFps + (OEM_8K_VIDEO_SIZE to OEM_8K_VIDEO_FPS),
    )
}

/** Whether THIS open is the OEM 8K session: an admitted route and an intent resolved onto the raster. */
internal fun oemEightKSessionWanted(routeAdmitted: Boolean, intent: VideoSessionIntent?): Boolean =
    routeAdmitted && intent != null && intent.streamSize == OEM_8K_VIDEO_SIZE && !intent.openGate &&
        !intent.rawLog && oemEightKPacketAdmitted(intent.transfer, intent.codec)

/**
 * HAL stabilization on the 8K route: always ON. 0x80A9 is the HAL's "8K … EIS2 VIDEO" usecase
 * (EisModeTable Super_EIS_8K) and reports CONTROL_VIDEO_STABILIZATION_MODE ON whatever is requested —
 * device-measured 2026-10-31 in the app, camera 4: a request of OFF came back ON in every 3A row of two
 * takes, while the non-8K session before it echoed OFF. PREVIEW_STABILIZATION was never measured there.
 * Asking for the mode the HAL runs keeps request and result in agreement; the OSD names it through
 * [CameraUiState.osdVideoStabMode].
 */
internal const val OEM_8K_VIDEO_STAB_MODE = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
