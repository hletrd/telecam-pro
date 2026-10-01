package me.hletrd.telecampro.ui.controls

import androidx.annotation.StringRes
import me.hletrd.telecampro.R
import me.hletrd.telecampro.camera.PhotoFormats
import me.hletrd.telecampro.camera.PhotoSessionOutputs
import me.hletrd.telecampro.camera.effectiveFor
import me.hletrd.telecampro.camera.withEdit

/** One output-format chip of the Shoot tab's Output row. */
internal enum class PhotoFormatAxis { HEIF, JPEG, DNG }

/**
 * What the Output row shows and how a tap edits the REQUEST — pure, so the chip row, the OSD and the
 * files cannot disagree again (AGG4-66 / AGG4-69).
 *
 * Since AGG3-18 `photoFormats` stays the operator's REQUEST and the accepted session's answer is
 * derived at render time. The OSD and the shutter already used that answer; the chips kept showing
 * the request, so a DNG-only request on FRONT (no RAW there) lit NO processed chip while every shot
 * saved HEIF, and a hi-res session showed HEIF selected while it wrote passthrough JPEG. Tapping the
 * "missing" HEIF to fix it permanently turned a DNG-only rear workflow into HEIF+DNG.
 *
 * - [displayed]: the processed axes (HEIF/JPEG) come from the accepted session's effective set;
 *   the DNG axis stays the request, because DNG is intent that MOVES the route (wanting it is what
 *   brings RAW into force), and the caption below explains a DNG that is not in force yet.
 * - While the camera is reconfiguring (`!cameraReady`) the accepted outputs are deliberately
 *   cleared (no stale reader may admit a capture), which is NOT a statement about the route: the
 *   row keeps the request, keeps its chips enabled, and says "Camera reconfiguring…" — the app's
 *   one name for that state — instead of "HEIF/JPEG unavailable · DNG only" or "Still capture
 *   unavailable" for the length of every aspect/fps/lens reopen.
 * - [toggled] folds a tap on the DISPLAYED set back into the request through [withEdit], the same
 *   merge the pre-inventory path uses, so a tap on a session stand-in edits only the tapped axis.
 */
internal data class PhotoFormatChipModel(
    val displayed: PhotoFormats,
    val heifEnabled: Boolean,
    val jpegEnabled: Boolean,
    val dngEnabled: Boolean,
    @StringRes val caption: Int?,
    private val request: PhotoFormats,
) {
    fun toggled(axis: PhotoFormatAxis): PhotoFormats {
        val edited = when (axis) {
            PhotoFormatAxis.HEIF -> displayed.copy(heif = !displayed.heif)
            PhotoFormatAxis.JPEG -> displayed.copy(jpeg = !displayed.jpeg)
            PhotoFormatAxis.DNG -> displayed.copy(dngRaw = !displayed.dngRaw)
        }
        return request.withEdit(displayed, edited)
    }
}

/**
 * Builds the Output row from the REQUEST and the ACCEPTED session.
 *
 * [rawAvailable] is the caller's [me.hletrd.telecampro.camera.rawSelectable] answer (neither pure
 * session truth nor bare capability). [heifAvailable] is the device's HEIF encoder fact.
 */
internal fun photoFormatChipModel(
    request: PhotoFormats,
    outputs: PhotoSessionOutputs,
    cameraReady: Boolean,
    rawAvailable: Boolean,
    heifAvailable: Boolean,
): PhotoFormatChipModel {
    val reconfiguring = !cameraReady
    val processedAvailable = reconfiguring || outputs.processed
    val displayed = if (!reconfiguring && outputs.hasStillTarget) {
        val effective = request.effectiveFor(outputs)
        PhotoFormats(heif = effective.heif, jpeg = effective.jpeg, dngRaw = request.dngRaw)
    } else {
        request
    }
    // RAW needs a processed sibling IN THE REQUEST: a session stand-in (FRONT/drop-RAW HEIF for a
    // DNG-only request) must not license switching DNG off, which would leave the request empty.
    val processedRequested = processedAvailable && request.wantsProcessedStill
    val rawSelected = rawAvailable && displayed.dngRaw
    // An accepted hi-res session's only still lane is the passthrough JPEG: a HEIF tap there could
    // never be written, so the chip is not offered rather than silently doing nothing.
    val hiResSession = !reconfiguring && outputs.hiRes
    // At least one processed format must survive unless RAW is on.
    val heifEnabled = heifAvailable && processedAvailable && !hiResSession &&
        (!displayed.heif || displayed.jpeg || rawSelected)
    val jpegEnabled = processedAvailable && (!displayed.jpeg || displayed.heif || rawSelected)
    val dngEnabled = rawAvailable && (!displayed.dngRaw || processedRequested)
    val caption = when {
        reconfiguring -> R.string.status_camera_reconfiguring
        // Same reasoning as the Ready-publication status: in VIDEO an accepted 10-bit session drops
        // the still readers by design, so the row says what it BOUGHT rather than what it lost.
        !outputs.processed && !rawAvailable -> noStillOutputCaption(outputs.hlg)
        // BEFORE the "switching" line: on a route that structurally cannot carry RAW (front, hi-res,
        // 10-bit video) nothing is switching. The DNG request itself survives as intent.
        !rawAvailable -> R.string.output_raw_unavailable
        !outputs.raw && request.dngRaw -> R.string.output_switching_single_lens
        // Word for word the status CameraEngine emits for the same accepted-output mask.
        !outputs.processed -> R.string.output_processed_unavailable_dng_only
        else -> null
    }
    return PhotoFormatChipModel(
        displayed = displayed,
        heifEnabled = heifEnabled,
        jpegEnabled = jpegEnabled,
        dngEnabled = dngEnabled,
        caption = caption,
        request = request,
    )
}
