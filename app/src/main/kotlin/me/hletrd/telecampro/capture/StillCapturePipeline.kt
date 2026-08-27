package me.hletrd.telecampro.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import me.hletrd.telecampro.camera.AspectRatio
import me.hletrd.telecampro.camera.CameraCaps
import me.hletrd.telecampro.camera.CameraRoute
import me.hletrd.telecampro.camera.CameraStatus
import me.hletrd.telecampro.camera.CameraStatusArgument
import me.hletrd.telecampro.camera.CameraStatusMessage
import me.hletrd.telecampro.camera.DeletedStillPublication
import me.hletrd.telecampro.camera.ManualControls
import me.hletrd.telecampro.camera.MeteringMode
import me.hletrd.telecampro.camera.RetainedStillDisposition
import me.hletrd.telecampro.camera.RotationMath
import me.hletrd.telecampro.camera.TeleSelection
import me.hletrd.telecampro.camera.CropBox
import me.hletrd.telecampro.camera.centerCropBox
import me.hletrd.telecampro.camera.status
import me.hletrd.telecampro.storage.CaptureFamilyKey
import me.hletrd.telecampro.storage.MediaStoreWriter
import me.hletrd.telecampro.storage.PendingOutputDiscardResult
import me.hletrd.telecampro.storage.PendingOutputAllocation

/** Immutable request-time state consumed by every output belonging to one shutter press. */
internal data class ShotSpec(
    val controls: ManualControls,
    val caps: CameraCaps?,
    val selection: TeleSelection?,
    val teleconverter: Boolean,
    // The mounted converter's magnification, snapshotted with [teleconverter] so this shot's EXIF
    // 35 mm focal describes the optic it was actually taken through (see Teleconverter.kt).
    val teleconverterMagnification: Float,
    // The declared phone's host tele focal the magnification multiplies (70 OPPO / 85 vivo kits);
    // snapshotted for the same EXIF honesty (review 2026-08-01 — the 70 default wrote false
    // focals on non-PMA110 hosts).
    val hostTeleEquivMm: Float = me.hletrd.telecampro.camera.LensChoice.TELE3X.targetEquivMm,
    val aspectRatio: AspectRatio,
    val jpegQuality: Int,
    val rotationDegrees: Int,
    val captureId: Int,
    val familyKey: CaptureFamilyKey,
    val requestedAtMs: Long,
    val takenAtMs: Long,
    // Fired against a hi-res (full-sensor) session: the processed JPEG saves PASSTHROUGH — the
    // decode→crop→rotate lane would inflate a ~200MP JPEG to an ~800 MB ARGB bitmap (OOM), so
    // orientation travels in EXIF like DNG and the 16:9 crop cannot apply (the 4:3 admission gate
    // upstream guarantees it is never wanted).
    val hiRes: Boolean = false,
    // Snapshotted at dispatch like [teleconverter]: a facing flip mid-save must not relabel this
    // shot's EXIF lens model or rotation. Front stills save UNMIRRORED (only the preview mirrors).
    val frontFacing: Boolean = false,
    // First-class route identity: EXTERNAL must never inherit the host handset's EXIF identity.
    val route: CameraRoute = if (frontFacing) CameraRoute.FRONT else CameraRoute.BACK,
)

/**
 * Everything the JPEG EXIF stamp needs, snapshotted AT THE SHOT (capture result + the controls
 * and optics active for that frame). Field set mirrors the stock camera's 3× reference sample
 * (FNumber/FocalLength/35 mm/LensModel/APEX values/metering/flash/program/zoom).
 */
internal data class ExifShot(
    val iso: Int,
    val expNs: Long,
    val lensFocalMm: Float,
    val lensApertureF: Float,
    val focal35mm: Int,
    val digitalZoom: Float,
    val evBiasStops: Float,
    val meteringMode: MeteringMode,
    val flashFired: Boolean,
    val exposureProgram: Int, // EXIF: 1=manual, 2=program, 4=shutter priority
    val manualExposure: Boolean,
    val manualWb: Boolean,
    val lensModel: String,
    /**
     * EXIF Make/Model straight from the running build (null when it reports nothing usable, in
     * which case the tag is omitted rather than written empty). Carried on the shot instead of read
     * inside [exifAttributeList] so that formatter stays a pure function of its input — it is
     * covered by plain JVM tests with no Android framework.
     */
    val deviceMake: String?,
    val deviceModel: String?,
    val takenAtMs: Long,
)

/** A fully written DNG awaiting MediaStore publication on the I/O lane. */
internal data class PendingDngPublication(
    val allocation: PendingOutputAllocation,
    val captureId: Int,
    val familyKey: CaptureFamilyKey,
    val completionMarkerDurable: Boolean,
) {
    val uri: android.net.Uri get() = allocation.uri
}

internal sealed interface DngWriteResult {
    data class Complete(val publication: PendingDngPublication) : DngWriteResult
    data class Rejected(
        val allocation: PendingOutputAllocation,
        val failure: Throwable,
    ) : DngWriteResult
    data class Failed(val failure: Throwable) : DngWriteResult
}

/**
 * The STILL SAVE LANES, extracted from CameraEngine (ARCH4-3 step 1 of the god-object plan):
 * decode/crop/rotate + HEIF/JPEG encode, DNG write, and JPEG EXIF re-stamp. Runs entirely on the
 * caller's executors (ioExecutor for processed stills/publication; the camera callback for the DNG
 * write, whose DngCreator needs the live Image), reads only immutable [ShotSpec]/[ExifShot]
 * snapshots, and
 * touches NO engine monitor — the zero-ownership-risk slice the extraction plan lands first. The
 * emit callbacks read the engine's live listeners at invoke time, so late listener wiring behaves
 * exactly as before the move.
 */
internal class StillCapturePipeline(
    private val context: Context,
    private val emitStatus: (CameraStatus) -> Unit,
    private val emitMediaSaved: (android.net.Uri, Int) -> Unit,
    private val emitRawSaved: (android.net.Uri, Int) -> Unit,
    /** Engine-owned check/publish/recheck boundary; survives ViewModel callback detachment. */
    private val publishStillOutput: (
        PendingOutputAllocation,
        Int,
        publish: () -> Boolean,
    ) -> DeletedStillPublication,
    /** Releases the successful-publication race owner only after the saved callback has returned. */
    private val finishPublishedStill: (PendingOutputAllocation, Int) -> Unit,
    // A COMPLETE output whose MediaStore publish failed. The same Engine owner that guards the
    // pre-publication boundary receives this retained result, so a family tombstoned during the
    // provider call still takes DISCARD instead of becoming recoverable media.
    private val emitPublishRetained: (PendingOutputAllocation, Int) -> RetainedStillDisposition,
    private val onRejectedOutputDisposition: (PendingOutputDiscardResult) -> Unit,
) {

    private fun discardRejectedOutput(allocation: PendingOutputAllocation) {
        val dispatch = MediaStoreWriter.dispatchRejectedOutput(
            context,
            allocation,
            onRejectedOutputDisposition,
        )
        if (dispatch != me.hletrd.telecampro.storage.RejectedOutputCleanupDispatch.ACCEPTED) {
            // REGISTERED remains durable launch-recovery ownership. Saturation never runs provider
            // or SQLite work inline on the save/camera caller.
            onRejectedOutputDisposition(PendingOutputDiscardResult.UNRESOLVED)
        }
    }

    /**
     * ONE decode → center-crop to [ShotSpec.aspectRatio] (processed stills only; [saveDng]'s RAW
     * output always stays full-frame) → rotate pass feeding BOTH processed encoders (PERF4-5): the
     * old per-format lanes each decoded/cropped/rotated the SAME bytes into a ~50 MB ARGB
     * intermediate, so a HEIF+JPEG shot paid the whole pixel pipeline twice serially on
     * [ioExecutor]. Each encoder keeps its own failure isolation (a HEIF write error must not cost
     * the JPEG) and the publish-or-delete policy documented on [writeProcessedHeif].
     */
    fun saveProcessedStills(
        bytes: ByteArray,
        spec: ShotSpec,
        exifShot: ExifShot,
        wantHeif: Boolean,
        wantJpeg: Boolean,
    ) {
        if (!wantHeif && !wantJpeg) return
        if (spec.hiRes) {
            // Hi-res passthrough lane (see [ShotSpec.hiRes]): the HAL JPEG bytes are written
            // UNMODIFIED and only EXIF (incl. the rotation as an orientation TAG) is stamped after.
            // HEIF is unavailable here — format normalization already collapsed it, and running it
            // anyway would be the exact 200MP decode this branch exists to avoid.
            if (wantJpeg) {
                runCatching { writePassthroughJpeg(bytes, spec, exifShot) }
                    .onFailure {
                        Log.e("StillCapturePipeline", "JPEG save failed", it)
                        emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status())
                    }
            }
            return
        }
        var decoded: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (d == null) { emitStatus(CameraStatusMessage.PHOTO_SAVE_FAILED.status()); return }
            decoded = d
            // ONE createBitmap does crop AND rotate (perf review #3b): the old crop-then-rotate
            // chain materialized a ~37 MB 16:9 intermediate that existed only to be re-read by the
            // rotate. Bitmap.createBitmap(src, x, y, w, h, m, filter) applies the source rect and
            // the matrix in a single allocation; a null matrix is a plain crop, and the
            // no-crop/no-rotate case returns [d] itself (createBitmap may also return the source
            // when the op is an identity — the === guards below already tolerate aliasing).
            val ar = spec.aspectRatio
            val degrees = spec.rotationDegrees % 360
            val r = if (ar == AspectRatio.W4_3 && degrees == 0) { // full sensor, upright: no-op
                d
            } else {
                val (x, y, cropW, cropH) = if (ar != AspectRatio.W4_3) {
                    centerCropBox(d.width, d.height, ar.w, ar.h)
                } else {
                    CropBox(0, 0, d.width, d.height)
                }
                val m = if (degrees != 0) Matrix().apply { postRotate(degrees.toFloat()) } else null
                Bitmap.createBitmap(d, x, y, cropW, cropH, m, true)
            }
            rotated = r
            // Release the decode the ENCODERS never read as soon as [rotated] exists (perf review
            // #3a): holding decoded (~50 MB ARGB) through the whole 1-3 s HEIF+JPEG encode kept a
            // ~127-152 MB working set live per shot and inflated the ART heap target — the most
            // plausible owner of the soak-observed Java growth. The finally block stays as the
            // safety net for every earlier-exit path.
            if (r !== d) {
                d.recycle()
                decoded = null
            }
            if (wantHeif) runCatching { writeProcessedHeif(r, spec, exifShot) }
                .onFailure {
                    Log.e("StillCapturePipeline", "HEIF save failed", it)
                    emitStatus(CameraStatusMessage.HEIF_SAVE_FAILED.status())
                }
            if (wantJpeg) runCatching { writeProcessedJpeg(r, spec, exifShot) }
                .onFailure {
                    Log.e("StillCapturePipeline", "JPEG save failed", it)
                    emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status())
                }
        } catch (t: Throwable) {
            if (t is ThreadDeath || t is VirtualMachineError && t !is OutOfMemoryError) throw t
            Log.e("StillCapturePipeline", "Photo processing failed", t)
            emitStatus(CameraStatusMessage.PHOTO_SAVE_FAILED.status())
        } finally {
            val rr = rotated
            val dd = decoded
            if (rr != null && rr !== dd) rr.recycle()
            dd?.recycle()
        }
    }

    /**
     * HEIF encode of the shared rotated bitmap. A fully written artifact is marked complete before
     * publication; persistent provider failure leaves it pending for launch recovery rather than
     * deleting a valuable take.
     */
    private fun writeProcessedHeif(rotated: Bitmap, spec: ShotSpec, exifShot: ExifShot) {
        val exifData = buildHeifExifData(exifShot, rotated.width, rotated.height)
        val allocation = MediaStoreWriter.createPendingImageAllocation(
            context,
            spec.familyKey.displayName("heic"),
            "image/heic",
        )
        if (allocation == null) { emitStatus(CameraStatusMessage.HEIF_SAVE_FAILED.status()); return }
        val u = allocation.uri
        // The Setup quality slider governs BOTH still containers (it used to silently apply only
        // to JPEG, leaving the DEFAULT photo format pinned at the encoder's 95).
        val quality = spec.jpegQuality
        val wrote = runCatching {
            MediaStoreWriter.openParcelFd(context, u, "rw")?.use { pfd ->
                HeifCapture.writeHeif(pfd.fileDescriptor, rotated, quality, exifData); true
            } ?: false
        }.getOrElse { failure -> discardRejectedOutput(allocation); throw failure }
        if (!wrote) {
            discardRejectedOutput(allocation)
            emitStatus(CameraStatusMessage.HEIF_SAVE_FAILED.status())
            return
        }
        val completion = MediaStoreWriter.markWriteComplete(context, u)
        completeStillPublication(
            kind = "HEIF",
            output = allocation,
            captureId = spec.captureId,
            markerDurable = completion.durable,
            effects = publicationEffects(spec.familyKey),
        )
    }

    /**
     * JPEG re-encode of the SAME rotated bitmap the HEIF got, so the two frame identically.
     * Same publish-or-delete policy as [writeProcessedHeif].
     */
    private fun writeProcessedJpeg(rotated: Bitmap, spec: ShotSpec, exifShot: ExifShot) {
        val allocation = MediaStoreWriter.createPendingImageAllocation(
            context,
            spec.familyKey.displayName("jpg"),
            "image/jpeg",
        )
        if (allocation == null) { emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status()); return }
        val u = allocation.uri
        val quality = spec.jpegQuality
        val wrote = runCatching {
            MediaStoreWriter.openOutputStream(context, u)?.use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
            } ?: false
        }.getOrElse { failure -> discardRejectedOutput(allocation); throw failure }
        if (!wrote) {
            discardRejectedOutput(allocation)
            emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status())
            return
        }
        // Bitmap.compress strips all metadata, so stamp the exposure EXIF back before publishing
        // (best-effort — a failed EXIF write must never lose the image itself).
        runCatching { writeJpegExif(u, exifShot) }
        val completion = MediaStoreWriter.markWriteComplete(context, u)
        completeStillPublication(
            kind = "JPEG",
            output = allocation,
            captureId = spec.captureId,
            markerDurable = completion.durable,
            effects = publicationEffects(spec.familyKey),
        )
    }

    /**
     * Hi-res JPEG lane: the HAL bytes go to disk verbatim (no decode, no crop, no pixel rotate),
     * then EXIF is stamped with TAG_ORIENTATION carrying the full capture rotation — the DNG
     * approach, because at ~200MP the ordinary pixel-upright pass is a guaranteed OOM. Same
     * publish-or-delete policy as [writeProcessedJpeg].
     */
    private fun writePassthroughJpeg(bytes: ByteArray, spec: ShotSpec, exifShot: ExifShot) {
        val allocation = MediaStoreWriter.createPendingImageAllocation(
            context,
            spec.familyKey.displayName("jpg"),
            "image/jpeg",
        )
        if (allocation == null) { emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status()); return }
        val u = allocation.uri
        val wrote = runCatching {
            MediaStoreWriter.openOutputStream(context, u)?.use { out ->
                out.write(bytes)
                true
            } ?: false
        }.getOrElse { failure -> discardRejectedOutput(allocation); throw failure }
        if (!wrote) {
            discardRejectedOutput(allocation)
            emitStatus(CameraStatusMessage.JPEG_SAVE_FAILED.status())
            return
        }
        // Best-effort like the processed lane — a failed EXIF write must never lose the image. The
        // orientation tag is the one exception a viewer NEEDS for uprightness, but a passthrough
        // with EXIF missing still beats a deleted take.
        runCatching { writeJpegExif(u, exifShot, exifOrientationFor(spec.rotationDegrees)) }
        val completion = MediaStoreWriter.markWriteComplete(context, u)
        completeStillPublication(
            kind = "JPEG",
            output = allocation,
            captureId = spec.captureId,
            markerDurable = completion.durable,
            effects = publicationEffects(spec.familyKey),
        )
    }

    /**
     * Writes RAW synchronously while [raw] is live and attempts the bounded durable marker. The
     * returned publication carries that outcome to [publishDng]; interrupted writes are deleted.
     */
    fun saveDng(
        raw: Image,
        chars: CameraCharacteristics,
        result: TotalCaptureResult,
        spec: ShotSpec,
        allocation: PendingOutputAllocation,
    ): DngWriteResult {
        require(allocation.familyKey == spec.familyKey) {
            "DNG allocation family does not match the captured shot"
        }
        val uri = allocation.uri
        var outputComplete = false
        try {
            val out = MediaStoreWriter.openOutputStream(context, uri)
                ?: throw IllegalStateException("Failed to open output stream")
            out.use {
                DngCapture.writeDng(it, raw, chars, result, exifOrientationFor(spec.rotationDegrees))
            }
            outputComplete = true
            val completion = MediaStoreWriter.markWriteComplete(context, uri)
            return DngWriteResult.Complete(
                PendingDngPublication(
                    allocation = allocation,
                    captureId = spec.captureId,
                    familyKey = spec.familyKey,
                    completionMarkerDurable = completion.durable,
                ),
            )
        } catch (t: Throwable) {
            // A fully-written DNG is handed to the publication lane with its marker outcome.
            // Interrupted writes remain REGISTERED and are deleted; marker exhaustion is returned.
            return if (!outputComplete) {
                DngWriteResult.Rejected(allocation, t)
            } else {
                DngWriteResult.Failed(t)
            }
        }
    }

    /** Publishes a completed DNG off the camera thread, including retry backoff and callbacks. */
    fun publishDng(pending: PendingDngPublication) {
        completeStillPublication(
            kind = "DNG",
            output = pending.allocation,
            captureId = pending.captureId,
            markerDurable = pending.completionMarkerDurable,
            effects = publicationEffects(pending.familyKey, emitSaved = emitRawSaved),
        )
    }

    private fun publicationEffects(
        familyKey: CaptureFamilyKey,
        emitSaved: (android.net.Uri, Int) -> Unit = emitMediaSaved,
    ) = StillPublicationEffects<PendingOutputAllocation>(
        withFamilyPublicationAuthority = { deleted, unavailable, live ->
            MediaStoreWriter.withFamilyPublicationAuthority(
                context = context,
                family = familyKey,
                deleted = deleted,
                unavailable = unavailable,
                live = live,
            )
        },
        discardDeletedFamily = { output -> MediaStoreWriter.discardPendingOutput(context, output) },
        publishOwned = { output, captureId ->
            publishStillOutput(output, captureId) { MediaStoreWriter.publish(context, output.uri) }
        },
        finishPublished = finishPublishedStill,
        emitSaved = { output, captureId -> emitSaved(output.uri, captureId) },
        emitRetained = emitPublishRetained,
        emitStatus = emitStatus,
    )

    private fun writeJpegExif(
        uri: android.net.Uri,
        shot: ExifShot,
        // NORMAL for the processed lane (pixels are rotated upright before encode); the hi-res
        // passthrough lane overrides with the capture rotation's tag, DNG-style.
        orientation: Int = RotationMath.ORIENTATION_NORMAL,
    ) {
        MediaStoreWriter.openParcelFd(context, uri, "rw")?.use { pfd ->
            val exif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
            applyExifAttributes(exif, shot)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                orientation.toString(),
            )
            exif.saveAttributes()
        }
    }

    private fun buildHeifExifData(shot: ExifShot, width: Int, height: Int): ByteArray {
        val temp = File.createTempFile("heif-exif-", ".jpg", context.cacheDir)
        return try {
            val seed = createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                FileOutputStream(temp).use { out ->
                    check(seed.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "EXIF seed encode failed" }
                }
            } finally {
                seed.recycle()
            }
            val exif = androidx.exifinterface.media.ExifInterface(temp)
            applyExifAttributes(exif, shot)
            // ExifInterface learned ImageWidth/ImageLength from the 1x1 JPEG seed above. If those
            // values ride into the HEIF unchanged, MediaStore indexes a valid full-resolution HEIF
            // as 1x1. Replace both primary and compressed-image dimension pairs with the already
            // cropped/rotated bitmap's true encoded size before extracting the APP1 payload.
            heifExifDimensionAttributes(width, height).forEach { (tag, value) ->
                exif.setAttribute(tag, value)
            }
            exif.saveAttributes()
            extractExifApp1(temp.readBytes()) ?: error("EXIF APP1 payload missing")
        } finally {
            // App-private cache scratch only; failure to remove it is harmless and never touches
            // user media. The normal output remains in MediaStore's pending lifecycle.
            runCatching { temp.delete() }
        }
    }

    private fun applyExifAttributes(
        exif: androidx.exifinterface.media.ExifInterface,
        shot: ExifShot,
    ) {
        exifAttributeList(shot).forEach { (tag, value) -> exif.setAttribute(tag, value) }
    }

    /** Maps a clockwise rotation (0/90/180/270) to the matching EXIF/TIFF orientation tag for DNG. */
    private fun exifOrientationFor(degrees: Int): Int = RotationMath.exifOrientationFor(degrees)
}

/** Injectable still-caller effects for the durable-before-publication contract. */
internal data class StillPublicationEffects<T>(
    /**
     * Runs exactly one deleted/unavailable/live branch under the output's process-wide exact-family
     * authority. The production branch spans the provider publish and saved callback; pure tests
     * default to a live family unless they explicitly select another branch.
     */
    val withFamilyPublicationAuthority: (
        deleted: () -> StillOutputPublication,
        unavailable: () -> StillOutputPublication,
        live: () -> StillOutputPublication,
    ) -> StillOutputPublication = { _, _, live -> live() },
    val discardDeletedFamily: (T) -> PendingOutputDiscardResult = {
        PendingOutputDiscardResult.UNRESOLVED
    },
    val publishOwned: (T, Int) -> DeletedStillPublication,
    val finishPublished: (T, Int) -> Unit,
    val emitSaved: (T, Int) -> Unit,
    val emitRetained: (T, Int) -> RetainedStillDisposition,
    val emitStatus: (CameraStatus) -> Unit,
)

/** Complete still result including deleted-family ownership, distinct from video publication. */
internal enum class StillOutputPublication {
    PUBLISHED,
    RETAINED_MARKER_UNAVAILABLE,
    RETAINED_PUBLICATION_UNAVAILABLE,
    DISCARDED_DELETED_CAPTURE,
    DISCARD_RETRY_PENDING,
}

/**
 * Completes the HEIF/JPEG/DNG caller contract without conflating retained bytes with publication.
 * A non-durable COMPLETE marker bypasses [StillPublicationEffects.publishOwned] entirely.
 */
internal fun <T> completeStillPublication(
    kind: String,
    output: T,
    captureId: Int,
    markerDurable: Boolean,
    effects: StillPublicationEffects<T>,
): StillOutputPublication = effects.withFamilyPublicationAuthority(
    {
        // The family marker itself is the durable launch veto. Exact-URI DISCARD/delete remains a
        // best-effort cleanup; even its double-failure must never fall through to publication.
        effects.discardDeletedFamily(output)
        StillOutputPublication.DISCARDED_DELETED_CAPTURE
    },
    {
        // An unreadable family journal is neither LIVE nor DELETED. Preserve complete bytes
        // privately and reuse the Engine's retained-output ownership so a same-Engine tombstone
        // that landed independently can still take the exact DISCARD path.
        effects.emitStatus(retainedSaveStatus(kind, markerDurable = markerDurable))
        effects.emitRetained(output, captureId).toStillOutputPublication(
            live = if (markerDurable) {
                StillOutputPublication.RETAINED_PUBLICATION_UNAVAILABLE
            } else {
                StillOutputPublication.RETAINED_MARKER_UNAVAILABLE
            },
        )
    },
    {
        if (!markerDurable) {
            effects.emitStatus(retainedSaveStatus(kind, markerDurable = false))
            effects.emitRetained(output, captureId).toStillOutputPublication(
                live = StillOutputPublication.RETAINED_MARKER_UNAVAILABLE,
            )
        } else {
            when (effects.publishOwned(output, captureId)) {
                DeletedStillPublication.LIVE_PUBLISHED -> {
                    try {
                        effects.emitSaved(output, captureId)
                    } finally {
                        effects.finishPublished(output, captureId)
                    }
                    StillOutputPublication.PUBLISHED
                }
                DeletedStillPublication.LIVE_PUBLICATION_FAILED -> {
                    effects.emitStatus(retainedSaveStatus(kind, markerDurable = true))
                    effects.emitRetained(output, captureId).toStillOutputPublication(
                        live = StillOutputPublication.RETAINED_PUBLICATION_UNAVAILABLE,
                    )
                }
                DeletedStillPublication.DISCARD_DELETED_CAPTURE ->
                    StillOutputPublication.DISCARDED_DELETED_CAPTURE
                DeletedStillPublication.DISCARD_RETRY_PENDING -> {
                    effects.emitStatus(CameraStatusMessage.COULD_NOT_DELETE_FILE.status())
                    StillOutputPublication.DISCARD_RETRY_PENDING
                }
            }
        }
    },
)

private fun RetainedStillDisposition.toStillOutputPublication(
    live: StillOutputPublication,
): StillOutputPublication = when (this) {
    RetainedStillDisposition.RETAIN_FOR_RECOVERY -> live
    RetainedStillDisposition.DISCARD_DELETED_CAPTURE ->
        StillOutputPublication.DISCARDED_DELETED_CAPTURE
    RetainedStillDisposition.DISCARD_RETRY_PENDING ->
        StillOutputPublication.DISCARD_RETRY_PENDING
}

/** Truthful retained-take status for either durable gate that left a completed artifact private. */
internal fun retainedSaveStatus(kind: String, markerDurable: Boolean): CameraStatus =
    (if (markerDurable) CameraStatusMessage.OUTPUT_SAVED_PENDING
    else CameraStatusMessage.OUTPUT_SAVED_PENDING_RECOVERY)
        .status(CameraStatusArgument.Text(kind))

/**
 * The complete tag→value list one [ExifShot] stamps into a processed still, hoisted out of the
 * ExifInterface apply loop (the [heifExifDimensionAttributes] precedent) so the APEX/rational math
 * pinned against the stock camera's 3× reference sample is host-testable. Pure java.* + TAG_*
 * String constants only; [StillCapturePipeline.applyExifAttributes] replays it verbatim.
 */
internal fun exifAttributeList(shot: ExifShot): List<Pair<String, String>> = buildList {
    if (shot.iso > 0) add(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to shot.iso.toString())
    if (shot.expNs > 0) {
        val sec = shot.expNs / 1_000_000_000.0
        add(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME to sec.toString())
        // APEX shutter speed = -log2(t), rational, matching the stock sample (6.908 at 1/120).
        val apex = -Math.log(sec) / Math.log(2.0)
        add(
            androidx.exifinterface.media.ExifInterface.TAG_SHUTTER_SPEED_VALUE to
                "${Math.round(apex * 1000)}/1000",
        )
    }
    if (shot.lensApertureF > 0f) {
        add(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER to shot.lensApertureF.toString())
        // APEX aperture = 2·log2(F) (stock: 2.35 at f/2.2).
        val apexAv = 2.0 * Math.log(shot.lensApertureF.toDouble()) / Math.log(2.0)
        add(
            androidx.exifinterface.media.ExifInterface.TAG_APERTURE_VALUE to
                "${Math.round(apexAv * 100)}/100",
        )
        add(
            androidx.exifinterface.media.ExifInterface.TAG_MAX_APERTURE_VALUE to
                "${Math.round(apexAv * 100)}/100",
        )
    }
    if (shot.lensFocalMm > 0f) {
        // Real lens focal (20.1 mm on the 3×), rational millimeters like the stock sample.
        add(
            androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH to
                "${Math.round(shot.lensFocalMm * 1000)}/1000",
        )
    }
    if (shot.focal35mm > 0) {
        add(
            androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to
                shot.focal35mm.toString(),
        )
    }
    add(
        androidx.exifinterface.media.ExifInterface.TAG_DIGITAL_ZOOM_RATIO to
            "${Math.round(shot.digitalZoom * 10000)}/10000",
    )
    // EV bias in sixths, the stock sample's denominator (0/6).
    add(
        androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_BIAS_VALUE to
            "${Math.round(shot.evBiasStops * 6)}/6",
    )
    add(
        androidx.exifinterface.media.ExifInterface.TAG_METERING_MODE to
            when (shot.meteringMode) {
                MeteringMode.MATRIX -> "5" // pattern
                MeteringMode.CENTER -> "2" // center-weighted (the stock default)
                MeteringMode.SPOT -> "3"
            },
    )
    // 0x1 = fired; 0x10 = "did not fire, compulsory off" (the stock sample's value).
    add(androidx.exifinterface.media.ExifInterface.TAG_FLASH to if (shot.flashFired) "1" else "16")
    add(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_PROGRAM to shot.exposureProgram.toString())
    add(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_MODE to if (shot.manualExposure) "1" else "0")
    add(androidx.exifinterface.media.ExifInterface.TAG_WHITE_BALANCE to if (shot.manualWb) "1" else "0")
    add(androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL to shot.lensModel)
    add(androidx.exifinterface.media.ExifInterface.TAG_COLOR_SPACE to "1") // sRGB

    val dt = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(shot.takenAtMs))
    val offset = java.text.SimpleDateFormat("XXX", java.util.Locale.US)
        .format(java.util.Date(shot.takenAtMs))
    add(androidx.exifinterface.media.ExifInterface.TAG_DATETIME to dt)
    add(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL to dt)
    add(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED to dt)
    add(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME to offset)
    add(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL to offset)
    // Pixels are rotated upright before encode — the orientation tag must say NORMAL,
    // not the invalid 0 exifinterface leaves when the tag was never present.
    add(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION to "1")
    // From the running build, not a literal: these were "OPPO" / "OPPO Find X9 Ultra", which wrote a
    // false camera model into every file taken on any other handset. TAG_MODEL is the model
    // IDENTIFIER by definition — photo software resolves the marketing name from it — so imitating
    // the stock app's market name here was both wrong off-device and wrong in principle.
    shot.deviceMake?.let { add(androidx.exifinterface.media.ExifInterface.TAG_MAKE to it) }
    shot.deviceModel?.let { add(androidx.exifinterface.media.ExifInterface.TAG_MODEL to it) }
}
